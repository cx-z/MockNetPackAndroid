package com.mocknetpack.android.internal.capture

import com.mocknetpack.android.MockNetPack
import com.mocknetpack.android.internal.connection.ConnectionClient
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/// HttpURLConnection 包装器（AN-10 M2）回归：200 文本 / POST 请求体 / 302 跟随 /
/// 404 errorStream / gzip / 大响应（1MB 上限）。
/// 同一 MockWebServer 桩既是业务目标，也是 /traffic 上传端点（与 OkHttp 测试同构）。
@OptIn(ExperimentalCoroutinesApi::class)
class UrlConnectionCaptureTest {

    private lateinit var server: MockWebServer
    private val appID = "com.example.basedemo"
    private val did = "test-did-0001"
    private val recordedRequests = CopyOnWriteArrayList<RecordedRequest>()
    private lateinit var handler: (RecordedRequest) -> MockResponse

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        server = MockWebServer()
        server.start()
        TrafficCaptureController.stop()
        TrafficCaptureController.resetBinaryCodecs()
        TrafficCaptureController.flushIntervalMs = 60
        TrafficCaptureController.schedulerDispatcher = Dispatchers.Unconfined
        TrafficCaptureController.clientFactory = { ConnectionClient.create(it) }
        server.setDispatcher(object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                recordedRequests.add(request)
                return handler(request)
            }
        })
        handler = { req ->
            when {
                pathOf(req).endsWith("/traffic") ->
                    MockResponse().setResponseCode(202).setBody("""{"accepted":true,"count":1}""")
                else -> MockResponse().setResponseCode(404)
            }
        }
    }

    @After
    fun tearDown() {
        TrafficCaptureController.stop()
        TrafficCaptureController.resetBinaryCodecs()
        server.shutdown()
        Dispatchers.resetMain()
    }

    private fun baseURL(): String = server.url("/api/v1").toString()

    private fun startCapturing() {
        TrafficCaptureController.start(baseURL(), appID, did)
        TrafficCaptureController.updateSession(capturing = true, sessionID = "sess-1")
    }

    private fun waitUntil(timeoutMs: Long = 3000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(10)
        }
        fail("condition not met within ${timeoutMs}ms")
    }

    /// 只取编码路径（不含 query）：`RecordedRequest.path` 带 `?query`，直接
    /// endsWith 匹配不到带参数的接口。
    private fun pathOf(req: RecordedRequest): String = req.requestUrl?.encodedPath() ?: req.path ?: ""

    private fun countRequests(suffix: String): Int = recordedRequests.count { it.path?.endsWith(suffix) == true }

    private fun lastRequest(suffix: String): RecordedRequest? =
        recordedRequests.lastOrNull { it.path?.endsWith(suffix) == true }

    /// 打开一条被包装的连接；业务侧只在 wrap 之后设置属性 / 写 body。
    /// 直接调内部实现而不是 `MockNetPack.wrap`：`MockNetPack` 的 <clinit> 会触碰
    /// Looper（JVM 单测未 mock），公开签名由宿主 Dokimo 的编译构建验证。
    private fun open(path: String): HttpURLConnection =
        UrlConnectionCapture.wrap(URL(server.url(path).toString()).openConnection() as HttpURLConnection)

    /// 上传一条记录并返回 entry（等待 flush 完成）。
    private fun uploadedEntry(): JSONObject {
        TrafficCaptureController.flush()
        waitUntil { countRequests("/traffic") >= 1 }
        val body = JSONObject(lastRequest("/traffic")!!.body.readUtf8())
        return body.optJSONArray("entries")!!.getJSONObject(0)
    }

    @Test
    fun `captures 200 text response with adapter httpurlconnection`() {
        handler = { req ->
            when {
                pathOf(req).endsWith("/traffic") ->
                    MockResponse().setResponseCode(202).setBody("""{"accepted":true,"count":1}""")
                pathOf(req).endsWith("/api/v1/business") ->
                    MockResponse().setResponseCode(200)
                        .setHeader("Content-Type", "application/json")
                        .setBody("""{"hello":"world"}""")
                else -> MockResponse().setResponseCode(404)
            }
        }
        startCapturing()
        val conn = open("/api/v1/business?id=7")
        try {
            assertEquals(200, conn.responseCode)
            assertEquals("""{"hello":"world"}""", conn.inputStream.bufferedReader().readText())
        } finally {
            conn.disconnect()
        }

        val entry = uploadedEntry()
        assertEquals("GET", entry.optString("method"))
        assertEquals("/api/v1/business", entry.optString("path"))
        assertEquals("id=7", entry.optString("query"))
        assertEquals(200, entry.optInt("statusCode"))
        assertEquals("""{"hello":"world"}""", entry.optString("responseBody"))
        assertEquals("httpurlconnection", entry.optString("adapter"))
    }

    @Test
    fun `captures request body written through output stream`() {
        handler = { req ->
            when {
                pathOf(req).endsWith("/traffic") ->
                    MockResponse().setResponseCode(202).setBody("""{"accepted":true,"count":1}""")
                pathOf(req).endsWith("/api/v1/upload") ->
                    MockResponse().setResponseCode(201)
                        .setHeader("Content-Type", "text/plain")
                        .setBody("ok")
                else -> MockResponse().setResponseCode(404)
            }
        }
        startCapturing()
        val payload = """{"page":1}"""
        val conn = open("/api/v1/upload")
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(payload.toByteArray()) }
            assertEquals(201, conn.responseCode)
            assertEquals("ok", conn.inputStream.bufferedReader().readText())
        } finally {
            conn.disconnect()
        }

        val entry = uploadedEntry()
        assertEquals("POST", entry.optString("method"))
        assertEquals(payload, entry.optString("requestBody"))
        assertEquals("application/json", entry.getJSONObject("requestHeaders").getJSONArray("Content-Type").getString(0))
    }

    @Test
    fun `follows 302 redirect and records the final response`() {
        handler = { req ->
            when {
                pathOf(req).endsWith("/traffic") ->
                    MockResponse().setResponseCode(202).setBody("""{"accepted":true,"count":1}""")
                pathOf(req).endsWith("/api/v1/redirect") ->
                    MockResponse().setResponseCode(302).setHeader("Location", server.url("/api/v1/final").toString())
                pathOf(req).endsWith("/api/v1/final") ->
                    MockResponse().setResponseCode(200).setBody("landed")
                else -> MockResponse().setResponseCode(404)
            }
        }
        startCapturing()
        val conn = open("/api/v1/redirect")
        try {
            // 默认跟随重定向：读到的是最终 200 的 body。
            assertEquals("landed", conn.inputStream.bufferedReader().readText())
        } finally {
            conn.disconnect()
        }

        val entry = uploadedEntry()
        assertEquals(200, entry.optInt("statusCode"))
        assertEquals("landed", entry.optString("responseBody"))
    }

    @Test
    fun `records 404 with status and error via error stream`() {
        handler = { req ->
            when {
                pathOf(req).endsWith("/traffic") ->
                    MockResponse().setResponseCode(202).setBody("""{"accepted":true,"count":1}""")
                pathOf(req).endsWith("/api/v1/missing") ->
                    MockResponse().setResponseCode(404).setBody("nope")
                else -> MockResponse().setResponseCode(404)
            }
        }
        startCapturing()
        val conn = open("/api/v1/missing")
        try {
            assertEquals(404, conn.responseCode)
            try {
                conn.inputStream
                fail("404 的 getInputStream 应当抛 IOException")
            } catch (_: java.io.IOException) {
                // 预期：4xx 走 errorStream。
            }
            conn.errorStream?.bufferedReader()?.readText()
        } finally {
            conn.disconnect()
        }

        val entry = uploadedEntry()
        assertEquals(404, entry.optInt("statusCode"))
        assertEquals("httpurlconnection", entry.optString("adapter"))
    }

    @Test
    fun `keeps gzip bytes intact in base64 sample`() {
        val plain = """{"compress":"yes"}"""
        val gzipped = java.io.ByteArrayOutputStream().also { out ->
            GZIPOutputStream(out).use { it.write(plain.toByteArray()) }
        }.toByteArray()
        handler = { req ->
            when {
                pathOf(req).endsWith("/traffic") ->
                    MockResponse().setResponseCode(202).setBody("""{"accepted":true,"count":1}""")
                pathOf(req).endsWith("/api/v1/gzip") ->
                    MockResponse().setResponseCode(200)
                        .setHeader("Content-Type", "application/json")
                        .setHeader("Content-Encoding", "gzip")
                        .setBody(okio.Buffer().write(gzipped))
                else -> MockResponse().setResponseCode(404)
            }
        }
        startCapturing()
        val conn = open("/api/v1/gzip")
        try {
            val bytes = conn.inputStream.readBytes()
            // 未包装时 HttpURLConnection 不会自动解 gzip，这里验证字节未被改写。
            assertEquals(gzipped.size, bytes.size)
        } finally {
            conn.disconnect()
        }

        val entry = uploadedEntry()
        assertEquals(200, entry.optInt("statusCode"))
        // 二进制样本带 base64，且长度与原始 gzip 字节一致（字节未被解压/改写）。
        val b64 = entry.optString("responseBodyBase64")
        assertTrue(b64.isNotEmpty())
        assertEquals(gzipped.size, java.util.Base64.getDecoder().decode(b64).size)
    }

    @Test
    fun `caps large response sample at 1MB and marks binary placeholder`() {
        val payload = ByteArray(2 * 1024 * 1024) { 0xFF.toByte() }
        handler = { req ->
            when {
                pathOf(req).endsWith("/traffic") ->
                    MockResponse().setResponseCode(202).setBody("""{"accepted":true,"count":1}""")
                pathOf(req).endsWith("/api/v1/blob") ->
                    MockResponse().setResponseCode(200)
                        .setHeader("Content-Type", "application/octet-stream")
                        .setBody(okio.Buffer().write(payload))
                else -> MockResponse().setResponseCode(404)
            }
        }
        startCapturing()
        val conn = open("/api/v1/blob")
        try {
            val bytes = conn.inputStream.readBytes()
            assertEquals(payload.size, bytes.size)
        } finally {
            conn.disconnect()
        }

        val entry = uploadedEntry()
        assertEquals("[binary ${payload.size} bytes]", entry.optString("responseBody"))
        val b64 = entry.optString("responseBodyBase64")
        assertNotNull(b64)
        assertEquals(MockNetPack.BODY_LIMIT_BYTES.toInt(), java.util.Base64.getDecoder().decode(b64).size)
    }
}
