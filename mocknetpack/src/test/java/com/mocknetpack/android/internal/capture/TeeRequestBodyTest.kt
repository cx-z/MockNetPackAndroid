package com.mocknetpack.android.internal.capture

import okhttp3.MediaType
import okhttp3.RequestBody
import okio.Buffer
import okio.BufferedSink
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/// TeeRequestBody：委托式请求体采集。
/// 覆盖 文本 / 一次性 body / 重试（writeTo 多次）/ 二进制大 body / 超限截断。
class TeeRequestBodyTest {

    private val json = MediaType.parse("application/json")!!

    private fun body(bytes: ByteArray): RequestBody =
        object : RequestBody() {
            override fun contentType() = json
            override fun contentLength() = bytes.size.toLong()
            override fun writeTo(sink: BufferedSink) {
                sink.write(bytes)
            }
        }

    @Test
    fun `tees text body without changing bytes and preserves fixed length`() {
        val payload = """{"page":1,"q":"x"}""".toByteArray()
        val tee = TeeRequestBody(body(payload), limit = 1024)

        // 长度沿用原 body：线上报文形态不变（Content-Length 仍在）。
        assertEquals(payload.size.toLong(), tee.contentLength())

        val out = Buffer()
        tee.writeTo(out)
        assertEquals(payload.size, out.size().toInt())
        assertEquals(String(payload), out.readUtf8())
        assertEquals(payload.size.toLong(), tee.recordedSize())
        assertEquals(String(payload), String(tee.recordedBytes()))
    }

    @Test
    fun `records the single send without buffering ahead`() {
        val payload = "once".toByteArray()
        val tee = TeeRequestBody(body(payload), limit = 1024)

        val out = Buffer()
        tee.writeTo(out)
        assertEquals("once", out.readUtf8())
        assertEquals("once", String(tee.recordedBytes()))
        assertEquals(4L, tee.recordedSize())
    }

    @Test
    fun `retry does not duplicate the recorded sample`() {
        val payload = """{"retry":true}""".toByteArray()
        val tee = TeeRequestBody(body(payload), limit = 1024)

        // OkHttp 重试会再次调用 writeTo：样本按"最后一次发送"重置，不累加。
        tee.writeTo(Buffer())
        val second = Buffer()
        tee.writeTo(second)

        assertEquals(payload.size, second.size().toInt())
        assertEquals(payload.size.toLong(), tee.recordedSize())
        assertEquals(String(payload), String(tee.recordedBytes()))
    }

    @Test
    fun `caps binary sample at limit while forwarding every byte`() {
        val payload = ByteArray(2 * 1024 * 1024) { 0xAB.toByte() }
        val limit = 1024L * 1024
        val tee = TeeRequestBody(body(payload), limit)

        val out = Buffer()
        tee.writeTo(out)
        // 转发不截断：下游收到完整字节。
        assertEquals(payload.size.toLong(), out.size())
        assertEquals(0xAB.toByte(), out.readByte())
        // 采样截断：只留 limit，总大小仍按真实字节统计。
        // 总大小按真实字节统计，样本单独截断到 limit。
        assertEquals(payload.size.toLong(), tee.recordedSize())
        assertEquals(limit.toInt(), tee.recordedBytes().size)
    }

    @Test
    fun `truncated sample keeps original total for placeholder arithmetic`() {
        val payload = "y".repeat(1_500_000).toByteArray()
        val tee = TeeRequestBody(body(payload), limit = 1024L * 1024)

        tee.writeTo(Buffer())

        assertEquals(payload.size.toLong(), tee.recordedSize())
        assertEquals(1024 * 1024, tee.recordedBytes().size)
        // 记录层据此产出 "[truncated N bytes] ..." 前缀（N 为真实总长）。
        val parts = HttpExchangeRecorder.requestBodyParts(
            sample = tee.recordedBytes(),
            fullSize = tee.recordedSize(),
            unavailable = false,
        )
        assertTrue(parts.first.startsWith("[truncated ${payload.size} bytes] "))
        assertFalse(parts.second != null)
    }
    @Test
    fun `captures the exact prefix when an Okio segment leaves a trailing byte`() {
        val payload = ByteArray(1_048_577) { (it % 127).toByte() }
        val tee = TeeRequestBody(body(payload), limit = 1_048_576L)
        val downstream = Buffer()
        tee.writeTo(downstream)

        assertArrayEquals(payload, downstream.readByteArray())
        assertArrayEquals(payload.copyOf(1_048_576), tee.recordedBytes())
        assertEquals(payload.size.toLong(), tee.recordedSize())
    }
}
