package com.mocknetpack.android.internal.capture

import com.mocknetpack.android.MockResponse as SdkMockResponse
import com.mocknetpack.android.MockRule
import com.mocknetpack.android.TrafficEntry
import com.mocknetpack.android.internal.connection.ConnectionClient
import com.mocknetpack.android.internal.mock.MockRuleController
import java.util.concurrent.CopyOnWriteArrayList
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
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/// 启动握手期流量暂存 + 采集代次归属验证（与 iOS 启动流量测试对齐）。
///
/// 覆盖：首心跳前请求不上传/首心跳晋升（顺序保持）、启动期应用 Mock 快照/不抓 SDK 请求、
/// 首 idle 丢弃 + 旧代迟到回包作废、迟到回包归首个会话、restart 作废旧在飞、
/// cancelStartupCapture 只匹配当前代、暂存 200 条 / 8MB 全量序列化有界保最早、
/// Interceptor 门闩（capturing=false 但 startupBuffering=true 时请求被接管）。
@OptIn(ExperimentalCoroutinesApi::class)
class StartupTrafficCaptureTest {

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

    private fun businessPost(body: String): String {
        val req = Request.Builder()
            .url(server.url("/api/v1/business"))
            .post(RequestBody.create(MediaType.parse("text/plain"), body))
            .build()
        return client.newCall(req).execute().use { resp -> resp.body()?.string() ?: "" }
    }

    /// 最近一批 /traffic 上传请求体（缓存解析结果；RecordedRequest.body 读一次即消费）。
    private fun uploadedBody(): JSONObject {
        val raw = lastRequest("/traffic")!!.body.readUtf8()
        if (!raw.trimStart().startsWith("{")) {
            fail("upload body is not a JSONObject: [${raw.take(300)}]")
        }
        return JSONObject(raw)
    }

    private fun uploadedPaths(body: JSONObject): List<String> {
        val arr = body.optJSONArray("entries")
        return (0 until arr.length()).map { arr.getJSONObject(it).optString("path") }
    }

    private fun makeEntry(
        method: String = "GET",
        path: String = "/character/get_discover_config",
        responseBody: String? = """{"ok":true}""",
    ): TrafficEntry {
        return TrafficEntry(
            timestamp = "2026-10-21T00:00:00.000Z",
            method = method,
            url = "https://business.local$path",
            path = path,
            query = "",
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

    // MARK: - 首心跳前暂存 / 首心跳晋升

    /// 两个早期请求先返回业务数据，首次有效心跳后才进入平台上传批次（顺序保持）。
    @Test
    fun `first heartbeat promotes buffered entries as first session batch`() {
        TrafficCaptureController.start(baseURL(), appID, did)
        assertTrue(!TrafficCaptureController.isCapturing)
        // 启动暂存期：可接管（门闩非空）但不上传。
        assertTrue(TrafficCaptureController.httpCaptureGeneration != null)
        TrafficCaptureController.record(makeEntry(path = "/character/get_discover_config"))
        TrafficCaptureController.record(makeEntry(path = "/character/get_discover_list"))
        TrafficCaptureController.flush()
        Thread.sleep(150)
        assertEquals("buffered entries must not upload before first heartbeat", 0, countRequests("/traffic"))

        TrafficCaptureController.updateSession(capturing = true, sessionID = "first-session")
        waitUntil { countRequests("/traffic") >= 1 }
        val upBody = uploadedBody()
        assertEquals("first-session", upBody.optString("sessionId"))
        assertEquals(
            listOf("/character/get_discover_config", "/character/get_discover_list"),
            uploadedPaths(upBody),
        )
    }

    /// 首次心跳前的请求命中内存快照规则 → 启动缓冲期即 Mock 回包
    /// （不回真实网络）；带 skip 标记的 SDK 自请求仍直接放行、不抓。
    @Test
    fun `startup applies persisted mock snapshot but not sdk requests`() {
        MockRuleController.reset()
        MockRuleController.applyForTesting(
            listOf(
                MockRule(
                    id = "old-rule",
                    method = "POST",
                    path = "/api/v1/business",
                    response = SdkMockResponse(
                        statusCode = 503,
                        headers = null,
                        body = "old",
                        bodyBase64 = null,
                    ),
                    enabled = true,
                    effective = true,
                ),
            ),
            version = 1,
        )
        TrafficCaptureController.start(baseURL(), appID, did)
        // 启动暂存期 POST 命中 Mock 快照 → 直接回 503 "old"，不访问真实网络。
        val body = businessPost("startup body")
        assertEquals("old", body)
        assertEquals("真实服务器不应收到 Mock 命中的请求", 0, countRequests("/api/v1/business"))

        // SDK 自请求（skipHeader）直接放行、不记录。
        val skipReq = Request.Builder()
            .url(server.url("/api/v1/business"))
            .post(RequestBody.create(MediaType.parse("text/plain"), "skip me"))
            .header("X-MockNetPack-Skip", "1")
            .build()
        client.newCall(skipReq).execute().use { resp -> resp.body()?.string() }
        assertEquals(1, countRequests("/api/v1/business"))

        TrafficCaptureController.updateSession(capturing = true, sessionID = "first-session")
        waitUntil { countRequests("/traffic") >= 1 }
        // 只有 Mock 命中的业务请求入暂存并晋升（mocked=true）；skip 请求不出现。
        val upBody = uploadedBody()
        val entries = upBody.optJSONArray("entries")
        assertEquals(1, entries.length())
        assertEquals(listOf("/api/v1/business"), uploadedPaths(upBody))
        val hit = entries.getJSONObject(0)
        assertTrue("mock 命中的记录 mocked=true", hit.optBoolean("mocked"))
        assertEquals("old-rule", hit.optString("matchedRuleId"))
    }

    // MARK: - 首 idle 丢弃 / 迟到回包归属

    /// 首次 idle 结果丢弃启动缓存与在飞请求；以后激活会话也不能带回旧记录。
    @Test
    fun `first idle heartbeat discards buffered and late entries`() {
        TrafficCaptureController.start(baseURL(), appID, did)
        val generation = TrafficCaptureController.httpCaptureGeneration!!
        TrafficCaptureController.record(makeEntry(), generation)
        TrafficCaptureController.updateSession(capturing = false, sessionID = null)
        assertNull("idle 首心跳后门闩关闭", TrafficCaptureController.httpCaptureGeneration)

        TrafficCaptureController.updateSession(capturing = true, sessionID = "later-session")
        TrafficCaptureController.record(makeEntry(path = "/late"), generation) // 旧代：作废丢弃
        TrafficCaptureController.record(makeEntry(path = "/current"))
        TrafficCaptureController.flush()
        waitUntil { countRequests("/traffic") >= 1 }
        assertEquals(listOf("/current"), uploadedPaths(uploadedBody()))
    }

    /// 有效首次心跳保留在飞请求的代次，回包晚于心跳仍可被正常记录。
    @Test
    fun `late response belongs to first active session`() {
        TrafficCaptureController.start(baseURL(), appID, did)
        val generation = TrafficCaptureController.httpCaptureGeneration!!
        TrafficCaptureController.updateSession(capturing = true, sessionID = "first-session")
        // 晋升时代次保留：创建于启动期、回包晚于心跳的请求仍归首个会话。
        TrafficCaptureController.record(makeEntry(), generation)
        TrafficCaptureController.flush()
        waitUntil { countRequests("/traffic") >= 1 }
        assertEquals(1, JSONObject(lastRequest("/traffic")!!.body.readUtf8()).optJSONArray("entries").length())
    }

    /// stop/start 后旧的在飞响应不能写入新一次启动暂存或新会话。
    @Test
    fun `restart invalidates in flight startup responses`() {
        TrafficCaptureController.start(baseURL(), appID, did)
        val oldGeneration = TrafficCaptureController.httpCaptureGeneration!!
        TrafficCaptureController.stop()
        TrafficCaptureController.start(baseURL(), appID, did)
        TrafficCaptureController.record(makeEntry(path = "/old"), oldGeneration) // 旧代：丢弃
        TrafficCaptureController.record(makeEntry(path = "/new"))
        TrafficCaptureController.updateSession(capturing = true, sessionID = "new-session")
        waitUntil { countRequests("/traffic") >= 1 }
        assertEquals(listOf("/new"), uploadedPaths(uploadedBody()))
    }

    /// 首次握手被永久拒绝时停止暂存，且不能污染后续手动激活的会话。
    @Test
    fun `rejected startup discards entries`() {
        TrafficCaptureController.start(baseURL(), appID, did)
        TrafficCaptureController.record(makeEntry())
        TrafficCaptureController.cancelStartupCapture(TrafficCaptureController.httpCaptureGeneration)
        assertNull(TrafficCaptureController.httpCaptureGeneration)
        TrafficCaptureController.updateSession(capturing = true, sessionID = "later-session")
        TrafficCaptureController.record(makeEntry(path = "/current"))
        TrafficCaptureController.flush()
        waitUntil { countRequests("/traffic") >= 1 }
        assertEquals(listOf("/current"), uploadedPaths(uploadedBody()))
    }

    // MARK: - 暂存有界（200 条 / 8MB 全量序列化，保最早）

    /// 长时间无法得到首个心跳时最多保留 200 条，优先保留最早请求。
    @Test
    fun `startup buffer keeps earliest entries within count limit`() {
        TrafficCaptureController.start(baseURL(), appID, did)
        for (index in 0..TrafficCaptureController.MAX_BATCH_COUNT) {
            TrafficCaptureController.record(makeEntry(path = "/request/$index"))
        }
        TrafficCaptureController.updateSession(capturing = true, sessionID = "first-session")
        waitUntil { countRequests("/traffic") >= 1 }
        val paths = uploadedPaths(uploadedBody())
        assertEquals(TrafficCaptureController.MAX_BATCH_COUNT, paths.size)
        assertEquals("/request/0", paths.first())
        assertEquals("/request/${TrafficCaptureController.MAX_BATCH_COUNT - 1}", paths.last())
    }

    /// 8MB 额度按全量序列化（含 base64 副本）计费；超限记录不挤掉最早请求，
    /// 后续小记录仍可暂存。
    @Test
    fun `startup buffer bounds all body representations`() {
        TrafficCaptureController.start(baseURL(), appID, did)
        TrafficCaptureController.record(makeEntry(path = "/first"))
        val oversized = makeEntry(path = "/oversized").copy(
            responseBodyBase64 = "A".repeat(TrafficCaptureController.MAX_BATCH_BYTES),
        )
        TrafficCaptureController.record(oversized) // 全量序列化 > 8MB → 拒绝
        TrafficCaptureController.record(makeEntry(path = "/last"))
        TrafficCaptureController.updateSession(capturing = true, sessionID = "first-session")
        waitUntil { countRequests("/traffic") >= 1 }
        assertEquals(listOf("/first", "/last"), uploadedPaths(uploadedBody()))
    }

    /// 旧拒绝在新启动已经暂存后才清理，也只能匹配旧代次；新启动的缓存与在飞响应都必须保留。
    @Test
    fun `old rejection cannot cancel new startup buffer`() {
        TrafficCaptureController.start(baseURL(), appID, did)
        val oldGeneration = TrafficCaptureController.httpCaptureGeneration!!
        TrafficCaptureController.stop()
        TrafficCaptureController.start(baseURL(), appID, did)
        val newGeneration = TrafficCaptureController.httpCaptureGeneration!!
        TrafficCaptureController.record(makeEntry(path = "/new-buffered"), newGeneration)

        TrafficCaptureController.cancelStartupCapture(oldGeneration) // 旧代：不取消新启动
        TrafficCaptureController.cancelStartupCapture(null) // 无代：no-op
        assertEquals(newGeneration, TrafficCaptureController.httpCaptureGeneration)
        TrafficCaptureController.record(makeEntry(path = "/new-inflight"), newGeneration)
        TrafficCaptureController.updateSession(capturing = true, sessionID = "new-session")
        waitUntil { countRequests("/traffic") >= 1 }
        assertEquals(listOf("/new-buffered", "/new-inflight"), uploadedPaths(uploadedBody()))
    }

    // MARK: - Interceptor 门闩（capturing=false 但 startupBuffering=true）

    /// 启动暂存期业务请求照常转发真实网络、body 完整，暂存并在首个会话晋升上传。
    @Test
    fun `interceptor captures request during startup buffering before session`() {
        TrafficCaptureController.start(baseURL(), appID, did)
        assertTrue(!TrafficCaptureController.isCapturing)
        val body = businessPost("startup body")
        assertEquals("""{"hello":"world"}""", body)
        assertEquals("startup body", lastRequest("/api/v1/business")!!.body.readUtf8())

        TrafficCaptureController.flush()
        Thread.sleep(150)
        assertEquals("startup buffered entries must not upload", 0, countRequests("/traffic"))

        TrafficCaptureController.updateSession(capturing = true, sessionID = "sess-1")
        waitUntil { countRequests("/traffic") >= 1 }
        val upBody = uploadedBody()
        assertEquals("sess-1", upBody.optString("sessionId"))
        assertEquals(listOf("/api/v1/business"), uploadedPaths(upBody))
    }
}
