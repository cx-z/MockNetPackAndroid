package com.mocknetpack.android.internal.capture

import com.mocknetpack.android.BinaryCompression
import com.mocknetpack.android.MockNetPack
import com.mocknetpack.android.TrafficEntry
import com.mocknetpack.android.TrafficUploadRequest
import com.mocknetpack.android.internal.codec.BinaryCodec
import com.mocknetpack.android.internal.connection.ConnectionClient
import com.mocknetpack.android.internal.connection.ConnectionClientError
import com.mocknetpack.android.internal.log.SDKLogger
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * 流量采集控制器（对齐 iOS TrafficCaptureController）：会话开关、攒批、
 * 上传、二进制协议编解码器槽。
 *
 * - 跟随抓包会话启停：`updateSession(capturing:sessionID:)` 由连接层心跳结果
 *   驱动——capturing 时采集生效，idle 时停止并清空待上传批次（会话结束本地
 *   未上传的临时记录一并丢弃，与服务端"临时记录随会话清空"一致）。
 * - 攒批上传：200 条 / 8MB / 2s 触发；单批 ≤500 条（契约）；上传失败直接
 *   丢弃批次、不重传、不本地缓存（fail-open）。
 * - codec 槽：`registerBinaryCodec` 安装 dispatch 闭包（响应解码/编码、
 *   请求体解码/编码），Interceptor 在组装记录时读取。
 * - Android 侧无全局 URLProtocol 注入：拦截器由宿主 `addInterceptor` 显式
 *   加入（D1 差异）；arm() 仅幂等标记（首次接入扫码前创建的 client 也携带拦截器，
 *   未连接时 isCapturing=false 不拦业务）。
 */
object TrafficCaptureController {

    /// 单批条数阈值（≤500 契约上限，留余量）。
    const val MAX_BATCH_COUNT = 200

    /// 单批字节阈值（避免超服务端 10MB 请求体上限）。
    const val MAX_BATCH_BYTES = 8 * 1024 * 1024

    /// 攒批定时触发间隔（毫秒；对应 iOS 2s）。
    const val FLUSH_INTERVAL_MS = 2_000L

    // MARK: - 测试注入点

    /// 上传客户端工厂：测试注入指向 MockServer 的客户端。
    var clientFactory: ((String) -> ConnectionClient)? = null
    /// 攒批定时触发间隔（毫秒）；测试可调小。
    @Volatile
    var flushIntervalMs: Long = FLUSH_INTERVAL_MS
    /// 攒批循环调度器；默认单线程，测试可替换。
    @Volatile
    internal var schedulerDispatcher: CoroutineDispatcher = defaultScheduler()

    // MARK: - 状态（锁保护）

    private val lock = Any()
    private var running = false
    private var capturingValue = false
    /// 仅本次有服务器的启动握手期间暂存 HTTP；首个会话结果或终止后关闭（对齐 iOS startupBufferingValue）。
    private var startupBufferingValue = false
    /// HTTP 请求所属采集代次；启动暂存晋升为首个会话时保留，其余会话切换作废（对齐 iOS httpGeneration）。
    private var httpGeneration = 0L
    /// 是否已 arm（连接层 unconfigured 启动时标记；start 幂等不重复启动定时器）。
    private var armedValue = false
    private var sessionIDValue: String? = null
    private var appIDValue: String? = null
    private var didValue: String? = null
    private var serverURLValue: String? = null
    private var logHandler: ((String) -> Unit)? = null

    private var pending = mutableListOf<TrafficEntry>()
    private var pendingBytes = 0
    private var scope: CoroutineScope? = null

    // MARK: - 二进制协议编解码器槽

    /// 一次 registerBinaryCodec 注册的 codec。加解密由业务方闭包提供；
    /// contentType 匹配 / gzip 压缩 / UTF-8 转换由 SDK 完成。
    private data class CodecRegistration(
        val key: String,
        val compression: BinaryCompression,
        val responseEncrypt: (ByteArray) -> ByteArray?,
        val responseDecrypt: (ByteArray) -> ByteArray?,
        /// 请求体业务加密闭包（可选；请求体回放/改写预留，当前无调用点）。
        val requestEncrypt: ((ByteArray) -> ByteArray?)?,
        /// 请求体专用解密闭包（可选；nil = 请求体不解码）。
        val requestDecrypt: ((ByteArray) -> ByteArray?)?,
    )

    /// codec 槽独立小锁（设置低频、读取高频，与 iOS decoderLock 同构）。
    private val codecLock = Any()
    private var codecs = mutableListOf<CodecRegistration>()

    /// 响应体解码：raw 密文 → 业务解密 → UTF-8 文本（仅展示）。
    internal var bodyDecoder: ((ByteArray, String?) -> String?)? = null
        get() = synchronized(codecLock) { field }
        set(value) {
            synchronized(codecLock) { field = value }
        }

    /// 响应体编码：编辑文本 → UTF-8 → gzip（按 compression）→ 业务加密（Mock 回放）。
    internal var bodyEncoder: ((String, String?) -> ByteArray?)? = null
        get() = synchronized(codecLock) { field }
        set(value) {
            synchronized(codecLock) { field = value }
        }

    /// 请求体专用解码槽（业务请求体/响应体编码不对称时由 requestDecrypt 安装）。
    internal var requestBodyDecoder: ((ByteArray, String?) -> String?)? = null
        get() = synchronized(codecLock) { field }
        set(value) {
            synchronized(codecLock) { field = value }
        }

    /// 请求体专用编码槽（预留，当前无调用点）。
    internal var requestBodyEncoder: ((String, String?) -> ByteArray?)? = null
        get() = synchronized(codecLock) { field }
        set(value) {
            synchronized(codecLock) { field = value }
        }

    // MARK: - 生命周期

    /// 预注入（连接层 unconfigured 启动时调用；幂等）：仅"武装"采集开关，
    /// 不发起网络。Android 侧拦截器由宿主提前 addInterceptor 注入（D1），
    /// 未连接时 isCapturing=false，业务零干预。
    fun arm() {
        synchronized(lock) {
            armedValue = true
        }
        log("traffic capture armed (interceptor installed by host)")
    }

    fun start(
        serverURL: String,
        appID: String,
        did: String,
        logHandler: ((String) -> Unit)? = null,
    ) {
        synchronized(lock) {
            if (running) return
            running = true
            // 首个心跳会话结果前开启有界暂存：capturing 未确认也接管 HTTP（对齐 iOS）。
            startupBufferingValue = true
            httpGeneration += 1
            serverURLValue = serverURL
            appIDValue = appID
            didValue = did
            this.logHandler = logHandler
            pending = mutableListOf()
            pendingBytes = 0
            val s = CoroutineScope(SupervisorJob() + schedulerDispatcher)
            scope = s
            s.launch {
                while (isActive) {
                    delay(flushIntervalMs)
                    flush()
                }
            }
        }
        log("traffic capture started (startup buffering enabled until first session result)")
    }

    fun stop() {
        synchronized(lock) {
            if (!running) return
            running = false
            capturingValue = false
            startupBufferingValue = false
            httpGeneration += 1 // 作废所有在途采集代次，迟到回包不得写入后续启动
            sessionIDValue = null
            pending = mutableListOf()
            pendingBytes = 0
            scope?.cancel()
            scope = null
        }
        // M15：会话结束清空 media 字节缓存（规则快照里只剩 sha256，重新拉取即可）。
        RuleMediaCache.reset()
        log("traffic capture stopped")
    }

    /// 当前会话的 (serverURL, app, did)：media 回放据此拼 media 端点。
    /// 未 start（或尚未登记服务器）时返回 null → RuleMediaCache fail-open。
    internal val mediaReplayContext: MediaReplayContext?
        get() = synchronized(lock) {
            val url = serverURLValue
            val app = appIDValue
            val did = didValue
            if (url == null || app == null || did == null) null
            else MediaReplayContext(serverURL = url, app = app, did = did)
        }

    /// media 回放所需会话参数（对齐 iOS mediaReplayContext 元组）。
    internal data class MediaReplayContext(val serverURL: String, val app: String, val did: String)

    /// 会话状态更新（连接层心跳结果驱动）。
    /// 首个心跳结果（wasBuffering）：
    /// - capturing=true → 启动暂存晋升为该会话第一批上传（锁内取出、锁外上传），代次保留，
    ///   在飞请求（旧代次）仍可归入首个会话（对齐 iOS updateSession 晋升语义）；
    /// - capturing=false（idle）→ 清空暂存 + 作废代次，迟到回包丢弃，不污染后续手动激活会话。
    /// 非首心跳的会话切换（开始/结束）清空待上传批次并作废代次；值未变（心跳重复下发
    /// 同一状态）时不清空——否则每次心跳都会丢掉 flush 定时器尚未触发的一整段已录流量。
    fun updateSession(capturing: Boolean, sessionID: String?) {
        var startupUpload: TrafficUploadRequest? = null
        val changed = synchronized(lock) {
            if (!running) return
            val wasBuffering = startupBufferingValue
            startupBufferingValue = false
            val c = capturingValue != capturing || sessionIDValue != sessionID
            capturingValue = capturing
            sessionIDValue = sessionID
            if (c || wasBuffering) {
                if (wasBuffering && capturing) {
                    // 首次心跳激活：代次保留（在飞请求仍归首个会话，对齐 iOS）；
                    // 有暂存则晋升为该会话第一批。
                    if (pending.isNotEmpty() &&
                        serverURLValue != null && appIDValue != null && didValue != null && sessionID != null
                    ) {
                        startupUpload = TrafficUploadRequest(appIDValue!!, didValue!!, sessionID, pending.toList())
                        pending = mutableListOf()
                        pendingBytes = 0
                    }
                } else {
                    pending = mutableListOf()
                    pendingBytes = 0
                    httpGeneration += 1
                }
            }
            c || wasBuffering
        }
        if (!changed) return
        log(if (capturing) "capture session active ($sessionID)" else "capture session idle; pending cleared")
        startupUpload?.let { uploadBatch(it, "startup") }
        if (capturing) flush()
    }

    /// 当前是否处于抓包中（服务端已确认有效会话；Mock 执行等读取）。
    val isCapturing: Boolean
        get() = synchronized(lock) { running && capturingValue }

    /// 当前可接管 HTTP 的采集代次：有效会话或启动暂存期返回当前代次，否则 null。
    /// Interceptor 的 canInit 门闩（对齐 iOS httpCaptureGeneration）——启动暂存不改变
    /// 公开 capturing 状态，但允许启动握手窗口内的业务请求被接管、有界暂存。
    val httpCaptureGeneration: Long?
        get() = synchronized(lock) {
            if (running && (capturingValue || startupBufferingValue)) httpGeneration else null
        }

    /// 拒绝结果只取消发起握手时绑定的启动代次；归属校验与清空在同一锁内，
    /// 旧代拒绝不影响重启（对齐 iOS cancelStartupCapture）。
    fun cancelStartupCapture(generation: Long?) {
        synchronized(lock) {
            if (generation == null || !startupBufferingValue || generation != httpGeneration) return
            startupBufferingValue = false
            pending = mutableListOf()
            pendingBytes = 0
            httpGeneration += 1
        }
    }

    // MARK: - 采集与攒批

    /// 记录一条完整请求/响应（由 MockNetPackInterceptor 调用）。
    /// generation 由 Interceptor 在请求创建时快照；启动暂存复用 200 条/8MB 有界额度
    /// （按全量序列化大小计费，对齐 iOS），超限丢新条目、保留最早请求。
    internal fun record(entry: TrafficEntry, generation: Long? = null) {
        val (tooMany, tooBig) = synchronized(lock) {
            if (!running || !(capturingValue || startupBufferingValue)) return
            if (generation != null && generation != httpGeneration) return
            val bytes: Int
            if (startupBufferingValue) {
                // 启动暂存：有界额度（200 条 / 8MB 全量序列化大小，超限丢新保最早，对齐 iOS）。
                val size = entryJson(entry).toString().toByteArray(Charsets.UTF_8).size
                if (pending.size >= MAX_BATCH_COUNT || size > MAX_BATCH_BYTES - pendingBytes) return
                bytes = size
            } else {
                bytes = entry.requestBody.toByteArray(Charsets.UTF_8).size +
                    (entry.responseBody?.toByteArray(Charsets.UTF_8)?.size ?: 0) + 256
            }
            pending.add(entry)
            pendingBytes += bytes
            (pending.size >= MAX_BATCH_COUNT) to (pendingBytes >= MAX_BATCH_BYTES)
        }
        if (tooMany || tooBig) {
            flush()
        }
    }

    /// 立即上传待上传批次（定时/阈值/会话激活时调用；无数据或无会话则 no-op）。
    fun flush() {
        val batch: List<TrafficEntry>
        val sessionID: String
        val appID: String
        val did: String
        synchronized(lock) {
            if (!capturingValue || sessionIDValue == null || serverURLValue == null ||
                appIDValue == null || didValue == null || pending.isEmpty()
            ) {
                return
            }
            batch = pending.toList()
            sessionID = sessionIDValue!!
            appID = appIDValue!!
            did = didValue!!
            pending = mutableListOf()
            pendingBytes = 0
        }

        uploadBatch(TrafficUploadRequest(appID, did, sessionID, batch), "traffic")
    }

    /// 锁外上传一个已绑定设备/会话身份的批次（flush 定时/阈值/会话激活与启动暂存
    /// 晋升共用同一路径）；失败直接丢弃、不重传（fail-open）。与 iOS uploadHTTP 同语义。
    private fun uploadBatch(req: TrafficUploadRequest, note: String) {
        val serverURL = synchronized(lock) { serverURLValue } ?: return
        val client = makeClient(serverURL)
        val body = uploadRequestJson(req)
        val uploadScope = scope ?: return
        uploadScope.launch {
            try {
                client.post("traffic", body)
                log("uploaded ${req.entries.size} $note entries")
            } catch (e: ConnectionClientError) {
                // 不做本地缓存、不重传；失败批次直接丢弃（fail-open）。
                log("$note upload failed: ${e.message}; batch dropped (no retry)")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            }
        }
    }

    // MARK: - 二进制协议编解码器

    /// 一次注册同时接管两条链路：
    /// - responseDecrypt（展示链路）：真实响应密文 → 业务解密 → 可读文本；
    /// - responseEncrypt（回放链路）：编辑文本 → UTF-8 → 按 compression 压缩 → 业务加密。
    /// 匹配：按注册顺序，contentType（大小写不敏感）包含 contentTypeKey 即命中，首个命中生效。
    fun registerBinaryCodec(
        contentTypeKey: String,
        compression: BinaryCompression,
        responseEncrypt: (ByteArray) -> ByteArray?,
        responseDecrypt: (ByteArray) -> ByteArray?,
        requestEncrypt: ((ByteArray) -> ByteArray?)? = null,
        requestDecrypt: ((ByteArray) -> ByteArray?)? = null,
    ) {
        if (contentTypeKey.isEmpty()) return
        synchronized(codecLock) {
            codecs.add(
                CodecRegistration(
                    key = contentTypeKey,
                    compression = compression,
                    responseEncrypt = responseEncrypt,
                    responseDecrypt = responseDecrypt,
                    requestEncrypt = requestEncrypt,
                    requestDecrypt = requestDecrypt,
                ),
            )
            installCodecDispatchLocked()
        }
    }

    /// 测试辅助：清空已注册 codec 与 dispatch 槽位。
    internal fun resetBinaryCodecs() {
        synchronized(codecLock) {
            codecs = mutableListOf()
            bodyDecoder = null
            bodyEncoder = null
            requestBodyDecoder = null
            requestBodyEncoder = null
        }
    }

    /// 按注册顺序取首个 contentType（大小写不敏感）包含 key 的 codec。
    private fun matchingCodec(contentType: String?): CodecRegistration? {
        val lower = contentType?.lowercase() ?: return null
        return synchronized(codecLock) { codecs.firstOrNull { lower.contains(it.key.lowercase()) } }
    }

    /// 把 dispatch 闭包安装到各槽位（调用方须已持有 codecLock）。
    private fun installCodecDispatchLocked() {
        bodyDecoder = decoder@{ data, contentType ->
            val codec = matchingCodec(contentType) ?: return@decoder null
            val plain = codec.responseDecrypt(data) ?: return@decoder null
            val text = tryDecodeUtf8(plain) ?: return@decoder null
            // 解码失败守卫：部分业务底层解码器（如 zlib ungzip）失败时不返回 nil，
            // 而返回错误描述文本（如 "Z_DATA_ERROR"）。识别 zlib 错误宏名特征。
            if (isDecoderErrorText(text)) null else text
        }
        // 请求体专用解码槽：仅当某 codec 注册了 requestDecrypt 时才启用。
        requestBodyDecoder = if (codecs.any { it.requestDecrypt != null }) {
            reqDecoder@{ data, contentType ->
                val codec = matchingCodec(contentType) ?: return@reqDecoder null
                val requestDecrypt = codec.requestDecrypt ?: return@reqDecoder null
                val plain = requestDecrypt(data) ?: return@reqDecoder null
                val text = tryDecodeUtf8(plain) ?: return@reqDecoder null
                if (isDecoderErrorText(text)) null else text
            }
        } else {
            null
        }
        // 请求体专用编码槽（预留）：仅当某 codec 注册了 requestEncrypt 时才启用。
        requestBodyEncoder = if (codecs.any { it.requestEncrypt != null }) {
            reqEncoder@{ text, contentType ->
                val codec = matchingCodec(contentType) ?: return@reqEncoder null
                val requestEncrypt = codec.requestEncrypt ?: return@reqEncoder null
                val raw = text.toByteArray(Charsets.UTF_8)
                requestEncrypt(raw)
            }
        } else {
            null
        }
        bodyEncoder = encoder@{ text, contentType ->
            val codec = matchingCodec(contentType) ?: return@encoder null
            val raw = text.toByteArray(Charsets.UTF_8)
            val payload = when (codec.compression) {
                BinaryCompression.NONE -> raw
                BinaryCompression.GZIP -> BinaryCodec.gzipCompressed(raw) ?: return@encoder null
            }
            codec.responseEncrypt(payload)
        }
    }

    /// 识别解码器失败时业务底层常返回的错误描述文本（zlib 错误宏名）。
    internal fun isDecoderErrorText(text: String): Boolean {
        val markers = listOf(
            "Z_DATA_ERROR", "Z_MEM_ERROR", "Z_BUF_ERROR",
            "Z_STREAM_ERROR", "Z_VERSION_ERROR", "Z_ERRNO", "Z_NEED_DICT",
        )
        return markers.any { text.contains(it) }
    }

    /// 严格 UTF-8 解码（非法序列返回 null，避免 U+FFFD 误判文本）。
    internal fun tryDecodeUtf8(bytes: ByteArray): String? {
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: java.nio.charset.CharacterCodingException) {
            null
        }
    }

    // MARK: - 契约 JSON 序列化（org.json 手写，字段名对齐 iOS Codable / 契约）

    internal fun uploadRequestJson(req: TrafficUploadRequest): JSONObject {
        val entries = JSONArray()
        for (e in req.entries) entries.put(entryJson(e))
        return JSONObject()
            .put("app", req.app)
            .put("did", req.did)
            .put("sessionId", req.sessionId)
            .put("entries", entries)
    }

    internal fun entryJson(e: TrafficEntry): JSONObject {
        val j = JSONObject()
        j.put("timestamp", e.timestamp)
        j.put("method", e.method)
        j.put("url", e.url)
        j.put("path", e.path)
        j.put("query", e.query)
        j.put("requestHeaders", headersJson(e.requestHeaders))
        j.put("requestBody", e.requestBody)
        e.requestBodyBase64?.let { j.put("requestBodyBase64", it) }
        e.requestBodyDecoded?.let { j.put("requestBodyDecoded", it) }
        e.statusCode?.let { j.put("statusCode", it) }
        e.responseHeaders?.let { j.put("responseHeaders", headersJson(it)) }
        e.responseBody?.let { j.put("responseBody", it) }
        e.responseBodyBase64?.let { j.put("responseBodyBase64", it) }
        e.responseBodyDecoded?.let { j.put("responseBodyDecoded", it) }
        e.error?.let { j.put("error", it) }
        j.put("durationMs", e.durationMs)
        j.put("mocked", e.mocked)
        e.matchedRuleId?.let { j.put("matchedRuleId", it) }
        // AN-10 D4a：采集方式（可选；沿用 omitempty 风格，null 不出现在 JSON 里）。
        e.adapter?.let { j.put("adapter", it) }
        return j
    }

    private fun headersJson(headers: Map<String, List<String>>): JSONObject {
        val j = JSONObject()
        for ((key, values) in headers) {
            val arr = JSONArray()
            for (v in values) arr.put(v)
            j.put(key, arr)
        }
        return j
    }

    // MARK: - 辅助

    /// RFC3339 时间戳（对齐 iOS ISO8601 编码；minSdk 21 无 java.time，用 SimpleDateFormat）。
    internal fun rfc3339(date: Date = Date()): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(date)
    }

    private fun makeClient(serverURL: String): ConnectionClient {
        clientFactory?.let { return it(serverURL) }
        return ConnectionClient.create(serverURL)
    }

    /// 输出一条 SDK 日志：先经注入的 logHandler，再落 SDKLogger。
    private fun log(message: String, category: String = "capture") {
        val handler = synchronized(lock) { logHandler }
        handler?.invoke(message)
        SDKLogger.info(message, category)
    }

    /// 默认攒批调度器：单线程 IO dispatcher（与连接层同构）。
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun defaultScheduler(): CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)
}
