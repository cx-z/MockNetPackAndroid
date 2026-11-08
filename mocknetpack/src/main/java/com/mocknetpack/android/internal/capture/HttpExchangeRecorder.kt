package com.mocknetpack.android.internal.capture

import com.mocknetpack.android.MockNetPack
import com.mocknetpack.android.TrafficEntry
import com.mocknetpack.android.internal.util.SDKBase64

/**
 * 统一的 HTTP 交换快照（AN-10 M1）。
 *
 * 刻意**不含任何 OkHttp / HttpURLConnection 类型**：各接入面（OkHttp 拦截器、
 * HttpURLConnection 包装器、业务自报 `reportHttp`）把各自栈里拿到的数据组装成
 * 这个纯数据对象，再交给 [HttpExchangeRecorder] 落成契约 `TrafficEntry`。
 * 这样 body 三态、1MB 截断、base64、codec 槽语义只有一份实现，新增适配器
 * 不需要复制一遍 `buildEntry/serveEntry`。
 *
 * 字段语义与历史实现逐字对齐（M1 只搬不改）：
 * - `requestBodySample` / `responseBodySample` 是"已采样字节"，Recorder 按
 *   [MockNetPack.BODY_LIMIT_BYTES] 截断；`*FullSize` 是原始总大小，供
 *   `[binary N bytes]` / `[truncated N bytes]` 占位使用。
 * - `requestBodyUnavailable`：下游替换成一次性 / 流式请求体，无法安全重读，
 *   展示 `[body unavailable]`，绝不二次消费。
 * - `mockResponseBodyText` / `mockResponseBodyBase64` / `mockResponseDecodeBytes`
 *   只对 mocked 条目生效：命中回包的展示体与回放字节来自规则（或 media 占位），
 *   不是本次请求的采样字节，不能与真实响应字段混用。
 * - `timestamp` 为空时由 Recorder 取当前时间；Mock 命中路径在 sleep 之前
 *   捕获时间戳后传入，保持"请求发起时间"语义。
 * - `path` / `query` 为空时从 `url` 解析（HttpURLConnection / 业务自报不保证
 *   单独给出路径字段）。
 */
internal data class CapturedExchange(
    val method: String,
    val url: String,
    val requestHeaders: Map<String, List<String>> = emptyMap(),
    val requestBodySample: ByteArray? = null,
    val requestBodyFullSize: Long = 0,
    val requestBodyUnavailable: Boolean = false,
    /** 请求体解码用的 contentType（沿用请求头里的 Content-Type，对齐历史实现）。 */
    val requestBodyContentType: String? = null,
    val statusCode: Int? = null,
    val responseHeaders: Map<String, List<String>>? = null,
    val responseBodySample: ByteArray? = null,
    val responseBodyFullSize: Long = 0,
    val responseBodyContentType: String? = null,
    val mockResponseBodyText: String? = null,
    val mockResponseBodyBase64: String? = null,
    val mockResponseDecodeBytes: ByteArray? = null,
    val error: String? = null,
    val durationMs: Int,
    /** 采集方式：`okhttp` / `httpurlconnection` / 业务自填（如 `cronet`）。 */
    val adapter: String,
    val mocked: Boolean = false,
    val matchedRuleId: String? = null,
    val timestamp: String? = null,
    val path: String? = null,
    val query: String? = null,
)

/**
 * HTTP 交换记录器（AN-10 M1）：把 [CapturedExchange] 组装成契约 `TrafficEntry`
 * 并交给既有的 [TrafficCaptureController.record] 管线（会话开关、启动暂存、
 * 攒批、失败即弃全部复用，不新增第二套上传路径）。
 *
 * 职责边界：只做"组装与规范化"，不做网络 IO、不持锁——所有额度与代次校验
 * 仍在 `TrafficCaptureController` 内完成。
 */
internal object HttpExchangeRecorder {

    /** OkHttp 应用拦截器来源。 */
    const val ADAPTER_OKHTTP = "okhttp"

    /** HttpURLConnection 包装器来源。 */
    const val ADAPTER_HTTP_URL_CONNECTION = "httpurlconnection"

    /** 业务自报（Cronet / 自研栈 / 封装层）来源。 */
    const val ADAPTER_BUSINESS = "business"

    fun record(exchange: CapturedExchange, generation: Long? = null) {
        TrafficCaptureController.record(toEntry(exchange), generation)
    }

    /** 纯组装：不触碰管线状态，便于单测直接断言字段映射。 */
    internal fun toEntry(exchange: CapturedExchange): TrafficEntry {
        val requestParts = requestBodyParts(
            sample = exchange.requestBodySample,
            fullSize = exchange.requestBodyFullSize,
            unavailable = exchange.requestBodyUnavailable,
        )
        // mocked 条目的响应体来自规则（或 media 展示占位），不是本次采样字节。
        val responseParts = if (exchange.mocked) {
            null
        } else {
            exchange.responseBodySample?.let { bodyParts(it, exchange.responseBodyFullSize) }
        }
        val responseBodyText = if (exchange.mocked) {
            exchange.mockResponseBodyText
        } else {
            responseParts?.first
        }
        val responseBodyBase64 = if (exchange.mocked) {
            exchange.mockResponseBodyBase64
        } else {
            responseParts?.second
        }
        val responseDecodeBytes = if (exchange.mocked) {
            exchange.mockResponseDecodeBytes
        } else {
            exchange.responseBodySample
        }

        return TrafficEntry(
            timestamp = exchange.timestamp ?: TrafficCaptureController.rfc3339(),
            method = exchange.method,
            url = exchange.url,
            path = exchange.path ?: rawPath(exchange.url),
            query = exchange.query ?: rawQuery(exchange.url),
            requestHeaders = exchange.requestHeaders,
            requestBody = requestParts.first,
            requestBodyBase64 = requestParts.second,
            requestBodyDecoded = decodedBody(
                exchange.requestBodySample,
                exchange.requestBodyContentType,
                isRequest = true,
            ),
            statusCode = exchange.statusCode,
            responseHeaders = exchange.responseHeaders,
            responseBody = responseBodyText,
            responseBodyBase64 = responseBodyBase64,
            responseBodyDecoded = decodedBody(
                responseDecodeBytes,
                exchange.responseBodyContentType,
                isRequest = false,
            ),
            error = exchange.error,
            durationMs = exchange.durationMs,
            mocked = exchange.mocked,
            matchedRuleId = exchange.matchedRuleId,
            adapter = exchange.adapter,
        )
    }

    // MARK: - body 治理（对齐 iOS MockNetPackURLProtocol.bodyParts / decodedBody）

    /// 返回 (展示文本, base64)。二进制时展示为占位文本、base64 携带原始字节
    /// （样本 1MB）；文本时 base64 为 nil。fullSize 用于占位 N（截断时总大小）。
    internal fun bodyParts(data: ByteArray?, fullSize: Long): Pair<String, String?> {
        if (data == null || data.isEmpty()) return "" to null
        val limit = MockNetPack.BODY_LIMIT_BYTES.toInt()
        val sample = if (data.size > limit) data.copyOfRange(0, limit) else data
        val text = TrafficCaptureController.tryDecodeUtf8(sample)
        return if (text != null) {
            text to null
        } else {
            "[binary $fullSize bytes]" to SDKBase64.encode(sample)
        }
    }

    /// 用 App 注入的解码器把二进制 body 解成可读 UTF-8 文本（仅展示）。
    /// isRequest=true 走请求体专用解码槽位（业务请求体/响应体编码不对称时）。
    internal fun decodedBody(data: ByteArray?, contentType: String?, isRequest: Boolean): String? {
        if (data == null || data.isEmpty()) return null
        val decoder = if (isRequest) TrafficCaptureController.requestBodyDecoder
        else TrafficCaptureController.bodyDecoder
        if (decoder == null) return null
        val limit = MockNetPack.BODY_LIMIT_BYTES.toInt()
        val sample = if (data.size > limit) data.copyOfRange(0, limit) else data
        return decoder(sample, contentType)
    }

    /// 请求体展示：unavailable → `[body unavailable]`；文本超限 → 加
    /// `[truncated N bytes]` 前缀；二进制 / 常规走 [bodyParts]（占位 + base64 样本）。
    internal fun requestBodyParts(sample: ByteArray?, fullSize: Long, unavailable: Boolean): Pair<String, String?> {
        if (unavailable) return "[body unavailable]" to null
        val parts = bodyParts(sample, fullSize)
        val truncatedText = fullSize > MockNetPack.BODY_LIMIT_BYTES &&
            parts.second == null && parts.first.isNotEmpty()
        return if (truncatedText) {
            "[truncated $fullSize bytes] ${parts.first}" to null
        } else {
            parts
        }
    }

    /// 从完整 URL 取编码路径（HttpURLConnection / 业务自报没有单独的 path 字段）。
    private fun rawPath(url: String): String = try {
        java.net.URI(url).rawPath ?: ""
    } catch (_: Exception) {
        ""
    }

    /// 从完整 URL 取原始 query（不含 `?`，保持原始顺序）。
    private fun rawQuery(url: String): String = try {
        java.net.URI(url).rawQuery ?: ""
    } catch (_: Exception) {
        ""
    }
}
