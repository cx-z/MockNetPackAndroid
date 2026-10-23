package com.mocknetpack.android.internal.mock

import com.mocknetpack.android.MockResponse
import com.mocknetpack.android.MockRule
import com.mocknetpack.android.MockRuleList
import com.mocknetpack.android.internal.connection.ConnectionClient
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse as MockWsResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/// MockRuleController 增量拉取 / fail-open A / 匹配 / ruleId 填充验证。
@OptIn(ExperimentalCoroutinesApi::class)
class MockRuleControllerTest {

    private lateinit var server: MockWebServer
    private val recordedRequests = CopyOnWriteArrayList<RecordedRequest>()
    private lateinit var handler: (RecordedRequest) -> MockWsResponse

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        server = MockWebServer()
        server.start()
        MockRuleController.reset()
        server.setDispatcher(object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockWsResponse {
                recordedRequests.add(request)
                return handler(request)
            }
        })
        handler = { MockWsResponse().setResponseCode(404) }
    }

    @After
    fun tearDown() {
        MockRuleController.reset()
        server.shutdown()
        Dispatchers.resetMain()
    }

    private fun baseURL(): String = server.url("/api/v1").toString()

    private fun client(): ConnectionClient = ConnectionClient.create(baseURL())

    private fun countRulePulls(): Int =
        recordedRequests.count { it.path!!.contains("/mock-rules") }

    private fun waitUntil(timeoutMs: Long = 3000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(10)
        }
        fail("condition not met within ${timeoutMs}ms")
    }

    private fun rulesJson(vararg rules: JSONObject): String {
        val arr = JSONArray()
        for (r in rules) arr.put(r)
        return JSONObject().put("version", 2).put("rules", arr).toString()
    }

    private fun ruleJson(id: String, method: String, path: String, body: String, statusCode: Int = 200): JSONObject {
        val resp = JSONObject()
            .put("statusCode", statusCode)
            .put("headers", JSONObject().put("Content-Type", "application/json"))
            .put("body", body)
        return JSONObject()
            .put("id", id)
            .put("method", method)
            .put("path", path)
            .put("response", resp)
            .put("enabled", true)
            .put("effective", true)
    }

    private fun mockRule(
        id: String,
        method: String,
        path: String,
        response: MockResponse = MockResponse(statusCode = 200, body = """{"mock":true}"""),
        effective: Boolean = true,
    ) = MockRule(id = id, method = method, path = path, response = response, enabled = true, effective = effective)

    // MARK: - 增量拉取

    @Test
    fun `sync pulls with sinceVersion when server version differs`() {
        handler = { req ->
            if (req.path!!.contains("/mock-rules")) {
                MockWsResponse().setResponseCode(200).setBody(rulesJson(ruleJson("r1", "GET", "/v1/users", """{"from":"server"}""")))
            } else {
                MockWsResponse().setResponseCode(404)
            }
        }
        // 本地快照 version=1 → 服务端 2 → 增量拉取 sinceVersion=1。
        MockRuleController.applyForTesting(listOf(mockRule("old", "GET", "/v1/old")), version = 1)
        runBlocking {
            MockRuleController.syncIfNeeded(client(), "com.example.basedemo", "did-1", serverVersion = 2)
        }

        waitUntil { countRulePulls() >= 1 }
        val pull = recordedRequests.first { it.path!!.contains("/mock-rules") }
        assertTrue(pull.path!!.contains("devices/com.example.basedemo/did-1/mock-rules"))
        assertEquals("1", pull.requestUrl!!.queryParameter("sinceVersion"))

        // 快照更新为新规则。
        val matched = MockRuleController.match("GET", "/v1/users")
        assertTrue(matched != null)
        assertEquals("r1", matched!!.ruleId)
    }

    @Test
    fun `sync no-op when versions equal`() {
        MockRuleController.applyForTesting(listOf(mockRule("r1", "GET", "/v1/users")), version = 5)
        runBlocking {
            MockRuleController.syncIfNeeded(client(), "com.example.basedemo", "did-1", serverVersion = 5)
        }
        Thread.sleep(100)
        assertEquals(0, countRulePulls())
    }

    // MARK: - fail-open A

    @Test
    fun `first pull failure keeps empty snapshot - no mock`() {
        handler = { MockWsResponse().setResponseCode(500) }
        // 从未拉到快照 → 拉取失败 → 不 Mock，走真实网络。
        runBlocking {
            MockRuleController.syncIfNeeded(client(), "com.example.basedemo", "did-1", serverVersion = 1)
        }
        waitUntil { countRulePulls() >= 1 }
        assertNull(MockRuleController.match("GET", "/v1/users"))
    }

    @Test
    fun `pull failure after snapshot keeps old rules - mock continues`() {
        handler = { MockWsResponse().setResponseCode(500) }
        // 已有快照（version=1）→ 本次拉取失败 → 沿用旧快照继续 Mock。
        MockRuleController.applyForTesting(listOf(mockRule("r1", "GET", "/v1/users")), version = 1)
        runBlocking {
            MockRuleController.syncIfNeeded(client(), "com.example.basedemo", "did-1", serverVersion = 2)
        }
        waitUntil { countRulePulls() >= 1 }
        val matched = MockRuleController.match("GET", "/v1/users")
        assertTrue("旧快照应继续生效", matched != null)
        assertEquals("r1", matched!!.ruleId)
    }

    // MARK: - 匹配

    @Test
    fun `match is method case insensitive with ruleId fill`() {
        MockRuleController.applyForTesting(
            listOf(mockRule("r1", "GET", "/v1/users")),
            version = 1,
        )
        // Method 大小写不敏感。
        val hit = MockRuleController.match("get", "/v1/users")
        assertTrue(hit != null)
        assertEquals("r1", hit!!.ruleId)
        assertEquals(200, hit.statusCode)
        // path 精确匹配（含 query 差异不参与）。
        assertNull(MockRuleController.match("GET", "/v1/users?page=2"))
        assertNull(MockRuleController.match("POST", "/v1/users"))
    }

    @Test
    fun `match respects effective flag from snapshot`() {
        // 服务端只下发 effective=true（契约保证）；SDK 直接使用快照中规则。
        MockRuleController.applyForTesting(
            listOf(mockRule("r1", "GET", "/v1/users", effective = true)),
            version = 1,
        )
        val hit = MockRuleController.match("GET", "/v1/users")
        assertTrue(hit != null)
    }

    @Test
    fun `reset clears snapshot`() {
        MockRuleController.applyForTesting(listOf(mockRule("r1", "GET", "/v1/users")), version = 3)
        MockRuleController.reset()
        assertNull(MockRuleController.match("GET", "/v1/users"))
        // 重置后版本回到 0 → 下次 sync 从 0 全量拉取。
        runBlocking {
            MockRuleController.syncIfNeeded(client(), "com.example.basedemo", "did-1", serverVersion = 1)
        }
        waitUntil { countRulePulls() >= 1 }
        assertEquals("0", recordedRequests.first { it.path!!.contains("/mock-rules") }.requestUrl!!.queryParameter("sinceVersion"))
    }

    // MARK: - 契约 JSON 解析

    @Test
    fun `parseRuleList decodes contract fields`() {
        val json = JSONObject(
            rulesJson(
                ruleJson("r1", "GET", "/v1/users", """{"a":1}"""),
            ),
        )
        val list = MockRuleController.parseRuleList(json)
        assertEquals(2, list.version)
        assertEquals(1, list.rules.size)
        val rule = list.rules[0]
        assertEquals("r1", rule.id)
        assertEquals("GET", rule.method)
        assertEquals("/v1/users", rule.path)
        assertTrue(rule.effective)
        assertTrue(rule.enabled)
        assertEquals(200, rule.response.statusCode)
        assertEquals("application/json", rule.response.headers?.get("Content-Type"))
        assertEquals("""{"a":1}""", rule.response.body)
    }

    @Test
    fun `parseRuleList handles binary bodyBase64`() {
        val b64 = java.util.Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3, 0x7f))
        val resp = JSONObject()
            .put("statusCode", 200)
            .put("body", "[binary 4 bytes]")
            .put("bodyBase64", b64)
        val rule = JSONObject()
            .put("id", "r2")
            .put("method", "POST")
            .put("path", "/v1/bin")
            .put("response", resp)
            .put("enabled", true)
            .put("effective", true)
        val list = MockRuleController.parseRuleList(JSONObject().put("version", 7).put("rules", JSONArray().put(rule)))
        assertEquals(7, list.version)
        assertEquals(b64, list.rules[0].response.bodyBase64)
        assertEquals("[binary 4 bytes]", list.rules[0].response.body)
    }

    @Test
    fun `parseRuleList decodes delayMs and treats missing as null`() {
        val withDelay = JSONObject()
            .put("id", "rd")
            .put("method", "GET")
            .put("path", "/v1/d")
            .put("response", JSONObject()
                .put("statusCode", 200)
                .put("body", "{}")
                .put("delayMs", 1500))
            .put("enabled", true)
            .put("effective", true)
        val withoutDelay = JSONObject()
            .put("id", "rn")
            .put("method", "GET")
            .put("path", "/v1/n")
            .put("response", JSONObject().put("statusCode", 200).put("body", "{}"))
            .put("enabled", true)
            .put("effective", true)
        val list = MockRuleController.parseRuleList(
            JSONObject().put("version", 1).put("rules", JSONArray().put(withDelay).put(withoutDelay)),
        )
        assertEquals(1500, list.rules[0].response.delayMs)
        assertNull(list.rules[1].response.delayMs)
    }

    @Test
    fun `parseRuleList treats explicit 0 and null delayMs as null`() {
        val zero = JSONObject()
            .put("id", "r0")
            .put("method", "GET")
            .put("path", "/v1/zero")
            .put("response", JSONObject().put("statusCode", 200).put("body", "{}").put("delayMs", 0))
            .put("enabled", true).put("effective", true)
        val nullVal = JSONObject()
            .put("id", "rn")
            .put("method", "GET")
            .put("path", "/v1/null")
            .put("response", JSONObject().put("statusCode", 200).put("body", "{}").put("delayMs", JSONObject.NULL))
            .put("enabled", true).put("effective", true)
        val list = MockRuleController.parseRuleList(
            JSONObject().put("version", 1).put("rules", JSONArray().put(zero).put(nullVal)),
        )
        // 显式 0 / null 都归一为 null（与 omitempty 语义对齐：0 = 无延迟）
        assertNull(list.rules[0].response.delayMs)
        assertNull(list.rules[1].response.delayMs)
    }

    // MARK: - AN-9 快照序列化 roundtrip

    @Test
    fun `rule snapshot serialization roundtrips`() {
        val rules = listOf(
            MockRule(
                id = "r1",
                method = "POST",
                path = "/api/v1/business",
                response = MockResponse(
                    statusCode = 503,
                    headers = mapOf("Content-Type" to "application/json", "X-Mock" to "1"),
                    body = "old",
                    bodyBase64 = null,
                    delayMs = 250,
                ),
                enabled = true,
                effective = true,
            ),
            MockRule(
                id = "r2",
                method = "GET",
                path = "/x/y",
                response = MockResponse(
                    statusCode = 200,
                    headers = null,
                    body = null,
                    bodyBase64 = "aGVsbG8=",
                    delayMs = null,
                ),
                enabled = false,
                effective = false,
            ),
        )

        // 序列化 → 包一层 version → 反序列化
        val json = JSONObject()
            .put("version", 7)
            .put("rules", MockRuleController.rulesToJsonArray(rules))
        val list = MockRuleController.parseRuleList(json)

        assertEquals(7, list.version)
        assertEquals(2, list.rules.size)

        val r1 = list.rules[0]
        assertEquals("r1", r1.id)
        assertEquals("POST", r1.method)
        assertEquals("/api/v1/business", r1.path)
        assertEquals(true, r1.enabled)
        assertEquals(true, r1.effective)
        assertEquals(503, r1.response.statusCode)
        assertEquals(mapOf("Content-Type" to "application/json", "X-Mock" to "1"), r1.response.headers)
        assertEquals("old", r1.response.body)
        assertNull(r1.response.bodyBase64)
        assertEquals(250, r1.response.delayMs)
        assertNull("ruleId 不落盘", r1.response.ruleId)

        val r2 = list.rules[1]
        assertEquals("r2", r2.id)
        assertEquals(false, r2.enabled)
        assertEquals(false, r2.effective)
        assertEquals("aGVsbG8=", r2.response.bodyBase64)
        assertNull(r2.response.body)
        assertNull(r2.response.delayMs)
    }

    /// F2 并发防护（B 会话复核 F2）：match / clearSnapshot / applyForTesting 并发
    /// （含 ensureLoaded 首次加载竞态）不抛异常、不返回越界规则。ensureLoaded
    /// 的构造→加载→解析→赋值与 clearSnapshot 同锁互斥，压力测试暴露撕裂/复活。
    @Test
    fun `concurrent match and clear does not tear or crash`() {
        val ruleA = mockRule("A", "GET", "/a")
        val errors = CopyOnWriteArrayList<Throwable>()
        val threads = mutableListOf<Thread>()
        // 写线程：交替写入规则 A / 清空快照。
        threads += Thread {
            try {
                repeat(200) { i ->
                    if (i % 2 == 0) {
                        MockRuleController.applyForTesting(listOf(ruleA), version = i + 1)
                    } else {
                        MockRuleController.clearSnapshot()
                    }
                }
            } catch (t: Throwable) {
                errors += t
            }
        }
        // 读线程：match 不抛异常；命中时规则 id 必须等于当前写入的 A。
        repeat(3) {
            threads += Thread {
                try {
                    repeat(2000) {
                        val m = MockRuleController.match("GET", "/a")
                        if (m != null) assertEquals("A", m.ruleId)
                    }
                } catch (t: Throwable) {
                    errors += t
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertTrue(
            "并发下不应有异常或越界规则: ${errors.joinToString { it.message ?: it.toString() }}",
            errors.isEmpty(),
        )
    }
}
