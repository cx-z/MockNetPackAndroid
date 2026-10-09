package com.mocknetpack.android.internal.scan

import com.mocknetpack.android.ConnectByScanError
import com.mocknetpack.android.internal.connection.ConnectionClient
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/// ConnectByScanFlow 核心流程验证（probe 重试 / 令牌注册分类 / R1.6 不覆盖地址）。
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectByScanFlowTest {

    private lateinit var server: MockWebServer
    private val recordedRequests = CopyOnWriteArrayList<RecordedRequest>()
    private lateinit var handler: (RecordedRequest) -> MockResponse

    private val appID = "com.example.basedemo"
    private val did = "test-did-0001"
    private val token = "pairing-token-abc"

    // 副作用记录
    private val stops = mutableListOf<String>()
    private val saves = mutableListOf<Pair<String, String>>()
    private val starts = mutableListOf<Triple<String, String, String>>()
    private var results = mutableListOf<ConnectByScanError?>()

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        server = MockWebServer()
        server.start()
        server.setDispatcher(object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                recordedRequests.add(request)
                return handler(request)
            }
        })
        handler = { req ->
            when {
                req.path!!.endsWith("/__mocknetpack_probe__") ->
                    MockResponse().setResponseCode(404)
                req.path!!.endsWith("/devices/register") ->
                    MockResponse().setResponseCode(202).setBody("""{"ok":true}""")
                else -> MockResponse().setResponseCode(404)
            }
        }
        stops.clear()
        saves.clear()
        starts.clear()
        results.clear()
    }

    @After
    fun tearDown() {
        server.shutdown()
        Dispatchers.resetMain()
    }

    private fun baseURL(): String = server.url("/api/v1").toString()

    private fun payload() = QRConnectPayload(
        serverURL = baseURL(),
        appID = appID,
        token = token,
    )

    private fun runFlow(
        probeRetryCount: Int = 4,
        probeRetryDelayMs: Long = 10,
    ) {
        runBlocking {
            ConnectByScanFlow.execute(
                payload = payload(),
                appID = appID,
                did = did,
                appVersion = "1.0",
                appName = "BaseDemo",
                sdkVersion = "0.1.0-SNAPSHOT",
                osVersion = "15",
                clientFactory = { ConnectionClient.create(it) },
                probeRetryCount = probeRetryCount,
                probeRetryDelayMs = probeRetryDelayMs,
                onStop = { stops.add("stop") },
                onSave = { serverURL, app -> saves.add(serverURL to app) },
                onStart = { serverURL, app, d -> starts.add(Triple(serverURL, app, d)) },
                onResult = { results.add(it) },
            )
        }
    }

    private fun count(pathSuffix: String): Int = recordedRequests.count { it.path!!.endsWith(pathSuffix) }

    @Test
    fun `success stops saves starts and reports null`() {
        runFlow()
        assertEquals(1, count("/__mocknetpack_probe__"))
        assertEquals(1, count("/devices/register"))
        assertEquals(listOf("stop"), stops)
        assertEquals(listOf(baseURL() to appID), saves)
        assertEquals(listOf(Triple(baseURL(), appID, did)), starts)
        assertEquals(listOf<ConnectByScanError?>(null), results)
    }

    @Test
    fun `register payload carries contract fields with pairing token`() {
        runFlow()
        val register = recordedRequests.first { it.path!!.endsWith("/devices/register") }
        val body = JSONObject(register.body.readUtf8())
        assertEquals(appID, body.optString("app"))
        assertEquals(did, body.optString("did"))
        assertEquals("android", body.optString("platform"))
        assertEquals("1.0", body.optString("appVersion"))
        assertEquals("BaseDemo", body.optString("appName"))
        assertEquals("0.1.0-SNAPSHOT", body.optString("sdkVersion"))
        assertEquals("15", body.optString("osVersion"))
        assertEquals(token, body.optString("pairingToken"))
    }

    @Test
    fun `403 register maps to token invalid and does not save address`() {
        handler = { req ->
            when {
                req.path!!.endsWith("/__mocknetpack_probe__") -> MockResponse().setResponseCode(404)
                req.path!!.endsWith("/devices/register") ->
                    MockResponse().setResponseCode(403).setBody("""{"error":"pairing_token_invalid"}""")
                else -> MockResponse().setResponseCode(404)
            }
        }
        runFlow()
        assertEquals(listOf<ConnectByScanError?>(ConnectByScanError.TOKEN_INVALID), results)
        assertTrue("令牌无效不得持久化地址（R1.6）", saves.isEmpty())
        assertTrue(starts.isEmpty())
    }

    @Test
    fun `register other failure maps to unreachable and does not save`() {
        handler = { req ->
            when {
                req.path!!.endsWith("/__mocknetpack_probe__") -> MockResponse().setResponseCode(404)
                req.path!!.endsWith("/devices/register") ->
                    MockResponse().setResponseCode(500).setBody("""{"error":"boom"}""")
                else -> MockResponse().setResponseCode(404)
            }
        }
        runFlow()
        assertEquals(listOf<ConnectByScanError?>(ConnectByScanError.UNREACHABLE), results)
        assertTrue(saves.isEmpty())
        assertTrue(starts.isEmpty())
    }

    @Test
    fun `server unreachable fails without register or save`() {
        // 服务器完全不可达：shutdown 后连接被拒绝 → probe 失败 → UNREACHABLE。
        // （重试存在性由 `probe retries then succeeds` 覆盖；此处只验证结果与副作用。）
        server.shutdown()
        runFlow()
        assertEquals(0, count("/devices/register"))
        assertEquals(listOf<ConnectByScanError?>(ConnectByScanError.UNREACHABLE), results)
        assertTrue(saves.isEmpty())
        assertTrue(starts.isEmpty())
    }

    @Test
    fun `probe retries then succeeds and registers`() {
        var probeAttempts = 0
        handler = { req ->
            when {
                req.path!!.endsWith("/__mocknetpack_probe__") -> {
                    probeAttempts++
                    // NO_RESPONSE：读请求后挂起不响应 → 客户端读超时（3s）→ IOException。
                    if (probeAttempts <= 2) MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                    else MockResponse().setResponseCode(404)
                }
                req.path!!.endsWith("/devices/register") ->
                    MockResponse().setResponseCode(202).setBody("""{"ok":true}""")
                else -> MockResponse().setResponseCode(404)
            }
        }
        runFlow(probeRetryCount = 4, probeRetryDelayMs = 10)
        // 前 2 次超时失败，第 3 次 404 连通 → 继续注册成功。
        assertEquals(3, count("/__mocknetpack_probe__"))
        assertEquals(1, count("/devices/register"))
        assertEquals(listOf<ConnectByScanError?>(null), results)
    }
}
