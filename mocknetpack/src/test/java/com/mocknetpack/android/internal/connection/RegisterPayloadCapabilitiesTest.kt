package com.mocknetpack.android.internal.connection

import com.mocknetpack.android.RegisterDeviceRequest
import com.mocknetpack.android.SdkCapabilities
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 注册载荷的 capabilities（能力声明）序列化。
 *
 * 契约（RegisterDeviceRequest.capabilities）：
 * - adapters / instrumented / requestBodyTee 三个字段；
 * - 老行为不传 capabilities 时整段字段不出现（不能给服务端塞空对象，
 *   否则会把"未知"误标成"未插桩"）。
 */
class RegisterPayloadCapabilitiesTest {

    private fun request(caps: SdkCapabilities?): JSONObject = RegisterDeviceRequest(
        app = "com.example.app",
        did = "did-1",
        platform = "android",
        osVersion = "14",
        sdkVersion = "1.0.1",
        appVersion = "1.0.0",
        capabilities = caps,
    ).toJson()

    @Test
    fun `capabilities are serialized with adapters and flags`() {
        val json = request(
            SdkCapabilities(
                adapters = listOf("okhttp", "httpurlconnection", "business"),
                instrumented = true,
            )
        )
        val caps = json.getJSONObject("capabilities")
        assertEquals(3, caps.getJSONArray("adapters").length())
        assertEquals("okhttp", caps.getJSONArray("adapters").getString(0))
        assertTrue(caps.getBoolean("instrumented"))
        assertTrue(caps.getBoolean("requestBodyTee"))
    }

    @Test
    fun `instrumented false is reported explicitly`() {
        val json = request(SdkCapabilities(adapters = listOf("okhttp"), instrumented = false))
        assertFalse(json.getJSONObject("capabilities").getBoolean("instrumented"))
    }

    @Test
    fun `absent capabilities are omitted from the payload`() {
        val json = request(null)
        assertFalse(json.has("capabilities"))
        // 基础字段不受影响
        assertEquals("android", json.getString("platform"))
        assertEquals("1.0.1", json.getString("sdkVersion"))
    }
}
