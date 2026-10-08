package com.mocknetpack.android.internal.connection

import com.mocknetpack.android.ConnectionState
import com.mocknetpack.android.SessionState
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/// ConnectionController 状态机验证（MockWebServer 桩 + 加速退避/心跳）。
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionControllerTest {

    private lateinit var server: MockWebServer
    private lateinit var controller: ConnectionController

    private val appID = "com.example.basedemo"
    private val externalDID = "test-did-0001"
    /// MockWebServer dispatch 线程写入、测试线程读取：需线程安全容器。
    private val recordedRequests = java.util.concurrent.CopyOnWriteArrayList<RecordedRequest>()

    /// 服务端行为闭包（按请求 path 分发；测试中可替换）。
    private lateinit var handler: (RecordedRequest) -> MockResponse

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        server = MockWebServer()
        server.start()
        controller = newController()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                recordedRequests.add(request)
                return handler(request)
            }
        }
        handler = { MockResponse().setResponseCode(404) }
    }

    @After
    fun tearDown() {
        controller.stop()
        server.shutdown()
        Dispatchers.resetMain()
    }

    private fun newController(): ConnectionController {
        val c = ConnectionController()
        c.backoffBaseSeconds = 0.05
        c.heartbeatIntervalOverrideSeconds = 0.1
        c.schedulerDispatcher = Dispatchers.Unconfined
        c.clientFactory = { ConnectionClient.create(it) }
        return c
    }

    private fun baseURL(): String = server.url("/api/v1").toString()

    private fun startController(pairingToken: String? = null) {
        controller.start(
            server = baseURL(),
            appID = appID,
            externalDID = externalDID,
            appVersion = "1.0",
            appName = "BaseDemo",
            sdkVersion = "0.1.0-SNAPSHOT",
            osVersion = "15",
            pairingToken = pairingToken,
        )
    }

    private fun registerResponse(interval: Int = 3): MockResponse {
        return MockResponse().setResponseCode(200).setBody(
            """{"device":{"app":"$appID","did":"$externalDID","status":"idle"},"serverConfig":{"heartbeatIntervalSeconds":$interval,"heartbeatTimeoutSeconds":60}}""",
        )
    }

    private fun heartbeatResponse(session: String? = null, interval: Int = 3): MockResponse {
        val sessionJson = session?.let {
            """{"id":"$it","app":"$appID","did":"$externalDID","status":"capturing","startedAt":"2026-10-08T00:00:00Z","endedAt":null,"requestCount":0,"viewerCount":0}"""
        } ?: "null"
        return MockResponse().setResponseCode(200).setBody(
            """{"ok":true,"serverTime":"2026-10-08T00:00:00Z","serverConfig":{"heartbeatIntervalSeconds":$interval,"heartbeatTimeoutSeconds":60},"session":$sessionJson,"rulesVersion":0}""",
        )
    }

    private fun waitUntil(timeoutMs: Long = 5000, condition: () -> Boolean) {
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

    // MARK: - 注册 → 心跳

    @Test
    fun `start registers then heartbeats and reaches connected idle`() {
        handler = { req ->
            if (req.path!!.endsWith("/devices/register")) registerResponse() else heartbeatResponse()
        }
        startController()

        waitUntil { countRequests("/devices/register") >= 1 && countRequests("/heartbeat") >= 1 }

        // 注册载荷字段（platform=android 等）。
        val reg = lastRequest("/devices/register")!!
        val body = org.json.JSONObject(reg.body.readUtf8())
        assertEquals(appID, body.optString("app"))
        assertEquals(externalDID, body.optString("did"))
        assertEquals("android", body.optString("platform"))
        assertEquals("15", body.optString("osVersion"))
        assertEquals("0.1.0-SNAPSHOT", body.optString("sdkVersion"))
        assertEquals("1.0", body.optString("appVersion"))
        assertEquals("BaseDemo", body.optString("appName"))

        // 心跳路径带 app/did。
        val hb = lastRequest("/heartbeat")!!
        assertTrue("heartbeat path should contain appID", hb.path!!.contains(appID))
        assertTrue("heartbeat path should contain did", hb.path!!.contains(externalDID))

        waitUntil { controller.connectionState == ConnectionState.CONNECTED }
        assertEquals(SessionState.IDLE, controller.sessionState)
        assertTrue(controller.isRunning)
        assertEquals(externalDID, controller.did)
        assertEquals(appID, controller.resolvedAppID)
    }

    // MARK: - 会话状态推导 + 回调

    @Test
    fun `heartbeat with session sets capturing and callback`() {
        var hasSession = true
        handler = { req ->
            if (req.path!!.endsWith("/devices/register")) registerResponse()
            else heartbeatResponse(if (hasSession) "sess-1" else null)
        }
        val sessionStates = mutableListOf<SessionState>()
        controller.onSessionStateChange = { sessionStates.add(it) }
        startController()

        waitUntil { controller.sessionState == SessionState.CAPTURING }
        assertTrue("callback should have delivered CAPTURING, got $sessionStates", sessionStates.contains(SessionState.CAPTURING))

        // 服务端会话结束 → 心跳返回无 session → 回到 idle。
        hasSession = false
        waitUntil { controller.sessionState == SessionState.IDLE }
        assertTrue(sessionStates.contains(SessionState.IDLE))
    }

    @Test
    fun `connection state callbacks fire via main dispatcher`() {
        handler = { req ->
            if (req.path!!.endsWith("/devices/register")) registerResponse() else heartbeatResponse()
        }
        val connStates = mutableListOf<ConnectionState>()
        controller.onConnectionStateChange = { connStates.add(it) }
        startController()

        waitUntil { controller.connectionState == ConnectionState.CONNECTED }
        assertTrue("should see CONNECTING, got $connStates", connStates.contains(ConnectionState.CONNECTING))
        assertTrue("should see CONNECTED, got $connStates", connStates.contains(ConnectionState.CONNECTED))
    }

    // MARK: - 404 静默停

    @Test
    fun `heartbeat 404 stops quietly without retrying`() {
        var failNextHeartbeat = true
        handler = { req ->
            if (req.path!!.endsWith("/devices/register")) registerResponse()
            else if (failNextHeartbeat) {
                failNextHeartbeat = false
                MockResponse().setResponseCode(404).setBody("""{"error":"device_not_registered"}""")
            } else heartbeatResponse()
        }
        startController()

        waitUntil { countRequests("/devices/register") >= 1 && countRequests("/heartbeat") >= 1 }
        waitUntil { controller.connectionState == ConnectionState.OFFLINE }

        // 冻结计数：404 后不再产生新 register/heartbeat。
        val frozenReg = countRequests("/devices/register")
        val frozenHb = countRequests("/heartbeat")
        Thread.sleep(400)
        assertEquals(frozenReg, countRequests("/devices/register"))
        assertEquals(frozenHb, countRequests("/heartbeat"))
    }

    @Test
    fun `register 404 stops quietly without retrying`() {
        handler = { MockResponse().setResponseCode(404).setBody("""{"error":"device_not_registered"}""") }
        startController()

        waitUntil { controller.connectionState == ConnectionState.OFFLINE }

        val frozenReg = countRequests("/devices/register")
        Thread.sleep(400)
        assertEquals(frozenReg, countRequests("/devices/register"))
        assertEquals(0, countRequests("/heartbeat"))
    }

    // MARK: - 退避重连

    @Test
    fun `network failure backs off and reconnects when server recovers`() {
        var failHeartbeat = true
        handler = { req ->
            if (req.path!!.endsWith("/devices/register")) registerResponse()
            else if (failHeartbeat) MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START)
            else heartbeatResponse()
        }
        startController()

        waitUntil { controller.connectionState == ConnectionState.OFFLINE && countRequests("/heartbeat") >= 2 }

        // 网络恢复 → 重连成功。
        failHeartbeat = false
        waitUntil { controller.connectionState == ConnectionState.CONNECTED }
        waitUntil { controller.sessionState == SessionState.IDLE }
    }

    @Test
    fun `register 403 without pairing token keeps backoff retrying`() {
        handler = { MockResponse().setResponseCode(403).setBody("""{"error":"forbidden"}""") }
        startController()

        waitUntil { controller.connectionState == ConnectionState.OFFLINE && countRequests("/devices/register") >= 2 }
        assertNotEquals(ConnectionState.TOKEN_INVALID, controller.connectionState)
    }

    // MARK: - 403 + 配对令牌 → TOKEN_INVALID

    @Test
    fun `register 403 with pairing token stops and surfaces token invalid`() {
        handler = { MockResponse().setResponseCode(403).setBody("""{"error":"pairing_token_invalid","message":"token expired"}""") }
        startController(pairingToken = "stale-token")

        waitUntil { controller.connectionState == ConnectionState.TOKEN_INVALID }

        val frozenReg = countRequests("/devices/register")
        Thread.sleep(400)
        assertEquals(frozenReg, countRequests("/devices/register"))
        assertEquals(0, countRequests("/heartbeat"))
    }

    // MARK: - stop / 代次

    @Test
    fun `stop stops heartbeating and clears state`() {
        handler = { req ->
            if (req.path!!.endsWith("/devices/register")) registerResponse() else heartbeatResponse()
        }
        startController()
        // 等第 2 次心跳的 performCycle 同步段完成（CONNECTED 已设置），消除在途竞态。
        waitUntil { countRequests("/heartbeat") >= 2 && controller.connectionState == ConnectionState.CONNECTED }

        controller.stop()
        assertFalse(controller.isRunning)
        assertEquals(ConnectionState.OFFLINE, controller.connectionState)
        assertEquals(SessionState.IDLE, controller.sessionState)

        val frozen = countRequests("/heartbeat")
        Thread.sleep(400)
        assertEquals(frozen, countRequests("/heartbeat"))
    }

    /// 4.7 回归：stop() 时心跳仍在途，旧心跳结果返回后不得把连接状态"翻活"
    /// （不得置 connected/capturing，也不得继续调度新循环）。
    @Test
    fun `stop discards in-flight heartbeat result`() {
        val gate = CountDownLatch(1)
        var firstHeartbeatBlocked = false
        handler = { req ->
            if (req.path!!.endsWith("/devices/register")) registerResponse()
            else {
                if (!firstHeartbeatBlocked) {
                    firstHeartbeatBlocked = true
                    gate.await(2, TimeUnit.SECONDS) // 在途心跳：挂起直到 stop 后放行
                }
                heartbeatResponse("sess-late")
            }
        }
        startController()

        waitUntil { countRequests("/heartbeat") >= 1 }

        controller.stop()
        assertEquals(ConnectionState.OFFLINE, controller.connectionState)

        gate.countDown() // 放行旧心跳 → 返回"成功 + 会话"，旧代结果必须被丢弃。
        Thread.sleep(400)
        assertEquals(ConnectionState.OFFLINE, controller.connectionState)
        assertEquals(SessionState.IDLE, controller.sessionState)
        assertFalse(controller.isRunning)

        val frozen = countRequests("/heartbeat")
        Thread.sleep(300)
        assertEquals(frozen, countRequests("/heartbeat"))
    }

    // MARK: - 心跳间隔钳制

    @Test
    fun `heartbeat interval clamps server config to 2 and 300`() {
        var interval = 1
        handler = { req ->
            if (req.path!!.endsWith("/devices/register")) registerResponse(interval = interval)
            else heartbeatResponse(interval = interval)
        }
        controller.heartbeatIntervalOverrideSeconds = null
        startController()
        waitUntil { controller.connectionState == ConnectionState.CONNECTED && countRequests("/heartbeat") >= 1 }
        // 服务端下发 1s（异常误配）→ 钳制下限 2s。
        assertEquals(2, controller.effectiveHeartbeatIntervalSeconds())

        controller.stop()

        // 第二个实例：服务端下发 500s → 钳制上限 300s。
        controller = newController()
        controller.heartbeatIntervalOverrideSeconds = null
        interval = 500
        startController()
        waitUntil { controller.connectionState == ConnectionState.CONNECTED && countRequests("/heartbeat") >= 1 }
        assertEquals(300, controller.effectiveHeartbeatIntervalSeconds())
    }

    @Test
    fun `heartbeat interval allows capturing 3s without clamping to 5`() {
        controller.heartbeatIntervalOverrideSeconds = null
        handler = { req ->
            if (req.path!!.endsWith("/devices/register")) registerResponse(interval = 3)
            else heartbeatResponse(interval = 3)
        }
        startController()
        waitUntil { controller.connectionState == ConnectionState.CONNECTED && countRequests("/heartbeat") >= 1 }
        // 契约 capturing 3s 必须原样放行（旧钳制下限 5 会失效）。
        assertEquals(3, controller.effectiveHeartbeatIntervalSeconds())
    }

    /// 真实节奏：注册后按服务端下发 2s 间隔心跳（钳制下限值）。
    @Test
    fun `heartbeat follows server config interval`() {
        controller.heartbeatIntervalOverrideSeconds = null
        handler = { req ->
            if (req.path!!.endsWith("/devices/register")) registerResponse(interval = 2)
            else heartbeatResponse()
        }
        startController()

        waitUntil { countRequests("/heartbeat") >= 1 }
        val t1 = System.currentTimeMillis()
        waitUntil(timeoutMs = 6000) { countRequests("/heartbeat") >= 2 }
        val gap = System.currentTimeMillis() - t1
        assertTrue("heartbeat gap $gap ms should follow ~2s interval", gap >= 1500)
        assertTrue("heartbeat gap $gap ms should not exceed ~2s", gap < 3500)
    }

    // MARK: - 无服务器启动

    @Test
    fun `startUnconfigured sets unconfigured without network`() {
        controller.startUnconfigured(
            appID = appID,
            externalDID = externalDID,
            appVersion = "1.0",
            appName = null,
            sdkVersion = "0.1.0-SNAPSHOT",
            osVersion = "15",
        )
        assertEquals(ConnectionState.UNCONFIGURED, controller.connectionState)
        assertTrue(controller.isRunning)
        assertEquals(externalDID, controller.did)
        Thread.sleep(200)
        assertEquals(0, server.requestCount)
    }
}
