package com.mocknetpack.android.internal.capture

import com.mocknetpack.android.MockNetPack
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * HttpURLConnection 采集包装器（AN-10 M2）。
 *
 * 覆盖范围：业务代码显式 `openConnection()` 得到的连接，接入点在
 * [MockNetPack.wrap]（同两行接入：`conn = MockNetPack.wrap(conn)` 后再设置请求属性）。
 *
 * 非侵入式设计（必须满足，否则会破坏上传进度/流式语义）：
 * - 只在 `http/https` 且 SDK 正在接管（有效会话或启动暂存）时返回包装实例；
 *   其余情况原样返回，业务拿到的仍是原生连接（fail-open）。
 * - 请求体：包装 `getOutputStream()`，边写边抄样本，**不缓冲全量、不重放**；
 *   写方看到的仍是原始流，`Content-Length` / `setFixedLengthStreamingMode`
 *   语义完全由底层连接决定。
 * - 响应体：包装 `getInputStream()` / `getErrorStream()`，边读边抄样本（上限
 *   [MockNetPack.BODY_LIMIT_BYTES]），到 EOF 或 close 时落一条记录；不消费、
 *   不替换字节，调用方读到的内容与未包装时逐字节一致。
 * - 只在**首个终止事件**（EOF / close / disconnect / 连接失败）落一条记录，
 *   重复调用不会重复上报。
 *
 * 已知边界（写入 knowledge/android-http-adapters.md）：
 * - 连接成功但调用方既不读 body 也不 disconnect（例如只读 header 后忘记关闭）
 *   时，记录要等到下一次可观测的终止事件，存在延迟；
 * - 读取中途 close 时 `fullSize` 等于已观测字节数（不是原始总长），
 *   展示上不承诺"完整响应体大小"；
 * - 包装后实例类型是 `HttpURLConnection` 子类，业务侧 `is HttpsURLConnection`
 *   判断会失效（本 SDK 不鼓励这种判断）。
 */
internal object UrlConnectionCapture {

    /** `wrap()` 入口：可接管时返回采集包装，否则原样返回（fail-open）。 */
    fun wrap(connection: HttpURLConnection, adapter: String? = null): HttpURLConnection {
        val generation = TrafficCaptureController.httpCaptureGeneration ?: return connection
        val scheme = connection.url?.protocol?.lowercase() ?: return connection
        if (scheme != "http" && scheme != "https") return connection
        // SDK 自身请求（注册/心跳/上传，理论上不走 HUC）标记跳过，避免自采自。
        if (connection.getRequestProperty(MockNetPack.SKIP_HEADER) != null) return connection
        return CaptureHttpURLConnection(
            delegate = connection,
            generation = generation,
            adapter = adapter?.takeIf { it.isNotBlank() }
                ?: HttpExchangeRecorder.ADAPTER_HTTP_URL_CONNECTION,
        )
    }
}

/**
 * 采集用 HttpURLConnection 包装：把 URLConnection 的全部状态读写转发给 delegate，
 * 只在请求体输出流与响应体输入流上挂样本抄写。
 */
private class CaptureHttpURLConnection(
    private val delegate: HttpURLConnection,
    private val generation: Long,
    private val adapter: String,
) : HttpURLConnection(delegate.url) {

    private val startNs = System.nanoTime()
    private val recorded = AtomicBoolean(false)

    private val requestSample = ByteArrayOutputStream()
    private val requestLock = Any()
    private var requestBytes = 0L

    private val responseSample = ByteArrayOutputStream()
    private val responseLock = Any()
    private var responseBytes = 0L

    /// 请求头必须在连接建立前快照：JDK 的 `getRequestProperties()` 在已连接后抛
    /// IllegalStateException，等到落库时再读会拿到空字典（历史"请求头为空"的同款坑）。
    private var requestHeaders: Map<String, List<String>>? = null

    private var outputStream: OutputStream? = null
    private var inputStream: InputStream? = null
    private var errorStream: InputStream? = null

    // MARK: - URLConnection 状态转发

    override fun connect() {
        snapshotRequestHeaders()
        try {
            delegate.connect()
        } catch (e: IOException) {
            recordError(e)
            throw e
        }
    }

    override fun disconnect() {
        // 调用方在读完/放弃时断开：先把已观测到的一半落库，再断开底层连接。
        record(null)
        delegate.disconnect()
    }

    override fun usingProxy(): Boolean = delegate.usingProxy()

    /// 连接前快照请求头（幂等）。
    private fun snapshotRequestHeaders() {
        if (requestHeaders != null) return
        requestHeaders = try {
            delegate.requestProperties?.mapValues { (_, values) -> values?.filterNotNull() ?: emptyList() }
        } catch (_: Exception) {
            null
        }
    }

    override fun getHeaderField(name: String?): String? = delegate.getHeaderField(name)
    override fun getHeaderField(pos: Int): String? = delegate.getHeaderField(pos)
    override fun getHeaderFieldKey(pos: Int): String? = delegate.getHeaderFieldKey(pos)
    override fun getHeaderFields(): Map<String, List<String>> = delegate.headerFields

    override fun getResponseCode(): Int = try {
        snapshotRequestHeaders()
        delegate.responseCode
    } catch (e: IOException) {
        recordError(e)
        throw e
    }

    override fun getResponseMessage(): String? = delegate.responseMessage

    override fun getRequestMethod(): String = delegate.requestMethod
    override fun setRequestMethod(method: String?) = delegate.setRequestMethod(method)

    override fun getInstanceFollowRedirects(): Boolean = delegate.instanceFollowRedirects
    override fun setInstanceFollowRedirects(follow: Boolean) = delegate.setInstanceFollowRedirects(follow)

    override fun getContentType(): String? = delegate.contentType
    override fun getContentLength(): Int = delegate.contentLength
    override fun getContentLengthLong(): Long = delegate.contentLengthLong
    override fun getContentEncoding(): String? = delegate.contentEncoding

    override fun getRequestProperty(key: String?): String? = delegate.getRequestProperty(key)
    override fun setRequestProperty(key: String?, value: String?) = delegate.setRequestProperty(key, value)
    override fun addRequestProperty(key: String?, value: String?) = delegate.addRequestProperty(key, value)
    override fun getRequestProperties(): Map<String, List<String>> = delegate.requestProperties

    override fun setDoInput(doInput: Boolean) = delegate.setDoInput(doInput)
    override fun getDoInput(): Boolean = delegate.doInput
    override fun setDoOutput(doOutput: Boolean) = delegate.setDoOutput(doOutput)
    override fun getDoOutput(): Boolean = delegate.doOutput
    override fun setUseCaches(useCaches: Boolean) = delegate.setUseCaches(useCaches)
    override fun getUseCaches(): Boolean = delegate.useCaches
    override fun setIfModifiedSince(ifModifiedSince: Long) = delegate.setIfModifiedSince(ifModifiedSince)
    override fun getIfModifiedSince(): Long = delegate.ifModifiedSince
    override fun setConnectTimeout(timeout: Int) {
        delegate.connectTimeout = timeout
    }

    override fun getConnectTimeout(): Int = delegate.connectTimeout

    override fun setReadTimeout(timeout: Int) {
        delegate.readTimeout = timeout
    }

    override fun getReadTimeout(): Int = delegate.readTimeout

    override fun setFixedLengthStreamingMode(contentLength: Int) = delegate.setFixedLengthStreamingMode(contentLength)
    override fun setFixedLengthStreamingMode(contentLength: Long) = delegate.setFixedLengthStreamingMode(contentLength)
    override fun setChunkedStreamingMode(chunklen: Int) = delegate.setChunkedStreamingMode(chunklen)

    // MARK: - 请求体 / 响应体采样

    override fun getOutputStream(): OutputStream {
        outputStream?.let { return it }
        snapshotRequestHeaders()
        val stream = try {
            delegate.outputStream
        } catch (e: IOException) {
            recordError(e)
            throw e
        }
        val wrapped = SampleOutputStream(stream)
        outputStream = wrapped
        return wrapped
    }

    override fun getInputStream(): InputStream {
        inputStream?.let { return it }
        snapshotRequestHeaders()
        val stream = try {
            delegate.inputStream
        } catch (e: IOException) {
            recordError(e)
            throw e
        }
        val wrapped = sampleInputStream(stream)
        inputStream = wrapped
        return wrapped
    }

    override fun getErrorStream(): InputStream? {
        errorStream?.let { return it }
        snapshotRequestHeaders()
        val stream = delegate.errorStream ?: return null
        val wrapped = sampleInputStream(stream)
        errorStream = wrapped
        return wrapped
    }

    /** 响应流包装：边读边抄样本，EOF / close 落记录（幂等）。 */
    private fun sampleInputStream(stream: InputStream): InputStream =
        object : InputStream() {
            override fun read(): Int {
                val value = stream.read()
                if (value >= 0) {
                    appendResponse(byteArrayOf(value.toByte()), 0, 1)
                } else {
                    record(null)
                }
                return value
            }

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                val n = stream.read(buffer, offset, length)
                if (n > 0) {
                    appendResponse(buffer, offset, n)
                } else if (n == -1) {
                    record(null)
                }
                return n
            }

            override fun available(): Int = stream.available()
            override fun close() {
                try {
                    stream.close()
                } finally {
                    record(null)
                }
            }
        }

    /** 请求体输出流包装：边写边抄样本，不缓冲全量、不影响底层发送。 */
    private inner class SampleOutputStream(private val stream: OutputStream) : OutputStream() {

        override fun write(b: Int) {
            stream.write(b)
            appendRequest(byteArrayOf(b.toByte()), 0, 1)
        }

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            stream.write(buffer, offset, length)
            appendRequest(buffer, offset, length)
        }

        override fun flush() = stream.flush()
        override fun close() = stream.close()
    }

    // MARK: - 样本缓冲 + 单次落库

    private fun appendRequest(buffer: ByteArray, offset: Int, length: Int) {
        synchronized(requestLock) {
            requestBytes += length
            val room = MockNetPack.BODY_LIMIT_BYTES - requestSample.size()
            if (room > 0) {
                val copy = minOf(room.toInt(), length)
                requestSample.write(buffer, offset, copy)
            }
        }
    }

    private fun appendResponse(buffer: ByteArray, offset: Int, length: Int) {
        synchronized(responseLock) {
            responseBytes += length
            val room = MockNetPack.BODY_LIMIT_BYTES - responseSample.size()
            if (room > 0) {
                val copy = minOf(room.toInt(), length)
                responseSample.write(buffer, offset, copy)
            }
        }
    }

    /// 落一条记录（只在首次调用生效）。`error` 非空表示连接失败。
    private fun record(error: IOException?) {
        if (!recorded.compareAndSet(false, true)) return
        val responseHeaders = try {
            buildResponseHeaders()
        } catch (_: IOException) {
            null
        }
        // 状态码尽量取到：4xx 时 getInputStream() 会抛 IOException，但 responseCode
        // 仍可读（错误条目也应带 404/500，便于 Web 区分"请求失败"与"网络不通"）。
        val statusCode = try {
            delegate.responseCode
        } catch (_: IOException) {
            null
        }
        val requestBody: ByteArray
        val requestSize: Long
        synchronized(requestLock) {
            requestBody = requestSample.toByteArray()
            requestSize = requestBytes
        }
        val responseBody: ByteArray
        val responseSize: Long
        synchronized(responseLock) {
            responseBody = responseSample.toByteArray()
            responseSize = responseBytes
        }

        HttpExchangeRecorder.record(
            CapturedExchange(
                method = delegate.requestMethod,
                url = delegate.url?.toString() ?: "",
                requestHeaders = requestHeaders ?: try {
                    delegate.requestProperties?.mapValues { (_, values) ->
                        values?.filterNotNull() ?: emptyList()
                    } ?: emptyMap()
                } catch (_: Exception) {
                    emptyMap()
                },
                requestBodySample = requestBody,
                requestBodyFullSize = requestSize,
                requestBodyContentType = try {
                    delegate.getRequestProperty("Content-Type")
                } catch (_: Exception) {
                    null
                },
                statusCode = statusCode,
                responseHeaders = responseHeaders,
                responseBodySample = responseBody,
                responseBodyFullSize = responseSize,
                responseBodyContentType = try {
                    delegate.contentType
                } catch (_: Exception) {
                    null
                },
                error = error?.let { it.message ?: it.javaClass.simpleName },
                durationMs = ((System.nanoTime() - startNs) / 1_000_000).toInt().coerceAtLeast(0),
                adapter = adapter,
            ),
            generation,
        )
    }

    /// 响应头字典：HttpURLConnection.getHeaderFields() 的首项是状态行（key 为 null），
    /// 必须剔除，否则 Web 侧会渲染出一个空名头。
    private fun buildResponseHeaders(): Map<String, List<String>>? {
        val fields = delegate.headerFields ?: return null
        val result = LinkedHashMap<String, List<String>>()
        for ((name, values) in fields) {
            if (name == null) continue
            val cleaned = values?.filterNotNull() ?: emptyList()
            if (cleaned.isEmpty()) continue
            result[name] = cleaned
        }
        return result
    }

    private fun recordError(e: IOException) {
        record(e)
    }
}
