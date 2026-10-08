package com.mocknetpack.android.internal.connection

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/// ConnectionClient 契约验证（MockWebServer 桩）。
class ConnectionClientTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    /// MockServer 桩地址（形如 http://localhost:PORT/api/v1）。
    private fun baseURL(): String = server.url("/api/v1").toString()

    @Test
    fun `post sends path headers and json body`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true}"""))
        val client = ConnectionClient.create(baseURL())
        val body = JSONObject().put("app", "com.example.basedemo").put("did", "d1")
        val resp = client.post("devices/register", body)

        assertEquals(true, resp.optBoolean("ok"))

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/api/v1/devices/register", recorded.path)
        // OkHttp 会自动为 JSON body 追加 charset（application/json; charset=utf-8）。
        assertTrue("Content-Type should be JSON, got ${recorded.getHeader("Content-Type")}", recorded.getHeader("Content-Type")!!.startsWith("application/json"))
        assertEquals("1", recorded.getHeader("X-MockNetPack-Skip"))
        val reqBody = JSONObject(recorded.body.readUtf8())
        assertEquals("com.example.basedemo", reqBody.optString("app"))
        assertEquals("d1", reqBody.optString("did"))
    }

    @Test
    fun `get sends path and skip header`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"version":7}"""))
        val client = ConnectionClient.create(baseURL())
        val resp = client.get("devices/com.example.basedemo/d1/mock-rules")

        assertEquals(7, resp.optInt("version"))

        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        assertEquals("/api/v1/devices/com.example.basedemo/d1/mock-rules", recorded.path)
        assertEquals("1", recorded.getHeader("X-MockNetPack-Skip"))
    }

    @Test
    fun `non-2xx throws HttpStatus with code`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":"device_not_registered"}"""))
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":"pairing_token_invalid"}"""))
        val client = ConnectionClient.create(baseURL())

        try {
            client.post("devices/register", JSONObject())
            fail("expected HttpStatus 404")
        } catch (e: ConnectionClientError.HttpStatus) {
            assertEquals(404, e.code)
        }
        try {
            client.post("devices/register", JSONObject())
            fail("expected HttpStatus 403")
        } catch (e: ConnectionClientError.HttpStatus) {
            assertEquals(403, e.code)
        }
    }

    @Test
    fun `probe treats any http response including 404 as reachable`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404).setBody("not found"))
        val client = ConnectionClient.create(baseURL())

        client.probe() // 不应抛（404 即连通）

        val recorded = server.takeRequest()
        assertTrue("probe path should hit __mocknetpack_probe__", recorded.path!!.endsWith("__mocknetpack_probe__"))
        assertEquals("1", recorded.getHeader("X-MockNetPack-Skip"))
    }

    @Test
    fun `create appends api v1 to bare origin`() = runBlocking {
        // 无路径的 origin（http://localhost:PORT）→ create 自动补 /api/v1。
        server.enqueue(MockResponse().setResponseCode(404))
        val client = ConnectionClient.create(server.url("/").toString().trimEnd('/'))
        client.probe()
        val recorded = server.takeRequest()
        assertTrue("bare origin should get /api/v1 prefix, got ${recorded.path}", recorded.path!!.startsWith("/api/v1/"))
    }

    @Test
    fun `create rejects invalid urls`() {
        try {
            ConnectionClient.create("ftp://192.168.1.10:4290")
            fail("expected InvalidURL")
        } catch (e: ConnectionClientError.InvalidURL) {
            // expected
        }
        try {
            ConnectionClient.create("not a url")
            fail("expected InvalidURL")
        } catch (e: ConnectionClientError.InvalidURL) {
            // expected
        }
        try {
            ConnectionClient.create("http://")
            fail("expected InvalidURL")
        } catch (e: ConnectionClientError.InvalidURL) {
            // expected
        }
    }
}
