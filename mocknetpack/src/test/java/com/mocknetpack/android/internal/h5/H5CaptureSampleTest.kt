package com.mocknetpack.android.internal.h5

import com.mocknetpack.android.internal.capture.TrafficCaptureController
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class H5CaptureSampleTest {
    @Test
    fun keepsEncodedPathAndPageReadableBody() {
        val window = H5RuleController.Window(1, System.currentTimeMillis() - 1_000)
        val message = sample(window)
            .put("requestHeaders", JSONObject().put("x-example", "Original"))
            .put("responseBody", JSONObject().put("kind", "text").put("value", """{"n": 9007199254740993}"""))
            .put("status", 403)
        val entry = checkNotNull(H5CaptureSample.fromPage(message, window))
        assertEquals("/a%2Fb", entry.path)
        assertEquals("x=a%2Bb", entry.query)
        assertEquals("Original", entry.requestHeaders["x-example"]?.single())
        assertEquals("""{"n": 9007199254740993}""", entry.responseBody)
        assertEquals(403, entry.statusCode)
        assertEquals("httpResponse", entry.terminalStage)
        assertEquals("POST https://example.test/a%2Fb", H5Endpoint.parse(entry.url)?.key("post"))
    }

    @Test
    fun keepsDiagnosticAndUnknownStatusSeparateAndDropsEarlierSamples() {
        val window = H5RuleController.Window(2, System.currentTimeMillis() - 1_000)
        val message = sample(window)
            .put("source", "android-webview-resource")
            .put("imageResource", true)
            .put("result", "h5ResourceObserved")
            .put("status", 0)
        val entry = checkNotNull(H5CaptureSample.fromPage(message, window))
        assertNull(entry.statusCode)
        assertEquals("h5ResourceObserved", entry.terminalStage)
        assertEquals("image", entry.responseStaticKind)
        assertNull(H5CaptureSample.fromPage(message.put("timestamp", window.startedAt - 1), window))
    }

    @Test
    fun exposesCancellationAndRejectsLegacyPlatformProjection() {
        val window = H5RuleController.Window(3, System.currentTimeMillis() - 1_000)
        val entry = checkNotNull(H5CaptureSample.fromPage(sample(window).put("result", "abort"), window))
        val json = TrafficCaptureController.entryJson(entry)
        assertTrue(json.getBoolean("clientAbort"))
        assertEquals("cancelled", json.getString("clientAbortReason"))
        assertFalse(json.has("statusCode"))

        val adapters = JSONArray(H5CaptureSample.adapters)
        assertFalse(H5RuleController.supportsAndroid(JSONObject().put("h5TrafficAdapters", adapters)))
        assertFalse(H5RuleController.supportsAndroid(JSONObject().put("scope", "h5")))
        assertTrue(H5RuleController.supportsAndroid(JSONObject().put("scope", "h5").put("h5TrafficAdapters", adapters)))
    }

    private fun sample(window: H5RuleController.Window) = JSONObject()
        .put("source", H5CaptureSample.FETCH)
        .put("method", "POST")
        .put("url", "https://example.test:443/a%2Fb?x=a%2Bb")
        .put("timestamp", window.startedAt + 1)
        .put("durationMs", 15)
        .put("result", "http")
}
