package com.mocknetpack.android.internal.capture

import com.mocknetpack.android.TrafficEntry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/// HttpExchangeRecorder：组装与规范化的字段映射验证。
/// 覆盖文本/二进制三态、1MB 截断、`[body unavailable]` 前缀、mock 条目覆写、
/// 以及 `adapter` 在契约 JSON 里的出现/缺省。
class HttpExchangeRecorderTest {

    @Test
    fun `toEntry maps text exchange with adapter and derives path query`() {
        val entry = HttpExchangeRecorder.toEntry(
            CapturedExchange(
                method = "POST",
                url = "https://example.com/api/v1/orders?id=7&x=1",
                requestHeaders = mapOf("Content-Type" to listOf("application/json")),
                requestBodySample = """{"a":1}""".toByteArray(),
                requestBodyFullSize = 7,
                requestBodyContentType = "application/json",
                statusCode = 201,
                responseHeaders = mapOf("Content-Type" to listOf("application/json")),
                responseBodySample = """{"ok":true}""".toByteArray(),
                responseBodyFullSize = 11,
                responseBodyContentType = "application/json",
                durationMs = 42,
                adapter = HttpExchangeRecorder.ADAPTER_OKHTTP,
            ),
        )
        // path / query 未显式给出时从 URL 解析（HttpURLConnection / 业务自报依赖该行为）。
        assertEquals("/api/v1/orders", entry.path)
        assertEquals("id=7&x=1", entry.query)
        assertEquals("""{"a":1}""", entry.requestBody)
        assertEquals("""{"ok":true}""", entry.responseBody)
        assertEquals(201, entry.statusCode)
        assertEquals(42, entry.durationMs)
        assertEquals(HttpExchangeRecorder.ADAPTER_OKHTTP, entry.adapter)
        assertFalse(entry.mocked)
    }

    @Test
    fun `toEntry marks binary response with placeholder and 1MB base64 sample`() {
        val payload = ByteArray(2 * 1024 * 1024) { 0xFF.toByte() }
        val entry = HttpExchangeRecorder.toEntry(
            CapturedExchange(
                method = "GET",
                url = "https://example.com/blob",
                responseBodySample = payload,
                responseBodyFullSize = payload.size.toLong(),
                durationMs = 5,
                adapter = HttpExchangeRecorder.ADAPTER_HTTP_URL_CONNECTION,
            ),
        )
        assertEquals("[binary ${payload.size} bytes]", entry.responseBody)
        val decoded = java.util.Base64.getDecoder().decode(entry.responseBodyBase64)
        assertEquals(1024 * 1024, decoded.size)
    }

    @Test
    fun `toEntry prefixes truncated text request body with original size`() {
        val bigText = "x".repeat(1_050_000)
        val entry = HttpExchangeRecorder.toEntry(
            CapturedExchange(
                method = "POST",
                url = "https://example.com/upload",
                requestBodySample = bigText.toByteArray(),
                requestBodyFullSize = bigText.length.toLong(),
                durationMs = 30,
                adapter = HttpExchangeRecorder.ADAPTER_OKHTTP,
            ),
        )
        assertTrue(entry.requestBody.startsWith("[truncated 1050000 bytes] "))
        assertNull(entry.requestBodyBase64)
        assertEquals(1_048_576, entry.requestBody.length - "[truncated 1050000 bytes] ".length)
    }

    @Test
    fun `toEntry marks unavailable request body without touching bytes`() {
        val entry = HttpExchangeRecorder.toEntry(
            CapturedExchange(
                method = "POST",
                url = "https://example.com/stream",
                requestBodyUnavailable = true,
                durationMs = 1,
                adapter = HttpExchangeRecorder.ADAPTER_OKHTTP,
            ),
        )
        assertEquals("[body unavailable]", entry.requestBody)
        assertNull(entry.requestBodyBase64)
    }

    @Test
    fun `toEntry uses mock response display for mocked entry`() {
        val ruleBody = """{"mocked":true}"""
        val entry = HttpExchangeRecorder.toEntry(
            CapturedExchange(
                method = "GET",
                url = "https://example.com/api/v1/business",
                statusCode = 200,
                responseHeaders = mapOf("Content-Type" to listOf("application/json")),
                mockResponseBodyText = ruleBody,
                mockResponseBodyBase64 = null,
                mockResponseDecodeBytes = ruleBody.toByteArray(),
                responseBodyContentType = "application/json",
                durationMs = 3,
                adapter = HttpExchangeRecorder.ADAPTER_OKHTTP,
                mocked = true,
                matchedRuleId = "r1",
                timestamp = "2026-11-09T00:00:00.000Z",
            ),
        )
        assertEquals(ruleBody, entry.responseBody)
        assertTrue(entry.mocked)
        assertEquals("r1", entry.matchedRuleId)
        assertEquals("2026-11-09T00:00:00.000Z", entry.timestamp)
        // mocked 条目不得把采样字节当成真实响应体（此处无采样，base64 必须为空）。
        assertNull(entry.responseBodyBase64)
    }

    @Test
    fun `entryJson includes adapter when set and omits it when null`() {
        val withAdapter = TrafficEntry(
            timestamp = "2026-11-09T00:00:00.000Z",
            method = "GET",
            url = "https://example.com/x",
            path = "/x",
            query = "",
            requestHeaders = emptyMap(),
            requestBody = "",
            durationMs = 1,
            adapter = "cronet",
        )
        val json = TrafficCaptureController.entryJson(withAdapter)
        assertEquals("cronet", json.optString("adapter"))

        // 老客户端 / 老路径：adapter 为 null 时字段不出现（对齐 omitempty 风格）。
        val legacy = withAdapter.copy(adapter = null)
        val legacyJson: JSONObject = TrafficCaptureController.entryJson(legacy)
        assertFalse(legacyJson.has("adapter"))
    }
    @Test
    fun `custom label respects byte length and built in identifiers`() {
        assertEquals("cronet", HttpExchangeRecorder.sourceLabel(" cronet ", "business"))
        assertEquals("business", HttpExchangeRecorder.sourceLabel("WKWebView-fetch", "business"))
        assertEquals("business", HttpExchangeRecorder.sourceLabel("okhttp", "business"))
        assertEquals("business", HttpExchangeRecorder.sourceLabel("界".repeat(11), "business"))
    }
}
