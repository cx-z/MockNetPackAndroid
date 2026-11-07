package com.mocknetpack.android.internal.capture

import com.mocknetpack.android.MediaRef
import com.mocknetpack.android.MockRule
import com.mocknetpack.android.MockResponse as SdkMockResponse
import com.mocknetpack.android.internal.connection.ConnectionClient
import com.mocknetpack.android.internal.mock.MockRuleController
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * M15（方案 D 阶段二）：大图规则回放——按 media 引用拉 blob、LRU 复用、fail-open。
 *
 * 与 iOS `MockRuleMediaReplayTests` 三条用例对齐：
 * ① media 规则回放给定字节；② 取数失败 fail-open 转发真实请求且留 error 命中流量；
 * ③ media 缺失时退回 bodyBase64（回归）。另补 LRU 复用一条。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MockRuleMediaReplayTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private val appID = "com.example.basedemo"
    private val did = "test-did-0001"
    private val ruleID = "rule-m15"
    private val recordedRequests = CopyOnWriteArrayList<RecordedRequest>()

    /// 1x1 PNG 的最小字节（内容不重要，只要求二进制非空且尺寸可校验）。
    private val blobBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x01)

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
        RuleMediaCache.clientFactory = { ConnectionClient.create(it) }
        RuleMediaCache.maxCacheBytes = 32L * 1024 * 1024
        RuleMediaCache.reset()
        MockRuleController.reset()
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
                else -> MockResponse().setResponseCode(404)
            }
        }
    }

    @After
    fun tearDown() {
        TrafficCaptureController.stop()
        TrafficCaptureController.resetBinaryCodecs()
        MockRuleController.reset()
        RuleMediaCache.clientFactory = null
        RuleMediaCache.reset()
        server.shutdown()
        Dispatchers.resetMain()
    }

    // MARK: - 辅助

    private fun baseURL(): String = server.url("/api/v1").toString()

    private fun startCapturing() {
        TrafficCaptureController.start(baseURL(), appID, did)
        TrafficCaptureController.updateSession(capturing = true, sessionID = "sess-1")
    }

    private fun mediaRule(body: String = "", ref: MediaRef?): MockRule = MockRule(
        id = ruleID,
        method = "GET",
        path = "/api/v1/big.png",
        response = SdkMockResponse(
            statusCode = 200,
            headers = mapOf("Content-Type" to "image/png"),
            body = body,
            media = ref,
        ),
        enabled = true,
        effective = true,
    )

    private fun getBusinessBytes(): ByteArray {
        val req = Request.Builder().url(server.url("/api/v1/big.png")).get().build()
        return client.newCall(req).execute().use { resp ->
            assertEquals(200, resp.code())
            resp.body()?.bytes() ?: ByteArray(0)
        }
    }

    private fun waitUntil(timeoutMs: Long = 3000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(10)
        }
        throw AssertionError("condition not met within ${timeoutMs}ms")
    }

    private fun countRequests(suffix: String): Int = recordedRequests.count { it.path?.endsWith(suffix) == true }

    // MARK: - 用例

    /** ① media 规则：回放前拉 blob，业务请求拿到原始字节（而非规则里为空的 body）。 */
    @Test
    fun `media rule replays blob bytes`() {
        MockRuleController.applyForTesting(
            listOf(mediaRule(ref = MediaRef(sha256 = "sha-big-1", size = blobBytes.size.toLong(), contentType = "image/png"))),
            version = 1,
        )
        startCapturing()
        handler = { req ->
            when {
                req.path!!.endsWith("/traffic") ->
                    MockResponse().setResponseCode(202).setBody("""{"accepted":true,"count":1}""")
                req.path!!.endsWith("/media") -> {
                    // 契约：media 端点返回原始字节；SDK 自身请求须带 skipHeader。
                    assertEquals("1", req.getHeader("X-MockNetPack-Skip"))
                    MockResponse().setResponseCode(200)
                        .setHeader("Content-Type", "image/png")
                        .setBody(okio.Buffer().write(blobBytes))
                }
                else -> {
                    throw AssertionError("非 media/traffic 请求不应发出: ${req.path}")
                }
            }
        }

        assertArrayEquals(blobBytes, getBusinessBytes())
        assertEquals(1, countRequests("/media"))
        assertEquals(blobBytes.size.toLong(), RuleMediaCache.cachedByteCount)
    }

    /** LRU 复用：同一 sha256 第二次回放不再发 media 请求。 */
    @Test
    fun `media rule reuses cached blob`() {
        MockRuleController.applyForTesting(
            listOf(mediaRule(ref = MediaRef(sha256 = "sha-big-2", size = blobBytes.size.toLong(), contentType = "image/png"))),
            version = 1,
        )
        startCapturing()
        handler = { req ->
            if (req.path!!.endsWith("/traffic")) {
                MockResponse().setResponseCode(202).setBody("""{"accepted":true,"count":1}""")
            } else {
                MockResponse().setResponseCode(200)
                    .setHeader("Content-Type", "image/png")
                    .setBody(okio.Buffer().write(blobBytes))
            }
        }

        assertArrayEquals(blobBytes, getBusinessBytes())
        assertArrayEquals(blobBytes, getBusinessBytes())
        assertEquals(1, countRequests("/media"))
    }

    /** ② 取数失败：fail-open 转发真实请求，并留一条带 error 的命中流量。 */
    @Test
    fun `media fetch failure fails open and records error`() {
        MockRuleController.applyForTesting(
            listOf(mediaRule(ref = MediaRef(sha256 = "sha-big-3", size = blobBytes.size.toLong(), contentType = "image/png"))),
            version = 1,
        )
        startCapturing()
        val realBytes = byteArrayOf(0x00, 0x11, 0x22)
        handler = { req ->
            when {
                req.path!!.endsWith("/traffic") ->
                    MockResponse().setResponseCode(202).setBody("""{"accepted":true,"count":1}""")
                // 源站 blob 已失效（500）→ SDK 无法回放。
                req.path!!.endsWith("/media") -> MockResponse().setResponseCode(500).setBody("boom")
                // 真实业务请求被 fail-open 转发到网络。
                else -> MockResponse().setResponseCode(200)
                    .setHeader("Content-Type", "image/png")
                    .setBody(okio.Buffer().write(realBytes))
            }
        }

        assertArrayEquals(realBytes, getBusinessBytes())

        TrafficCaptureController.flush()
        waitUntil { countRequests("/traffic") >= 1 }
        val entries = JSONObject(
            recordedRequests.last { it.path!!.endsWith("/traffic") }.body.readUtf8(),
        ).optJSONArray("entries")!!
        val mockedFailure = (0 until entries.length())
            .map { entries.getJSONObject(it) }
            .firstOrNull { it.optBoolean("mocked") && it.optString("error") == "media_unavailable" }
        assertNotNull("media 取数失败必须留一条带 error 的命中流量", mockedFailure)
    }

    /** ③ media 缺失时退回 bodyBase64（既有路径回归），且不发 media 请求。 */
    @Test
    fun `non media rule falls back to bodyBase64`() {
        MockRuleController.applyForTesting(
            listOf(
                MockRule(
                    id = "rule-plain",
                    method = "GET",
                    path = "/api/v1/big.png",
                    response = SdkMockResponse(
                        statusCode = 200,
                        headers = mapOf("Content-Type" to "image/png"),
                        body = "[binary 10 bytes]",
                        bodyBase64 = java.util.Base64.getEncoder().encodeToString(blobBytes),
                    ),
                    enabled = true,
                    effective = true,
                ),
            ),
            version = 1,
        )
        startCapturing()
        handler = { req ->
            if (req.path!!.endsWith("/traffic")) {
                MockResponse().setResponseCode(202).setBody("""{"accepted":true,"count":1}""")
            } else {
                throw AssertionError("非 media 规则不应请求 media: ${req.path}")
            }
        }

        assertArrayEquals(blobBytes, getBusinessBytes())
        assertEquals(0, countRequests("/media"))
        assertTrue(RuleMediaCache.cachedByteCount == 0L)
    }
}
