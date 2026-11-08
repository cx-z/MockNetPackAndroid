package com.mocknetpack.android.internal.capture

import com.mocknetpack.android.MockNetPack
import com.mocknetpack.android.MockResponse
import com.mocknetpack.android.internal.mock.MockRuleController
import java.io.ByteArrayOutputStream
import java.io.IOException
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.Okio

/**
 * OkHttp 应用拦截器（iOS MockNetPackURLProtocol 的安卓等价物）。
 *
 * 工作机制：
 * - 由宿主在其 OkHttpClient.Builder 上 addInterceptor 注入；门闩未接管
 *   （idle：capturing=false 且非启动缓冲）→ 直接放行（fail-open，业务零干预）。
 * - 拦截判定（canInit 等价）：接管期（capturing 或 启动缓冲）&& http(s) && 非 skipHeader。
 * - 命中本地 Mock 快照 → 合成 Response 直接回（AN-3 serve 分支 + AN-9 启动期
 *   用磁盘快照匹配）；未命中 → chain.proceed() 转发真实网络，响应体 tee 采集，
 *   完成后组装 TrafficEntry 攒批上传。
 * - SDK 自身请求（注册/心跳/上传）带 `X-MockNetPack-Skip`，直接放行。
 * - 线程安全：OkHttp 保证同一 Interceptor 实例的并发调用安全；内部状态
 *   读写经 TrafficCaptureController/MockRuleController 加锁。
 *
 * 实现要点（对齐 iOS 修复记录）：
 * - 请求体快照：应用拦截器内 request.body 可读一次，转发前用 Buffer 快照
 *   重建（保证转发不丢 body，对齐 iOS init 抢读 body 修复）。
 * - 响应体采集（tee）：包装 ResponseBody.source()，每次 read 同时写入上限
 *   1MB 的录制缓冲；不整体缓冲大响应、不破坏流式语义；body 消费完成（EOF
 *   或 close）时组装条目。超限记 `[binary N bytes]` 占位 + base64。
 * - 回放三级 fallback / header 过滤 / Content-Type 补全：AN-3 实现。
 * - AN-10 M1：条目组装统一交给 [HttpExchangeRecorder]（body 三态 / 1MB 截断 /
 *   base64 / codec 槽只有一份实现），本类只负责"从 OkHttp 取数 + 组装快照"。
 */
class MockNetPackInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        // 1) canInit 等价：可接管（有效会话 或 启动暂存期）。请求创建时快照采集代次，
        //    记录与迟到回包归属均使用该代次（对齐 iOS URLProtocol.init 快照 generation）。
        val generation = TrafficCaptureController.httpCaptureGeneration
            ?: return chain.proceed(chain.request())
        val original = chain.request()
        val scheme = original.url().scheme()
        if (scheme != "http" && scheme != "https") return chain.proceed(original)
        if (original.header(MockNetPack.SKIP_HEADER) != null) return chain.proceed(original)

        // 2) 请求体快照（serve 与转发共用；可读一次，转发前重建保证不丢 body）。
        val startNs = System.nanoTime()
        val requestBodySnapshot = snapshotRequestBody(original)

        // 3) AN-9: Mock 匹配优先（命中 → serve 合成回包，不访问真实网络）。
        //    门闩接管期（capturing 或 启动缓冲）均匹配——启动握手期用上次同步的
        //    磁盘快照即可命中（AN-9 快照持久化）；快照为空（从未同步 / 断连保护
        //    已清空）→ match 返回 null → 走真实网络（fail-open A）。
        val mock = MockRuleController.match(original.method(), original.url().encodedPath())
        if (mock != null) {
            // M15：media 规则（大图）先阻塞取 blob——成功则回放原始字节；失败
            // （断网/404/尺寸不符）记一条带 error 的命中流量后 fail-open 继续转发
            // 真实请求，业务侧与「未命中规则」一致，绝不静默吞掉。
            val media = mock.media
            val ruleID = mock.ruleId
            if (media != null && ruleID != null) {
                val mediaBytes = RuleMediaCache.load(ruleID, media)
                if (mediaBytes != null) {
                    return serve(mock, original, requestBodySnapshot, startNs, generation, mediaBytes)
                }
                recordMediaFailure(mock, original, requestBodySnapshot, startNs, generation)
            } else {
                return serve(mock, original, requestBodySnapshot, startNs, generation, null)
            }
        }

        // 转发：重建 body（快照字节）并去掉 SDK 跳过标记（不发给真实服务器）。
        // 注意：GET/HEAD 等无 body 请求不得重建 body（OkHttp 拒绝 GET+body）。
        val forwarded = original.newBuilder()
            .removeHeader(MockNetPack.SKIP_HEADER)
            .method(
                original.method(),
                if (original.body() != null) {
                    RequestBody.create(original.body()?.contentType(), requestBodySnapshot)
                } else {
                    null
                },
            )
            .build()

        val response: Response
        try {
            response = chain.proceed(forwarded)
        } catch (e: IOException) {
            // 请求失败：记录 error 条目（失败请求不保留状态码/响应字段）并上抛。
            HttpExchangeRecorder.record(
                requestExchange(
                    request = original,
                    requestBody = RequestBodyRecord(
                        sampleBytes = requestBodySnapshot,
                        fullSize = requestBodySnapshot.size.toLong(),
                        unavailable = false,
                    ),
                    startNs = startNs,
                    statusCode = null,
                    responseHeaders = null,
                    error = e.message ?: e.javaClass.simpleName,
                ),
                generation,
            )
            throw e
        }

        // AN7：记录来源修正 —— proceed 返回后选择与最终网络请求一致的请求。
        // networkResponse.request 含 Bridge 补全头（Host/User-Agent/Content-Length 等），
        // 最接近实际发送的请求；无 networkResponse（缓存命中/合成响应）时回退
        // response.request。URL/method/headers/body 统一取自该选定请求，不再混用
        // 链入口 original —— 记录点早于下游补头正是"请求头为空"的根因。
        // 记录为"响应侧关联请求"，不承诺底层字节级抓包（不覆盖重定向中间过程）。
        val selected = response.networkResponse()?.request() ?: response.request()
        // 请求体：仅当 selected.body() 与 SDK 自建转发体是同一实例（可重复读）
        // 时才做快照；被下游替换的一次性/流式体标记 unavailable，不二次消费。
        val selectedBody = snapshotSelectedBody(selected, forwarded, requestBodySnapshot)

        // 4) 响应体 tee 采集：body 消费完成（EOF 或 close）时组装条目。
        //    不整体缓冲大响应；录制缓冲上限 1MB。
        val tee = TeeResponseBody(
            response.body() ?: ResponseBody.create(null, ByteArray(0)),
            limit = MockNetPack.BODY_LIMIT_BYTES,
        )
        val bodyContentType = response.body()?.contentType()?.toString()
        val responseHeaders = headersToMultimap(response.headers())
        val statusCode = response.code()
        tee.onComplete = { body ->
            HttpExchangeRecorder.record(
                requestExchange(
                    request = selected,
                    requestBody = selectedBody,
                    startNs = startNs,
                    statusCode = statusCode,
                    responseHeaders = responseHeaders,
                    responseBodySample = body.recordedBytes(),
                    responseBodyFullSize = body.totalBytes,
                    responseBodyContentType = bodyContentType,
                    error = null,
                ),
                generation,
            )
        }
        return response.newBuilder().body(tee).build()
    }

    /// 命中本地 Mock 规则：合成 HTTP 响应直接回给调用方，不转发真实网络，
    /// 同时记一条 mocked=true 的临时流量（会话结束随临时流量清空）。
    /// 对齐 iOS serve：Content-Type 缺省补 application/json；回包 header
    /// 过滤键含 "proto-res" 的项（旧密钥轮换 header 不泄漏）；回放三级
    /// fallback bodyBase64 原字节 → bodyEncoder 编码 → UTF-8 文本。
    private fun serve(
        mock: MockResponse,
        original: Request,
        requestBodyBytes: ByteArray,
        startNs: Long,
        generation: Long,
        mediaBytes: ByteArray?,
    ): Response {
        // 时间戳必须在 sleep 之前捕获——否则延迟 N 秒后记录的 timestamp 比请求发起时间晚 N 秒，
        // 破坏"请求发起时间"的语义（对齐 iOS startTime）。durationMs 仍由 startNs 计算，包含延迟。
        val timestamp = TrafficCaptureController.rfc3339()
        val delay = mock.delayMs ?: 0
        if (delay > 0) {
            try {
                Thread.sleep(delay.toLong())
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                // 中断时直接返回，不再延迟（走下面的组装逻辑）
            }
        }
        var headerFields = LinkedHashMap(mock.headers ?: emptyMap())
        if (!headerFields.containsKey("Content-Type")) {
            // M15：media 规则以服务端记录的图片类型回包（服务端已写入 Content-Type，
            // 此处是快照丢头的兜底），其余规则沿用历史默认 application/json。
            headerFields["Content-Type"] = mock.media?.contentType ?: "application/json"
        }
        // 修复：删除动态密钥轮换 header（x-xc-proto-res）。mock 快照是历史捕获的，
        // 其 x-xc-proto-res 是当时的旧密钥；回放给接入方会导致后续真实请求用过期密钥。
        val filtered = headerFields.filterKeys { !it.lowercase().contains("proto-res") }
        val contentType = filtered["Content-Type"]

        // 回放三级 fallback（顺序不可调整）：
        // ① bodyBase64 有原始字节 → 按原始字节回放（未编辑的二进制规则直接启用）
        // ② body 有编辑过的文本 + encoder 可用 → 编码回私有协议字节（Web 编辑生效）
        // ③ 否则 → UTF-8 文本直传
        // 调整原因：未编辑二进制规则的 body 是 "[binary N bytes]" 占位文本，若
        // encoder 优先会把占位文本编码回放导致 App 解析失败；编辑保存时 Web 清除
        // bodyBase64，因此原始字节优先不影响已编辑规则。
        val bodyBytes: ByteArray = run {
            val fromBase64 = mock.bodyBase64?.let { b64 ->
                com.mocknetpack.android.internal.util.SDKBase64.decode(b64)
            }
            if (fromBase64 != null && fromBase64.isNotEmpty()) {
                fromBase64
            } else if (!mock.body.isNullOrEmpty()) {
                TrafficCaptureController.bodyEncoder?.invoke(mock.body, contentType)
                    ?: mock.body.toByteArray(Charsets.UTF_8)
            } else {
                ByteArray(0)
            }
        }

        // M15：media 规则的字节由调用方先行取到（mediaBytes），优先级高于
        // bodyBase64 > body；非 media 规则 mediaBytes 为 null，行为零改动。
        val payloadBytes: ByteArray = mediaBytes ?: bodyBytes

        HttpExchangeRecorder.record(
            CapturedExchange(
                method = original.method(),
                url = original.url().toString(),
                path = original.url().encodedPath(),
                query = original.url().encodedQuery() ?: "",
                requestHeaders = headersToMultimap(original.headers()),
                requestBodySample = requestBodyBytes,
                requestBodyFullSize = requestBodyBytes.size.toLong(),
                requestBodyContentType = original.headers()["Content-Type"],
                statusCode = mock.statusCode,
                responseHeaders = filtered.mapValues { listOf(it.value) },
                // M15：media 规则的字节在服务端 blob / 本地缓存里，规则体是空的；命中
                // 记录照抄 body 会与「空响应」不可区分，故写一句展示占位（与二进制规则
                // 的 "[binary N bytes]" 同一套约定），字节本身仍不进记录。
                mockResponseBodyText = mock.body?.takeIf { it.isNotEmpty() }
                    ?: mock.media?.let { "[原图 ${it.size} 字节，按需拉取]" },
                mockResponseBodyBase64 = mock.bodyBase64,
                mockResponseDecodeBytes = payloadBytes,
                responseBodyContentType = contentType,
                error = null,
                durationMs = elapsedMs(startNs),
                adapter = HttpExchangeRecorder.ADAPTER_OKHTTP,
                mocked = true,
                matchedRuleId = mock.ruleId,
                timestamp = timestamp,
            ),
            generation,
        )

        return Response.Builder()
            .request(original)
            .protocol(Protocol.HTTP_1_1)
            .code(mock.statusCode)
            .message("Mock")
            .headers(Headers.Builder().apply { for ((k, v) in filtered) add(k, v) }.build())
            .body(ResponseBody.create(contentType?.let { MediaType.parse(it) }, payloadBytes))
            .build()
    }

    /// media 取字节失败：记一条带 error 的 mocked 命中流量（随后调用方 fail-open
    /// 转发真实请求，转发路径再记一条真实条目——两条各自可判别，便于 Web 侧看出
    /// 「规则命中但字节没取到」）。
    private fun recordMediaFailure(
        mock: MockResponse,
        original: Request,
        requestBodyBytes: ByteArray,
        startNs: Long,
        generation: Long,
    ) {
        val timestamp = TrafficCaptureController.rfc3339()
        val headers = mock.headers ?: emptyMap()
        HttpExchangeRecorder.record(
            CapturedExchange(
                method = original.method(),
                url = original.url().toString(),
                path = original.url().encodedPath(),
                query = original.url().encodedQuery() ?: "",
                requestHeaders = headersToMultimap(original.headers()),
                requestBodySample = requestBodyBytes,
                requestBodyFullSize = requestBodyBytes.size.toLong(),
                requestBodyContentType = original.headers()["Content-Type"],
                statusCode = mock.statusCode,
                responseHeaders = headers.mapValues { listOf(it.value) },
                // 取字节失败：响应体两态一律置空（与历史 copy(error=..) 语义一致），
                // 只保留「规则命中但字节没取到」这一条可判别记录。
                mockResponseBodyText = null,
                mockResponseBodyBase64 = null,
                mockResponseDecodeBytes = ByteArray(0),
                responseBodyContentType = headers["Content-Type"],
                error = "media_unavailable",
                durationMs = elapsedMs(startNs),
                adapter = HttpExchangeRecorder.ADAPTER_OKHTTP,
                mocked = true,
                matchedRuleId = mock.ruleId,
                timestamp = timestamp,
            ),
            generation,
        )
    }

    /// 组装"请求侧 + 可选响应侧"的统一快照（转发 / 失败 / tee 完成三条路径共用）。
    /// URL / method / headers / body 统一取自传入的 request（AN7 修正后为最终网络请求）。
    private fun requestExchange(
        request: Request,
        requestBody: RequestBodyRecord,
        startNs: Long,
        statusCode: Int?,
        responseHeaders: Map<String, List<String>>?,
        error: String?,
        responseBodySample: ByteArray? = null,
        responseBodyFullSize: Long = 0,
        responseBodyContentType: String? = null,
    ): CapturedExchange = CapturedExchange(
        method = request.method(),
        url = request.url().toString(),
        path = request.url().encodedPath(),
        query = request.url().encodedQuery() ?: "",
        requestHeaders = headersToMultimap(request.headers()),
        requestBodySample = requestBody.sampleBytes,
        requestBodyFullSize = requestBody.fullSize,
        requestBodyUnavailable = requestBody.unavailable,
        requestBodyContentType = request.headers()["Content-Type"],
        statusCode = statusCode,
        responseHeaders = responseHeaders,
        responseBodySample = responseBodySample,
        responseBodyFullSize = responseBodyFullSize,
        responseBodyContentType = responseBodyContentType,
        error = error,
        durationMs = elapsedMs(startNs),
        adapter = HttpExchangeRecorder.ADAPTER_OKHTTP,
    )

    /// 单位统一的耗时计算（毫秒，下限 0）。
    private fun elapsedMs(startNs: Long): Int =
        ((System.nanoTime() - startNs) / 1_000_000).toInt().coerceAtLeast(0)

    // MARK: - body 治理（委托 HttpExchangeRecorder，保留旧调用点）

    /// 返回 (展示文本, base64)；二进制走占位 + base64 样本（实现见记录器）。
    internal fun bodyParts(data: ByteArray?, fullSize: Long): Pair<String, String?> =
        HttpExchangeRecorder.bodyParts(data, fullSize)

    /// 用 App 注入的解码器把二进制 body 解成可读 UTF-8 文本（仅展示）。
    internal fun decodedBody(data: ByteArray?, contentType: String?, isRequest: Boolean): String? =
        HttpExchangeRecorder.decodedBody(data, contentType, isRequest)

    /// 请求体快照：读一次并返回字节（转发用快照重建，保证不丢 body）。
    /// AN-10 M3a-0 会改为委托式 TeeRequestBody（此处保持 M1 行为不变）。
    private fun snapshotRequestBody(request: Request): ByteArray {
        val body = request.body() ?: return ByteArray(0)
        val buffer = Buffer()
        return try {
            body.writeTo(buffer)
            buffer.readByteArray()
        } catch (_: IOException) {
            ByteArray(0)
        }
    }

    /// 选定请求体的记录快照（AN7）：请求体字节 + 原始大小 + 可用性。
    /// sampleBytes 为入口快照的原始字节，未额外截断 —— 展示层统一在
    /// bodyParts / decodedBody 内按 BODY_LIMIT_BYTES 截断采样（绝不重建发送）；
    /// 注意：同一实例路径直接复用入口全量快照，大请求体在响应体消费完成前
    /// 会常驻全量字节（记录侧内存取舍，展示不受影响）。
    /// fullSize 为原始总大小（binary 占位 / truncated 前缀用）。
    /// unavailable=true 表示无法安全重复读取，未二次消费，展示 [body unavailable]。
    private data class RequestBodyRecord(
        val sampleBytes: ByteArray,
        val fullSize: Long,
        val unavailable: Boolean,
    )

    /// AN7：响应后对选定请求体做安全快照（有上限，仅记录展示，绝不重建发送）。
    /// 仅当 selected.body() 与 SDK 自建转发体（forwarded.body()）是同一实例时，
    /// 才确认可重复读：forwarded.body() 是 SDK 用 requestBodySnapshot 字节数组
    /// 构建的背衬体（RequestBody.create(byte[])），同一实例即字节可证明相同，
    /// 直接复用入口快照，避免对大 body 做第二次全量重读（F2）。
    /// 否则不触碰、不二次消费，标记 unavailable —— 覆盖一次性 / 流式 / 被下游
    /// 替换的请求体。
    private fun snapshotSelectedBody(
        selected: Request,
        forwarded: Request,
        snapshotBytes: ByteArray,
    ): RequestBodyRecord {
        val body = selected.body()
        if (body == null) return RequestBodyRecord(ByteArray(0), 0, false)
        if (body !== forwarded.body()) return RequestBodyRecord(ByteArray(0), 0, true)
        return RequestBodyRecord(snapshotBytes, snapshotBytes.size.toLong(), false)
    }

    /// 头字典（保留原始大小写，对齐 iOS allHTTPHeaderFields 与 Web 展示习惯；
    /// 不使用 Headers.toMultimap() 的小写键 —— 历史问题修复）。多值按原顺序。
    private fun headersToMultimap(headers: Headers): Map<String, List<String>> {
        val result = LinkedHashMap<String, MutableList<String>>()
        for (i in 0 until headers.size()) {
            result.getOrPut(headers.name(i)) { mutableListOf() }.add(headers.value(i))
        }
        return result
    }
}

/// 响应体 tee 包装：每次 source().read 同时抄写录制缓冲（上限 1MB），
/// 不整体缓冲大响应、不破坏流式语义；body 消费完成（EOF 或 close）回调
/// onComplete（幂等），供拦截器组装 TrafficEntry。
internal class TeeResponseBody(
    private val delegate: ResponseBody,
    private val limit: Long,
) : ResponseBody() {

    /// 消费完成回调（EOF 或 close，幂等）。由拦截器设置。
    @Volatile
    var onComplete: ((TeeResponseBody) -> Unit)? = null

    private val recorded = ByteArrayOutputStream()
    private var recordedBytes = 0L
    private var totalBytesValue = 0L
    private var completeValue = false

    val totalBytes: Long get() = totalBytesValue

    override fun contentType() = delegate.contentType()
    override fun contentLength() = delegate.contentLength()

    fun recordedBytes(): ByteArray = recorded.toByteArray()

    override fun source(): BufferedSource {
        val source = delegate.source()
        val tee = object : ForwardingSource(source) {
            override fun read(sink: Buffer, byteCount: Long): Long {
                val n = super.read(sink, byteCount)
                if (n > 0) {
                    totalBytesValue += n
                    if (recordedBytes < limit) {
                        val room = minOf(limit - recordedBytes, n)
                        // 新读入数据位于 sink 尾部 [size-n, size)，复制不消费。
                        sink.copyTo(recorded, sink.size() - n, room)
                        recordedBytes += room
                    }
                } else if (n == -1L) {
                    finish()
                }
                return n
            }
        }
        return Okio.buffer(tee)
    }

    override fun close() {
        finish()
        super.close()
    }

    private fun finish() {
        if (!completeValue) {
            completeValue = true
            onComplete?.invoke(this)
        }
    }
}
