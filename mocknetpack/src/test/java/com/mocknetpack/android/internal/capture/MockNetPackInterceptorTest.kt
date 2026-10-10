package com.mocknetpack.android.internal.capture

import com.mocknetpack.android.BinaryCompression
import com.mocknetpack.android.MockResponse as SdkMockResponse
import com.mocknetpack.android.MockRule
import com.mocknetpack.android.internal.connection.ConnectionClient
import com.mocknetpack.android.internal.mock.MockRuleController
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.Cache
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import okio.BufferedSink
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

    // MARK: - AN7 测试辅助（下游拦截器 / 计数 body）

    private fun headerInterceptor(name: String, value: String): Interceptor = Interceptor { chain ->
        chain.proceed(chain.request().newBuilder().header(name, value).build())
    }

    private fun downstreamRewriteInterceptor(): Interceptor = Interceptor { chain ->
        val rewritten = chain.request().newBuilder()
            .url(server.url("/api/v1/rewritten"))
            .header("X-Downstream", "1")
            .method("POST", RequestBody.create(MediaType.parse("application/json"), """{"downstream":true}"""))
            .build()
        chain.proceed(rewritten)
    }

    private fun replaceBodyInterceptor(body: RequestBody): Interceptor = Interceptor { chain ->
        chain.proceed(chain.request().newBuilder().method("POST", body).build())
    }

    private fun syntheticResponseInterceptor(): Interceptor = Interceptor { chain ->
        Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("Synthetic")
            .body(ResponseBody.create(MediaType.parse("application/json"), """{"synthetic":true}"""))
            .build()
    }

    /// 计数请求体：记录 writeTo 被调用次数（验证一次性 body 不被二次消费）。
    private class CountingBody(private val text: String) : RequestBody() {
        var writeCount = 0
            private set

        override fun contentType(): MediaType? = MediaType.parse("text/plain")

        override fun contentLength(): Long = text.toByteArray().size.toLong()

        override fun writeTo(sink: BufferedSink) {
            writeCount++
            sink.writeUtf8(text)
        }
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

    // MARK: - AN7 记录来源修正（MockWebServer 验证）

    /// 下游测试拦截器修改 URL / 请求头 / 请求体 → SDK 记录能反映这些修改。
    @Test
    fun `downstream interceptor url header body changes are recorded`() {
        TrafficCaptureController.start(baseURL(), appID, did)
        TrafficCaptureController.updateSession(capturing = true, sessionID = "sess-1")
        val rewriteClient = OkHttpClient.Builder()
            .addInterceptor(MockNetPackInterceptor())
            .addInterceptor(downstreamRewriteInterceptor())
            .build()
        val req = Request.Builder()
            .url(server.url("/api/v1/business"))
            .post(RequestBody.create(MediaType.parse("text/plain"), "orig body"))
            .build()
        rewriteClient.newCall(req).execute().use { resp -> resp.body()?.string() }

        // 服务端收到的是下游改写后的请求。
        assertEquals("/api/v1/rewritten", lastRequest("/api/v1/rewritten")?.path)
        assertEquals("""{"downstream":true}""", lastRequest("/api/v1/rewritten")!!.body.readUtf8())

        TrafficCaptureController.flush()
        waitUntil { countRequests("/traffic") >= 1 }
        val entry = JSONObject(lastRequest("/traffic")!!.body.readUtf8()).optJSONArray("entries")!!.getJSONObject(0)
        // URL 与头反映下游修改；body 被下游替换为一次性新实例 → 不可安全重读 → unavailable。
        // 头键保留原始大小写（headersToMultimap，对齐 iOS/Web 展示）。
        assertTrue(entry.optString("url").endsWith("/api/v1/rewritten"))
        assertEquals("1", entry.optJSONObject("requestHeaders")!!.optJSONArray("X-Downstream")!!.getString(0))
        assertEquals("[body unavailable]", entry.optString("requestBody"))
    }

    /// networkResponse.request 的 URL / 头 / 可重复读取 body 与服务端收到的请求对应。
    @Test
    fun `recorded request matches what server received`() {
        TrafficCaptureController.start(baseURL(), appID, did)
        TrafficCaptureController.updateSession(capturing = true, sessionID = "sess-1")
        val body = "hello world body"
        val req = Request.Builder()
            .url(server.url("/api/v1/business"))
            .post(RequestBody.create(MediaType.parse("text/plain"), body))
            .build()
        client.newCall(req).execute().use { resp -> resp.body()?.string() }

        val received = lastRequest("/api/v1/business")!!
        assertEquals(body, received.body.readUtf8())

        TrafficCaptureController.flush()
        waitUntil { countRequests("/traffic") >= 1 }
        val entry = JSONObject(lastRequest("/traffic")!!.body.readUtf8()).optJSONArray("entries")!!.getJSONObject(0)
        val reqHeaders = entry.optJSONObject("requestHeaders")!!
        // 记录内容与服务端实际收到的一致（含 Bridge 自动补头；键保留原始大小写）。
        assertEquals(body, entry.optString("requestBody"))
        assertEquals(received.getHeader("Content-Type"), reqHeaders.optJSONArray("Content-Type")!!.getString(0))
        assertEquals(received.getHeader("Host"), reqHeaders.optJSONArray("Host")!!.getString(0))
    }

    /// OkHttp 自动添加的 HTTP 请求头能够正确记录（Host / User-Agent / Content-Length）。
    @Test
    fun `okhttp auto added headers are recorded`() {
        TrafficCaptureController.start(baseURL(), appID, did)
        TrafficCaptureController.updateSession(capturing = true, sessionID = "sess-1")
        val req = Request.Builder()
            .url(server.url("/api/v1/business"))
            .post(RequestBody.create(MediaType.parse("text/plain"), "payload"))
            .build()
        client.newCall(req).execute().use { resp -> resp.body()?.string() }

        TrafficCaptureController.flush()
        waitUntil { countRequests("/traffic") >= 1 }
        val entry = JSONObject(lastRequest("/traffic")!!.body.readUtf8()).optJSONArray("entries")!!.getJSONObject(0)
        val reqHeaders = entry.optJSONObject("requestHeaders")!!
        assertTrue(reqHeaders.has("Host"))
        assertTrue(reqHeaders.optJSONArray("User-Agent")!!.getString(0).startsWith("okhttp/"))
        assertTrue(reqHeaders.has("Content-Length"))
    }

    /// 调整拦截器顺序后，SDK 不需要业务条件判断（前置拦截器补的头同样被记录）。
    @Test
    fun `interceptor order does not affect recording`() {
        TrafficCaptureController.start(baseURL(), appID, did)
        TrafficCaptureController.updateSession(capturing = true, sessionID = "sess-1")
        // SDK 位于补头拦截器之后。
        val sdkLastClient = OkHttpClient.Builder()
            .addInterceptor(headerInterceptor("X-First", "1"))
            .addInterceptor(MockNetPackInterceptor())
            .build()
        val req = Request.Builder().url(server.url("/api/v1/business")).get().build()
        sdkLastClient.newCall(req).execute().use { resp -> resp.body()?.string() }

        TrafficCaptureController.flush()
        waitUntil { countRequests("/traffic") >= 1 }
        val entry = JSONObject(lastRequest("/traffic")!!.body.readUtf8()).optJSONArray("entries")!!.getJSONObject(0)
        assertEquals("1", entry.optJSONObject("requestHeaders")!!.optJSONArray("X-First")!!.getString(0))
    }

    /// 缓存命中：无 networkResponse，记录来自 response.request，不访问服务端。
    @Test
    fun `cache hit records from response request without network send`() {
        TrafficCaptureController.start(baseURL(), appID, did)
        TrafficCaptureController.updateSession(capturing = true, sessionID = "sess-1")
        val cacheDir = File.createTempFile("mnpcache", "").apply { delete(); mkdirs() }
        try {
            val cacheClient = OkHttpClient.Builder()
                .cache(Cache(cacheDir, 10 * 1024 * 1024))
                .addInterceptor(MockNetPackInterceptor())
                .addInterceptor(headerInterceptor("X-Host-Hdr", "h1"))
                .build()
            handler = { req ->
                when {
                    req.path!!.endsWith("/traffic") ->
                        MockResponse().setResponseCode(202).setBody("""{"accepted":true,"count":1}""")
                    req.path!!.endsWith("/api/v1/business") ->
                        MockResponse().setResponseCode(200)
                            .setHeader("Content-Type", "application/json")
                            .setHeader("Cache-Control", "max-age=60")
                            .setBody("""{"hello":"world"}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
            val req = Request.Builder().url(server.url("/api/v1/business")).get().build()
            cacheClient.newCall(req).execute().use { resp -> resp.body()?.string() }
            cacheClient.newCall(req).execute().use { resp -> resp.body()?.string() }
            // 第二次请求缓存命中，未发到服务端。
            assertEquals(1, countRequests("/api/v1/business"))

            TrafficCaptureController.flush()
            waitUntil { countRequests("/traffic") >= 1 }
            val entries = JSONObject(lastRequest("/traffic")!!.body.readUtf8()).optJSONArray("entries")!!
            assertEquals(2, entries.length())
            // 缓存命中条目仍正常记录（URL 来自 response.request，与本次调用一致；
            // 应用层头 X-Host-Hdr 保留 —— 缓存命中不宣称网络字节级请求）。
            val cachedEntry = entries.getJSONObject(1)
            assertTrue(cachedEntry.optString("url").endsWith("/api/v1/business"))
            assertEquals(200, cachedEntry.optInt("statusCode"))
            assertEquals("h1", cachedEntry.optJSONObject("requestHeaders")!!.optJSONArray("X-Host-Hdr")!!.getString(0))
        } finally {
            cacheDir.deleteRecursively()
        }
    }

    /// 其他拦截器直接合成响应（不 proceed）：无 networkResponse，记录正常且不访问网络。
    @Test
    fun `synthetic response without network is recorded from response request`() {
        TrafficCaptureController.start(baseURL(), appID, did)
        TrafficCaptureController.updateSession(capturing = true, sessionID = "sess-1")
        val syntheticClient = OkHttpClient.Builder()
            .addInterceptor(MockNetPackInterceptor())
            .addInterceptor(syntheticResponseInterceptor())
            .build()
        val req = Request.Builder().url(server.url("/api/v1/business")).get().build()
        val respBody = syntheticClient.newCall(req).execute().use { resp ->
            assertEquals(200, resp.code())
            resp.body()?.string()
        }
        assertEquals("""{"synthetic":true}""", respBody)
        // 未访问真实服务端。
        assertEquals(0, countRequests("/api/v1/business"))

        TrafficCaptureController.flush()
        waitUntil { countRequests("/traffic") >= 1 }
        val entry = JSONObject(lastRequest("/traffic")!!.body.readUtf8()).optJSONArray("entries")!!.getJSONObject(0)
        assertEquals(200, entry.optInt("statusCode"))
        assertEquals("""{"synthetic":true}""", entry.optString("responseBody"))
        assertTrue(entry.optString("url").endsWith("/api/v1/business"))
    }

    /// 请求失败：保存原始请求与错误，不伪造最终请求；异常照常上抛。
    @Test
    fun `failed request records original and error and rethrows`() {
        TrafficCaptureController.start(baseURL(), appID, did)
        TrafficCaptureController.updateSession(capturing = true, sessionID = "sess-1")
        // 业务请求指向不可达端口（连接拒绝）；上传端点 baseURL() 仍是存活 server。
        val deadURL = "http://127.0.0.1:1/api/v1/business"
        val req = Request.Builder().url(deadURL).get().build()
        var thrown: java.io.IOException? = null
        try {
            client.newCall(req).execute().use { resp -> resp.body()?.string() }
        } catch (e: java.io.IOException) {
            thrown = e
        }
        assertTrue("expected IOException, got $thrown", thrown != null)

        TrafficCaptureController.flush()
        waitUntil { countRequests("/traffic") >= 1 }
        val entry = JSONObject(lastRequest("/traffic")!!.body.readUtf8()).optJSONArray("entries")!!.getJSONObject(0)
        assertEquals(deadURL, entry.optString("url"))
        assertTrue(entry.has("error"))
        assertTrue(!entry.has("statusCode"))
    }

    /// 一次性 / 流式请求体不会被采集逻辑二次消费：writeTo 仅被网络调用一次，记录 unavailable。
    @Test
    fun `one shot replaced body is not re consumed and marked unavailable`() {
        TrafficCaptureController.start(baseURL(), appID, did)
        TrafficCaptureController.updateSession(capturing = true, sessionID = "sess-1")
        val counting = CountingBody("replaced payload")
        val replacingClient = OkHttpClient.Builder()
            .addInterceptor(MockNetPackInterceptor())
            .addInterceptor(replaceBodyInterceptor(counting))
            .build()
        val req = Request.Builder()
            .url(server.url("/api/v1/business"))
            .post(RequestBody.create(MediaType.parse("text/plain"), "orig"))
            .build()
        replacingClient.newCall(req).execute().use { resp -> resp.body()?.string() }

        // 服务端收到替换后的 body（转发成功，只消费一次）。
        assertEquals("replaced payload", lastRequest("/api/v1/business")!!.body.readUtf8())
        assertEquals(1, counting.writeCount)

        TrafficCaptureController.flush()
        waitUntil { countRequests("/traffic") >= 1 }
        val entry = JSONObject(lastRequest("/traffic")!!.body.readUtf8()).optJSONArray("entries")!!.getJSONObject(0)
        assertEquals("[body unavailable]", entry.optString("requestBody"))
        assertTrue(entry.optString("url").endsWith("/api/v1/business"))
    }

    /// 超限文本请求体：记录截断样本 + [truncated N bytes] 前缀（identity 匹配路径）。
    @Test
    fun `large text request body is recorded truncated with size prefix`() {
        TrafficCaptureController.start(baseURL(), appID, did)
        TrafficCaptureController.updateSession(capturing = true, sessionID = "sess-1")
        val bigText = "x".repeat(1_050_000) // > BODY_LIMIT_BYTES (1MB)
        val req = Request.Builder()
            .url(server.url("/api/v1/business"))
            .post(RequestBody.create(MediaType.parse("text/plain"), bigText))
            .build()
        client.newCall(req).execute().use { resp -> resp.body()?.string() }
        assertEquals(bigText, lastRequest("/api/v1/business")!!.body.readUtf8())

        TrafficCaptureController.flush()
        waitUntil { countRequests("/traffic") >= 1 }
        val entry = JSONObject(lastRequest("/traffic")!!.body.readUtf8()).optJSONArray("entries")!!.getJSONObject(0)
        val body = entry.optString("requestBody")
        assertTrue(body.startsWith("[truncated 1050000 bytes] "))
        assertTrue(body.length < 1_050_000) // 仅截断样本，不携带全量
    }

    /// 超限二进制请求体：记录 [binary N bytes] 占位 + base64 样本（identity 匹配路径）。
    @Test
    fun `large binary request body is recorded with binary placeholder`() {
        TrafficCaptureController.start(baseURL(), appID, did)
        TrafficCaptureController.updateSession(capturing = true, sessionID = "sess-1")
        val bigBytes = ByteArray(1_050_000) { 0xFF.toByte() } // 非 UTF-8，> BODY_LIMIT_BYTES
        val req = Request.Builder()
            .url(server.url("/api/v1/business"))
            .post(RequestBody.create(MediaType.parse("application/octet-stream"), bigBytes))
            .build()
        client.newCall(req).execute().use { resp -> resp.body()?.string() }
        assertEquals(1_050_000, lastRequest("/api/v1/business")!!.body.readByteArray().size)

        TrafficCaptureController.flush()
        waitUntil { countRequests("/traffic") >= 1 }
        val entry = JSONObject(lastRequest("/traffic")!!.body.readUtf8()).optJSONArray("entries")!!.getJSONObject(0)
        assertEquals("[binary 1050000 bytes]", entry.optString("requestBody"))
        assertTrue(entry.has("requestBodyBase64"))
        assertTrue(entry.optString("requestBodyBase64").isNotEmpty())
    }

    /// 重定向：记录最终响应关联请求（最终 URL），不宣称记录所有重定向过程。
    @Test
    fun `redirect records final response associated request`() {
        TrafficCaptureController.start(baseURL(), appID, did)
        TrafficCaptureController.updateSession(capturing = true, sessionID = "sess-1")
        handler = { req ->
            when {
                req.path!!.endsWith("/traffic") ->
                    MockResponse().setResponseCode(202).setBody("""{"accepted":true,"count":1}""")
                req.path!!.endsWith("/api/v1/redirect") ->
                    MockResponse().setResponseCode(302)
                        .setHeader("Location", server.url("/api/v1/final").toString())
                        .setBody("")
                req.path!!.endsWith("/api/v1/final") ->
                    MockResponse().setResponseCode(200).setBody("""{"ok":true}""")
                else -> MockResponse().setResponseCode(404)
            }
        }
        val req = Request.Builder().url(server.url("/api/v1/redirect")).get().build()
        val body = client.newCall(req).execute().use { resp ->
            assertEquals(200, resp.code())
            resp.body()?.string()
        }
        assertEquals("""{"ok":true}""", body)
        assertEquals(1, countRequests("/api/v1/redirect"))
        assertEquals(1, countRequests("/api/v1/final"))

        TrafficCaptureController.flush()
        waitUntil { countRequests("/traffic") >= 1 }
        val entry = JSONObject(lastRequest("/traffic")!!.body.readUtf8()).optJSONArray("entries")!!.getJSONObject(0)
        assertTrue(entry.optString("url").endsWith("/api/v1/final"))
    }
}
