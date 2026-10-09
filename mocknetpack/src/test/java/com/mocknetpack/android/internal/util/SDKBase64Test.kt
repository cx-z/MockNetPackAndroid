package com.mocknetpack.android.internal.util

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SDKBase64Test {

    @Test
    fun `valid bytes round-trip through encode then decode`() {
        val raw = byteArrayOf(0x01, 0x02, 0x7f, 0x00, 'A'.code.toByte())
        val encoded = SDKBase64.encode(raw)
        val decoded = SDKBase64.decode(encoded)
        assertArrayEquals(raw, decoded)
    }

    @Test
    fun `malformed input decodes to null instead of throwing`() {
        // 畸形 base64：android 路径抛 IllegalArgumentException，JVM 回退路径
        // java.util.Base64 也抛 IllegalArgumentException；都必须兜住返回 null。
        assertNull(SDKBase64.decode("!!!not-base64!!!"))
    }

    @Test
    fun `empty string decodes to empty bytes`() {
        assertArrayEquals(ByteArray(0), SDKBase64.decode(""))
    }
}
