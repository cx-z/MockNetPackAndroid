package com.mocknetpack.android.internal.capture

import com.mocknetpack.android.BinaryCompression
import com.mocknetpack.android.MockResponse as SdkMockResponse
import com.mocknetpack.android.MockRule
import com.mocknetpack.android.internal.connection.ConnectionClient
import com.mocknetpack.android.internal.mock.MockRuleController
import java.io.ByteArrayInputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/// MockNetPackInterceptor 拦截/转发/治理验证（同一 MockWebServer 桩：
/// 业务转发目标 + /traffic 上传端点）。
@OptIn(ExperimentalCoroutinesApi::class)
class MockNetPackInterceptorTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
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
        client = OkHttpClient.Builder().addInterceptor(MockNetPackInterceptor()).build()
        server.setDispatcher(object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                recordedRequests.add(request)
                return handler(request)
            }
        })
        handler = { req ->
            when {
                req.path!!.endsWith("/traffic") ->
                    MockResponse().setResponseCode(202).setBody("""{"accepted":true,"count":1}""")
                req.path!!.endsWith("/api/v1/business") ->
                    MockResponse().setResponseCode(200)
                        .setHeader("Content-Type", "application/json")
                        .setBody("""{"hello":"world"}""")
                else -> MockResponse().setResponseCode(404)
            }
        }
    }

    @After
    fun tearDown() {
        TrafficCaptureController.stop()
        TrafficCaptureController.resetBinaryCodecs()
        MockRuleController.reset()
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

    private fun businessGet(): String {
        val req = Request.Builder().url(server.url("/api/v1/business")).get().build()
        client.newCall(req).execute().use { resp -> return resp.body()?.string() ?: "" }
    }

    // MARK: - 拦截开关

    @Test
    fun `idle session does not capture or upload`() {
        // 未激活会话：isCapturing=false → 直接放行，不记录、不上传。
        assertEquals("""{"hello":"world"}""", businessGet())
        TrafficCaptureController.flush()
        Thread.sleep(150)
        assertEquals(0, countRequests("/traffic"))
    }

    @Test
    fun `skipHeader requests pass through uncaptured`() {
        TrafficCaptureController.start(baseURL(), appID, did)
        TrafficCaptureController.updateSession(capturing = true, sessionID = "sess-1")
        val req = Request.Builder()
            .url(server.url("/api/v1/business"))
            .header("X-MockNetPack-Skip", "1")
            .get()
            .build()
        client.newCall(req).execute().use { resp -> resp.body()?.string() }
        TrafficCaptureController.flush()
        Thread.sleep(150)
        assertEquals(0, countRequests("/traffic"))
    }

    // MARK: - 全链路采集与上传

    @Test
    fun `captures request response and uploads entry`() {
        TrafficCaptureController.start(baseURL(), appID, did)
        TrafficCaptureController.updateSession(capturing = true, sessionID = "sess-1")
        assertEquals("""{"hello":"world"}""", businessGet())

        TrafficCaptureController.flush()
        waitUntil { countRequests("/traffic") >= 1 }
        val body = JSONObject(lastRequest("/traffic")!!.body.readUtf8())
        val entry = body.optJSONArray("entries")!!.getJSONObject(0)
        assertEquals(appID, body.optString("app"))
        assertEquals(did, body.optString("did"))
        assertEquals("sess-1", body.optString("sessionId"))
        assertEquals("GET", entry.optString("method"))
        assertTrue(entry.optString("url").endsWith("/api/v1/business"))
        assertEquals("/api/v1/business", entry.optString("path"))
        assertEquals(200, entry.optInt("statusCode"))
        assertEquals("""{"hello":"world"}""", entry.optString("responseBody"))
        assertEquals(false, entry.optBoolean("mocked"))
        assertTrue(entry.optInt("durationMs") >= 0)
        assertTrue(entry.has("requestHeaders"))
    }

    @Test
    fun `post request body preserved on forward`() {
        TrafficCaptureController.start(baseURL(), appID, did)
        TrafficCaptureController.updateSession(capturing = true, sessionID = "sess-1")
        val body = "hello world body"
        val req = Request.Builder()
            .url(server.url("/api/v1/echo"))
            .post(RequestBody.create(MediaType.parse("text/plain"), body))
            .build()
        client.newCall(req).execute().use { resp -> resp.body()?.string() }

        // 转发目标收到完整请求体（快照重建不丢 body）。
        val echo = lastRequest("/api/v1/echo")!!
        assertEquals(body, echo.body.readUtf8())
        // 且转发请求不含 SDK skip 标记。
        assertTrue(echo.getHeader("X-MockNetPack-Skip") == null)
    }

    @Test
    fun `large binary response truncated with placeholder and base64`() {
        TrafficCaptureController.start(baseURL(), appID, did)
        TrafficCaptureController.updateSession(capturing = true, sessionID = "sess-1")
        // 2MB 全 0xFF（非法 UTF-8）二进制响应。
        handler = { req ->
            when {
                req.path!!.endsWith("/traffic") ->
                    MockResponse().setResponseCode(202).setBody("""{"accepted":true,"count":1}""")
                req.path!!.endsWith("/api/v1/business") ->
                    MockResponse().setResponseCode(200).setBody(Buffer().apply { write(ByteArray(2 * 1024 * 1024) { 0xFF.toByte() }) })
                else -> MockResponse().setResponseCode(404)
            }
        }
        val req = Request.Builder().url(server.url("/api/v1/business")).get().build()
        client.newCall(req).execute().use { resp -> resp.body()?.string() } // 消费触发 tee 完成

        TrafficCaptureController.flush()
        waitUntil { countRequests("/traffic") >= 1 }
        val entry = JSONObject(lastRequest("/traffic")!!.body.readUtf8()).optJSONArray("entries")!!.getJSONObject(0)
        assertEquals("[binary 2097152 bytes]", entry.optString("responseBody"))
        // base64 为 1MB 截断样本（上限治理）。
        val b64 = entry.optString("responseBodyBase64")
        assertEquals(1024 * 1024, java.util.Base64.getDecoder().decode(b64).size)
    }

    // MARK: - body 治理（直接单测）

    @Test
    fun `bodyParts marks utf8 text without base64`() {
        val interceptor = MockNetPackInterceptor()
        val bytes = """{"a":1}""".toByteArray()
        val (text, b64) = interceptor.bodyParts(bytes, bytes.size.toLong())
        assertEquals("""{"a":1}""", text)
        assertTrue(b64 == null)
    }

    @Test
    fun `bodyParts marks binary with placeholder and base64 sample`() {
        val interceptor = MockNetPackInterceptor()
        val bytes = ByteArray(2 * 1024 * 1024) { 0xFF.toByte() }
        val (text, b64) = interceptor.bodyParts(bytes, bytes.size.toLong())
        assertEquals("[binary 2097152 bytes]", text)
        assertTrue(b64 != null)
        // 样本截断 1MB。
        assertEquals(1024 * 1024, java.util.Base64.getDecoder().decode(b64).size)
    }

    // MARK: - Mock 命中回放（AN-3）

    @Test
    fun `mock hit serves synthetic response without network and uploads mocked entry`() {
        MockRuleController.reset()
        MockRuleController.applyForTesting(
            listOf(
                MockRule(
                    id = "r1",
                    method = "GET",
                    path = "/api/v1/business",
                    response = SdkMockResponse(
                        statusCode = 200,
                        headers = mapOf("Content-Type" to "application/json"),
                        body = """{"mocked":true,"hello":"world"}""",
                    ),
                    enabled = true,
                    effective = true,
                ),
            ),
            version = 1,
        )
        TrafficCaptureController.start(baseURL(), appID, did)
        TrafficCaptureController.updateSession(capturing = true, sessionID = "sess-1")

        val req = Request.Builder().url(server.url("/api/v1/business")).get().build()
        val body = client.newCall(req).execute().use { resp ->
            assertEquals(200, resp.code())
            resp.body()?.string()
        }
        assertEquals("""{"mocked":true,"hello":"world"}""", body)

        // 业务请求未到达真实服务器（serve 直接合成回包）。
        assertEquals(0, recordedRequests.count { it.path!!.endsWith("/api/v1/business") })

        TrafficCaptureController.flush()
        waitUntil { countRequests("/traffic") >= 1 }
        val entry = JSONObject(lastRequest("/traffic")!!.body.readUtf8()).optJSONArray("entries")!!.getJSONObject(0)
        assertEquals(true, entry.optBoolean("mocked"))
        assertEquals("r1", entry.optString("matchedRuleId"))
        assertEquals(200, entry.optInt("statusCode"))
        assertEquals("""{"mocked":true,"hello":"world"}""", entry.optString("responseBody"))
        // 请求体已捕获展示（快照）。
        assertTrue(entry.has("requestHeaders"))
    }

    @Test
    fun `mock bodyBase64 raw bytes replay wins over placeholder text`() {
        MockRuleController.reset()
        val b64 = java.util.Base64.getEncoder().encodeToString(byteArrayOf(0x01, 0x02, 0x03, 0x7f))
        MockRuleController.applyForTesting(
            listOf(
                MockRule(
                    id = "r-bin",
                    method = "GET",
                    path = "/api/v1/bin",
                    response = SdkMockResponse(
                        statusCode = 200,
                        headers = mapOf("Content-Type" to "application/octet-stream"),
                        body = "[binary 4 bytes]", // 未编辑二进制规则的占位文本
                        bodyBase64 = b64,
                    ),
                    enabled = true,
                    effective = true,
                ),
            ),
            version = 1,
        )
        TrafficCaptureController.start(baseURL(), appID, did)
        TrafficCaptureController.updateSession(capturing = true, sessionID = "sess-1")

        val req = Request.Builder().url(server.url("/api/v1/bin")).get().build()
        val bytes = client.newCall(req).execute().use { resp ->
            assertEquals(200, resp.code())
            resp.body()?.bytes()
        }
        // 回放为原始字节（占位文本不被编码回放）。
        assertTrue(bytes.contentEquals(byteArrayOf(0x01, 0x02, 0x03, 0x7f.toByte())))
    }

    @Test
    fun `mock replay applies bodyEncoder when registered`() {
        MockRuleController.reset()
        TrafficCaptureController.resetBinaryCodecs()
        TrafficCaptureController.registerBinaryCodec(
            contentTypeKey = "xcp",
            compression = BinaryCompression.GZIP,
            responseEncrypt = { it },
            responseDecrypt = { data -> GZIPInputStream(ByteArrayInputStream(data)).readBytes() },
        )
        MockRuleController.applyForTesting(
            listOf(
                MockRule(
                    id = "r-enc",
                    method = "GET",
                    path = "/api/v1/enc",
                    response = SdkMockResponse(
                        statusCode = 200,
                        headers = mapOf("Content-Type" to "application/xcp"),
                        body = """{"edited":true}""", // Web 编辑过的文本，无 bodyBase64
                    ),
                    enabled = true,
                    effective = true,
                ),
            ),
            version = 1,
        )
        TrafficCaptureController.start(baseURL(), appID, did)
        TrafficCaptureController.updateSession(capturing = true, sessionID = "sess-1")

        val req = Request.Builder().url(server.url("/api/v1/enc")).get().build()
        val bytes = client.newCall(req).execute().use { resp -> resp.body()?.bytes() }
        // 回放为 gzip 编码字节（编辑文本 → UTF-8 → gzip → 业务加密恒等）。
        assertEquals(0x1f, bytes!![0].toInt() and 0xff)
        assertEquals(0x8b, bytes[1].toInt() and 0xff)
    }

    @Test
    fun `mock replay filters proto-res headers and defaults content type`() {
        MockRuleController.reset()
        MockRuleController.applyForTesting(
            listOf(
                MockRule(
                    id = "r-hdr",
                    method = "GET",
                    path = "/api/v1/hdr",
                    response = SdkMockResponse(
                        statusCode = 201,
                        headers = mapOf(
                            "X-XC-Proto-Res" to "stale-key", // 旧密钥轮换 header 应被过滤
                            "X-Extra" to "keep-me",
                        ),
                        body = """{"ok":true}""",
                    ),
                    enabled = true,
                    effective = true,
                ),
            ),
            version = 1,
        )
        TrafficCaptureController.start(baseURL(), appID, did)
        TrafficCaptureController.updateSession(capturing = true, sessionID = "sess-1")

        val req = Request.Builder().url(server.url("/api/v1/hdr")).get().build()
        client.newCall(req).execute().use { resp ->
            assertEquals(201, resp.code())
            assertTrue("proto-res header 不得回放", resp.header("X-XC-Proto-Res") == null)
            assertEquals("keep-me", resp.header("X-Extra"))
            // Content-Type 缺省补 application/json。
            assertEquals("application/json", resp.header("Content-Type"))
            resp.body()?.string()
        }
    }
}
