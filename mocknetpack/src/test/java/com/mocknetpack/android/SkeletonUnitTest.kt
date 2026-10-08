package com.mocknetpack.android

import com.mocknetpack.android.internal.codec.BinaryCodec
import com.mocknetpack.android.internal.scan.QRConnectParseError
import com.mocknetpack.android.internal.scan.QRConnectParser
import com.mocknetpack.android.internal.scan.QRConnectResult
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/// 骨架阶段单测：已实现的纯函数单元（QR 协议解析、gzip 编解码）。
class SkeletonUnitTest {

    // MARK: - QRConnectParser（从 iOS 移植）

    private fun encodeBase64Url(raw: String): String =
        java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(raw.toByteArray())

    @Test
    fun `parse valid payload`() {
        val server = "http://192.168.1.10:4290/api/v1"
        val raw = "mocknetpack://connect?v=1&u=${encodeBase64Url(server)}&a=com.example.basedemo&t=tok123"
        val result = QRConnectParser.parse(raw, expectedAppID = "com.example.basedemo")
        assertTrue(result is QRConnectResult.Success)
        val payload = (result as QRConnectResult.Success).payload
        assertEquals(server, payload.serverURL)
        assertEquals("com.example.basedemo", payload.appID)
        assertEquals("tok123", payload.token)
    }

    @Test
    fun `parse appID mismatch`() {
        val server = "http://192.168.1.10:4290/api/v1"
        val raw = "mocknetpack://connect?v=1&u=${encodeBase64Url(server)}&a=com.other.app&t=tok123"
        val result = QRConnectParser.parse(raw, expectedAppID = "com.example.basedemo")
        assertTrue(result is QRConnectResult.Failure)
        assertEquals(QRConnectParseError.AppMismatch, (result as QRConnectResult.Failure).error)
    }

    @Test
    fun `parse invalid payload`() {
        assertEquals(
            QRConnectParseError.NotMockNetPack,
            (QRConnectParser.parse("https://not.mocknetpack/x", "com.a") as QRConnectResult.Failure).error,
        )
        assertEquals(
            QRConnectParseError.UnsupportedVersion,
            (QRConnectParser.parse("mocknetpack://connect?v=2&u=eA&a=com.a&t=t", "com.a") as QRConnectResult.Failure).error,
        )
        assertEquals(
            QRConnectParseError.InvalidServer,
            (QRConnectParser.parse("mocknetpack://connect?v=1&u=!!!&a=com.a&t=t", "com.a") as QRConnectResult.Failure).error,
        )
        assertEquals(
            QRConnectParseError.MissingToken,
            (QRConnectParser.parse(
                "mocknetpack://connect?v=1&u=${encodeBase64Url("http://localhost:4290/api/v1")}&a=com.a",
                "com.a",
            ) as QRConnectResult.Failure).error,
        )
    }

    // MARK: - BinaryCodec gzip（对齐 iOS zlib 标准 gzip 输出）

    @Test
    fun `gzip roundtrip`() {
        val raw = "{\"hello\":\"world\"}".toByteArray()
        val gz = BinaryCodec.gzipCompressed(raw)!!
        // 标准 gzip 头（1f 8b）
        assertArrayEquals(byteArrayOf(0x1f, 0x8b.toByte()), gz.copyOfRange(0, 2))
        val out = GZIPInputStream(ByteArrayInputStream(gz)).readBytes()
        assertArrayEquals(raw, out)
    }

    @Test
    fun `gzip empty returns null`() {
        assertNull(BinaryCodec.gzipCompressed(ByteArray(0)))
    }
}
