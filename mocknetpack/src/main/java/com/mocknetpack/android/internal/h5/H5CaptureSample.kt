package com.mocknetpack.android.internal.h5

import com.mocknetpack.android.MockNetPack
import com.mocknetpack.android.TrafficEntry
import com.mocknetpack.android.internal.capture.TrafficCaptureController
import com.mocknetpack.android.internal.util.SDKBase64
import org.json.JSONObject
import java.util.Date

/** Validates the page's observations and maps them to the existing HTTP wire format. */
internal object H5CaptureSample {
    const val FETCH = "android-webview-fetch"
    const val XHR = "android-webview-xhr"
    const val NAVIGATION = "android-webview-navigation"
    private const val BEACON = "android-webview-beacon"
    private const val SSE = "android-webview-eventsource"
    private const val RESOURCE = "android-webview-resource"

    val adapters = setOf(FETCH, XHR, NAVIGATION, BEACON, SSE, RESOURCE)
    private val cspDirective = Regex("^[a-z-]{1,64}$")

    private data class Outcome(
        val stage: String? = null,
        val error: String? = null,
        val abort: String? = null,
    )

    fun fromPage(json: JSONObject, window: H5RuleController.Window): TrafficEntry? {
        val source = json.optString("source")
        val method = json.optString("method")
        val url = json.optString("url")
        val endpoint = H5Endpoint.parse(url) ?: return null
        val timestamp = json.optLong("timestamp")
        val duration = json.optInt("durationMs", -1)
        val status = json.optInt("status").takeIf { it in 100..599 }
        if (!H5Endpoint.validMethod(method) || timestamp < window.startedAt ||
            timestamp > System.currentTimeMillis() + 60_000 || duration !in 0..86_400_000
        ) return null

        val result = json.optString("result")
        val outcome = outcome(source, result, json.optString("blockedDirective")) ?: return null
        val http = result == "http"
        if (http && status == null) return null
        val request = body(json.optJSONObject("requestBody"))
        val response = if (http) body(json.optJSONObject("responseBody")) else null
        return TrafficEntry(
            timestamp = TrafficCaptureController.rfc3339(Date(timestamp)),
            method = method,
            url = url,
            path = endpoint.path,
            query = endpoint.query,
            requestHeaders = headers(json.optJSONObject("requestHeaders")).orEmpty(),
            requestBody = request?.first.orEmpty(),
            requestBodyBase64 = request?.second,
            statusCode = status.takeIf { http || source == RESOURCE || source == NAVIGATION },
            responseHeaders = if (http) headers(json.optJSONObject("responseHeaders")) else null,
            responseBody = response?.first,
            responseBodyBase64 = response?.second,
            durationMs = duration,
            error = outcome.error,
            terminalStage = outcome.stage,
            clientAbortReason = outcome.abort,
            responseStaticKind = "image".takeIf {
                source == RESOURCE && result != "h5ResourceError" && (status ?: 0) < 400 &&
                    json.optBoolean("imageResource")
            },
            adapter = source,
        )
    }

    private fun outcome(source: String, result: String, directive: String): Outcome? = when (source) {
        FETCH, XHR -> when (result) {
            "http" -> Outcome("httpResponse")
            "opaque" -> Outcome(error = "浏览器未暴露响应")
            "error" -> Outcome("transportError", "网络请求失败或浏览器限制访问")
            "timeout" -> Outcome("clientTimeout", "请求超时", "timeout")
            "abort" -> Outcome("clientCancelled", "请求已取消", "cancelled")
            else -> null
        }
        BEACON -> if (result == "h5BeaconQueued" || result == "h5BeaconRejected") Outcome(result) else null
        SSE -> if (result == "h5SSEOpen" || result == "h5SSEError") Outcome(result) else null
        NAVIGATION -> if (result == "h5NavigationFinished") Outcome(result) else null
        RESOURCE -> when (result) {
            "h5ResourceLoaded", "h5ResourceObserved" -> Outcome(result)
            "h5ResourceError" -> Outcome(result, if (cspDirective.matches(directive)) "CSP: $directive" else null)
            else -> null
        }
        else -> null
    }

    /** Only accept the bounded header representation sent by the page. */
    private fun headers(json: JSONObject?): Map<String, List<String>>? {
        if (json == null) return null
        var remaining = 8 * 1024
        val result = LinkedHashMap<String, List<String>>()
        for (name in json.keys()) {
            if (result.size == 64) break
            val value = json.opt(name) as? String ?: continue
            val bytes = name.toByteArray(Charsets.UTF_8).size + value.toByteArray(Charsets.UTF_8).size
            if (name.isEmpty() || bytes > remaining) continue
            result[name] = listOf(value)
            remaining -= bytes
        }
        return result
    }

    private fun body(json: JSONObject?): Pair<String, String?>? {
        if (json == null) return null
        val value = json.opt("value") as? String
        val limit = MockNetPack.BODY_LIMIT_BYTES.toInt()
        return when (json.optString("kind")) {
            "text", "derived" -> {
                if (value == null || value.toByteArray(Charsets.UTF_8).size > limit) {
                    "[正文未保留：超过 1 MiB]" to null
                } else if (json.optString("kind") == "derived") {
                    "[正文未保留：浏览器仅提供结构化值]\n$value" to null
                } else {
                    value to null
                }
            }
            "binary" -> {
                val data = value?.takeIf { it.length <= ((limit + 2) / 3) * 4 }?.let(SDKBase64::decode)
                if (data == null || data.size !in 1..limit) {
                    "[正文未保留：无效或超限的二进制样本]" to null
                } else {
                    "[binary ${maxOf(json.optLong("size"), data.size.toLong())} bytes]" to value
                }
            }
            "timedOut" -> "[正文未保留：采样超时]" to null
            "tooLarge" -> "[正文未保留：超过 HTTP 上传预算]" to null
            "unreadable" -> "[正文未保留：浏览器未提供可读样本]" to null
            else -> "[正文未保留：页面正文类型暂不支持采样]" to null
        }
    }
}
