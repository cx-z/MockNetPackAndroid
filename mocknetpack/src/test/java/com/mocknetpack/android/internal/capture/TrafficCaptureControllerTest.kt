package com.mocknetpack.android.internal.capture

import com.mocknetpack.android.BinaryCompression
import com.mocknetpack.android.TrafficEntry
import com.mocknetpack.android.internal.connection.ConnectionClient
import java.io.ByteArrayInputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.GZIPInputStream
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/// TrafficCaptureController 攒批/上传/会话切换/codec 槽验证（MockWebServer 桩）。
@OptIn(ExperimentalCoroutinesApi::class)
class TrafficCaptureControllerTest {

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
            if (req.path!!.endsWith("/traffic")) {
                MockResponse().setResponseCode(202).setBody("""{"accepted":true,"count":1}""")
            } else {
                MockResponse().setResponseCode(404)
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

    private fun waitUntil(timeoutMs: Long = 3000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(10)
        }
        fail("condition not met within ${timeoutMs}ms")
    }

    private fun countRequests(suffix: String): Int = recordedRequests.count { it.path?.endsWith(suffix) == true }

    private fun lastRequest(suffix: String): RecordedRequest? =
        recordedRequests.lastOrNull { it.path?.endsWith(suffix) == true }

    private fun makeEntry(
        method: String = "GET",
        url: String = "https://api.example.com/v1/users?page=2",
        responseBody: String? = """{"ok":true}""",
    ): TrafficEntry {
        return TrafficEntry(
            timestamp = "2026-10-08T00:00:00.000Z",
            method = method,
            url = url,
            path = "/v1/users",
            query = "page=2",
            requestHeaders = mapOf("Content-Type" to listOf("application/json")),
            requestBody = """{"q":"1"}""",
            statusCode = 200,
            responseHeaders = mapOf("Content-Type" to listOf("application/json")),
            responseBody = responseBody,
            error = null,
            durationMs = 12,
            mocked = false,
            matchedRuleId = null,
        )
    }

    // MARK: - 攒批阈值

    @Test
    fun `record flushes at 200 entries with upload payload fields`() {
        TrafficCaptureController.start(baseURL(), appID, did)
        TrafficCaptureController.updateSession(capturing = true, sessionID = "sess-1")
        for (i in 0 until 200) TrafficCaptureController.record(makeEntry())

        waitUntil { countRequests("/traffic") >= 1 }
        val body = JSONObject(lastRequest("/traffic")!!.body.readUtf8())
        assertEquals(appID, body.optString("app"))
        assertEquals(did, body.optString("did"))
        assertEquals("sess-1", body.optString("sessionId"))
        val entries = body.optJSONArray("entries")
        assertEquals(200, entries.length())

        // 单条字段契约（camelCase、mocked=false）。
        val first = entries.getJSONObject(0)
        assertEquals("GET", first.optString("method"))
        assertEquals("https://api.example.com/v1/users?page=2", first.optString("url"))
        assertEquals("/v1/users", first.optString("path"))
        assertEquals("page=2", first.optString("query"))
        assertEquals(200, first.optInt("statusCode"))
        assertEquals("""{"ok":true}""", first.optString("responseBody"))
        assertEquals(false, first.optBoolean("mocked"))
        assertTrue(first.has("timestamp"))
        assertTrue(first.has("requestHeaders"))
        assertTrue(first.has("durationMs"))
    }

    @Test
    fun `record flushes at 8MB bytes threshold`() {
        TrafficCaptureController.start(baseURL(), appID, did)
        TrafficCaptureController.updateSession(capturing = true, sessionID = "sess-1")
        // 单条超大 responseBody → pendingBytes >= 8MB 触发阈值。
        TrafficCaptureController.record(makeEntry(responseBody = "x".repeat(8 * 1024 * 1024)))

        waitUntil { countRequests("/traffic") >= 1 }
        val body = JSONObject(lastRequest("/traffic")!!.body.readUtf8())
        assertEquals(1, body.optJSONArray("entries").length())
    }

    @Test
    fun `timed flush uploads after interval`() {
        TrafficCaptureController.start(baseURL(), appID, did)
        TrafficCaptureController.updateSession(capturing = true, sessionID = "sess-1")
        TrafficCaptureController.record(makeEntry())

        // flushIntervalMs=60 → 定时触发上传。
        waitUntil { countRequests("/traffic") >= 1 }
    }

    // MARK: - 会话切换清批 / 失败丢弃

    @Test
    fun `session switch clears pending batch`() {
        TrafficCaptureController.start(baseURL(), appID, did)
        TrafficCaptureController.updateSession(capturing = true, sessionID = "sess-1")
        for (i in 0 until 5) TrafficCaptureController.record(makeEntry())

        // 会话结束 → 清空待上传批次（临时记录随会话结束丢弃）。
        TrafficCaptureController.updateSession(capturing = false, sessionID = null)
        TrafficCaptureController.flush()
        Thread.sleep(150)
        assertEquals(0, countRequests("/traffic"))
    }

    @Test
    fun `upload failure drops batch without retry`() {
        var failUpload = true
        handler = { req ->
            if (req.path!!.endsWith("/traffic")) {
                if (failUpload) {
                    failUpload = false
                    MockResponse().setResponseCode(500).setBody("""{"error":"boom"}""")
                } else {
                    MockResponse().setResponseCode(202).setBody("""{"accepted":true,"count":1}""")
                }
            } else {
                MockResponse().setResponseCode(404)
            }
        }
        TrafficCaptureController.start(baseURL(), appID, did)
        TrafficCaptureController.updateSession(capturing = true, sessionID = "sess-1")
        TrafficCaptureController.record(makeEntry())
        TrafficCaptureController.flush()

        // 失败批次直接丢弃（不重传、不本地缓存）；批次已清空。
        waitUntil { countRequests("/traffic") >= 1 }
        val frozen = countRequests("/traffic")
        Thread.sleep(200)
        assertEquals(frozen, countRequests("/traffic"))
    }

    // MARK: - codec 槽

    @Test
    fun `registerBinaryCodec drives decoder and encoder`() {
        TrafficCaptureController.registerBinaryCodec(
            contentTypeKey = "xcp",
            compression = BinaryCompression.GZIP,
            responseEncrypt = { data -> data },
            responseDecrypt = { data -> GZIPInputStream(ByteArrayInputStream(data)).readBytes() },
        )
        // encoder：编辑文本 → UTF-8 → gzip → 业务加密（恒等）→ gzip 密文。
        val encoded = TrafficCaptureController.bodyEncoder?.invoke("""{"a":1}""", "application/xcp")!!
        assertEquals(0x1f, encoded[0].toInt() and 0xff)
        assertEquals(0x8b, encoded[1].toInt() and 0xff)
        // decoder：密文 → 业务解密（恒等）→ UTF-8 → 明文。
        val text = TrafficCaptureController.bodyDecoder?.invoke(encoded, "application/xcp")
        assertEquals("""{"a":1}""", text)
    }

    @Test
    fun `decoder guards zlib error text`() {
        TrafficCaptureController.registerBinaryCodec(
            contentTypeKey = "xcp",
            compression = BinaryCompression.NONE,
            responseEncrypt = { it },
            responseDecrypt = { "Z_DATA_ERROR or Z_MEM_ERROR".toByteArray() },
        )
        val decoded = TrafficCaptureController.bodyDecoder?.invoke(ByteArray(4), "application/xcp")
        assertNull("zlib 错误描述不应被当作业务明文展示", decoded)
        assertTrue(TrafficCaptureController.isDecoderErrorText("Z_NEED_DICT"))
        assertTrue(!TrafficCaptureController.isDecoderErrorText("""{"ok":1}"""))
    }

    @Test
    fun `request body decoder uses requestDecrypt slot only when registered`() {
        // 未注册 requestDecrypt → 请求体不解码（回退占位）。
        TrafficCaptureController.registerBinaryCodec(
            contentTypeKey = "xcp",
            compression = BinaryCompression.NONE,
            responseEncrypt = { it },
            responseDecrypt = { it },
        )
        assertNull(TrafficCaptureController.requestBodyDecoder)

        // 注册 requestDecrypt → 请求体专用解码生效。
        TrafficCaptureController.resetBinaryCodecs()
        TrafficCaptureController.registerBinaryCodec(
            contentTypeKey = "xcp",
            compression = BinaryCompression.NONE,
            responseEncrypt = { it },
            responseDecrypt = { it },
            requestDecrypt = { data -> "req:${String(data)}".toByteArray() },
        )
        val decoded = TrafficCaptureController.requestBodyDecoder?.invoke("hello".toByteArray(), "application/xcp")
        assertEquals("req:hello", decoded)
    }
}
