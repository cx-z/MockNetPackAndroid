package com.mocknetpack.android.internal.capture

import java.io.ByteArrayOutputStream
import okhttp3.MediaType
import okhttp3.RequestBody
import okio.Buffer
import okio.BufferedSink
import okio.Okio
import okio.Sink
import okio.Timeout

/**
 * 委托式请求体采集：在实际写出时采样，不会调用 `writeTo(Buffer)` 全量读取并
 * `RequestBody.create(bytes)` 重建，而是把原 body 原样交给 OkHttp，在写出路径上
 * 边写边抄样本（上限 [limit] 字节）。
 *
 * 与旧实现的差别（必须清楚）：
 * - 旧实现：进入拦截器就全量读一遍 body，再用字节重建 —— 大上传（OSS 分片/视频）
 *   会多一份全量内存拷贝，且重建后的 body 丢掉了原始 `Content-Type` 之外的语义
 *   （一次性 body、`isDuplex`、自定义 `contentLength`）。
 * - 新实现：`contentLength()` 沿用原 body，字节与顺序不变；原 body 未知长度时
 *   才自然走 chunked / 流式。定长请求的线上报文形态与未接入 SDK 时一致
 *   （`Content-Length` 仍在，不会因接入抓包被改成 chunked）。
 *
 * 采样语义：
 * - 每次 `writeTo` 视为一次独立发送尝试（OkHttp 重试会重新调用），开始时重置样本，
 *   因此最终样本始终是「最后一次尝试的完整 body（≤ limit）」而不是多份拼接；
 * - 样本只用于展示与 base64 兜底，不参与转发，写路径上绝不会被改写或截断。
 */
internal class TeeRequestBody(
    private val delegate: RequestBody,
    private val limit: Long,
) : RequestBody() {

    private val lock = Any()
    private val sample = ByteArrayOutputStream()
    private var total = 0L

    override fun contentType(): MediaType? = delegate.contentType()

    /// 长度沿用原 body：tee 只旁路抄样本、不改写字节，声称的长度与实际写出一致。
    /// 保留真实长度是为了**不改变线上报文形态**（不因接入抓包而把定长请求变成
    /// chunked）；原 body 长度未知（-1）时才自然走 chunked / 流式。
    override fun contentLength(): Long = delegate.contentLength()

    override fun writeTo(sink: BufferedSink) {
        synchronized(lock) {
            sample.reset()
            total = 0
        }
        // 原 body 写到"抄写 sink"上：字节从这里流过，不在这里停留。
        val tee = Okio.buffer(TeeSink(sink, this))
        delegate.writeTo(tee)
        // 把内层缓冲全部推到 tee（进而写到 OkHttp 的 sink），保证 body 字节先于
        // 后续写入（chunk 终止符等）到达，顺序与未包装时一致。
        tee.emit()
    }

    /// 已观测样本（≤ limit）。
    internal fun recordedBytes(): ByteArray = synchronized(lock) { sample.toByteArray() }

    /// 已观测总字节数（未截断）。
    internal fun recordedSize(): Long = synchronized(lock) { total }

    /// 抄写：`source` 中待发送的前 `byteCount` 字节同时进样本缓冲。
    private fun capture(source: Buffer, byteCount: Long) {
        synchronized(lock) {
            total += byteCount
            val room = limit - sample.size()
            if (room <= 0 || byteCount <= 0) return
            val copy = minOf(room, byteCount)
            val staging = Buffer()
            source.copyTo(staging, 0, copy)
            sample.write(staging.readByteArray())
        }
    }

    /// Sink 侧切面：先抄样本，再把原字节交给下游 sink（不消费、不改写）。
    private class TeeSink(
        private val delegate: Sink,
        private val owner: TeeRequestBody,
    ) : Sink {
        override fun write(source: Buffer, byteCount: Long) {
            owner.capture(source, byteCount)
            delegate.write(source, byteCount)
        }

        override fun flush() = delegate.flush()

        override fun timeout(): Timeout = delegate.timeout()

        override fun close() = delegate.close()
    }
}
