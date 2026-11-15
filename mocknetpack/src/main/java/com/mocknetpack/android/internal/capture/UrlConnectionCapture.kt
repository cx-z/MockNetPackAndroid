package com.mocknetpack.android.internal.capture

import com.mocknetpack.android.MockNetPack
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * HttpURLConnection 采集包装器。
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
            adapter = HttpExchangeRecorder.sourceLabel(adapter, HttpExchangeRecorder.ADAPTER_HTTP_URL_CONNECTION),
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

    private val requestSample = ByteArrayOutputStream()
    private val requestLock = Any()
    private var requestBytes = 0L

    private val responseSample = ByteArrayOutputStream()
    private val responseLock = Any()
    private var responseBytes = 0L

    private val lifecycleLock = Any()
    private var requestHeaders: Map<String, List<String>>? = null
    private var started = false
    private var recorded = false
    private var statusCode: Int? = null
    private var responseHeaders: Map<String, List<String>>? = null
    private var inputFailure: IOException? = null

    private var outputStream: OutputStream? = null
    private var inputStream: InputStream? = null
    private var errorStream: InputStream? = null

    override fun connect() {
        beginNetworkAccess()
        try {
            delegate.connect()
        } catch (e: IOException) {
            record(e)
            throw e
        }
    }

    override fun disconnect() {
        try {
            delegate.disconnect()
        } finally {
            record()
        }
    }

    override fun usingProxy(): Boolean = delegate.usingProxy()

    /// 在调用方的操作可能触发连接前取得请求头。
    private fun beginNetworkAccess() = synchronized(lifecycleLock) {
        if (!started) {
            requestHeaders = runCatching {
                delegate.requestProperties?.mapValues { (_, values) -> values?.filterNotNull() ?: emptyList() }
            }.getOrNull()
            started = true
        }
    }

    /// 只有调用方已经取得响应时才读底层的响应头缓存。
    private fun observeResponse(code: Int? = null) {
        val fields = runCatching { delegate.headerFields }.getOrNull()
        rememberResponse(fields, code)
    }

    private fun rememberResponse(fields: Map<out String?, List<String>?>?, code: Int? = null) {
        val headers = LinkedHashMap<String, List<String>>()
        var statusLineCode: Int? = null
        for ((name, values) in fields.orEmpty()) {
            if (name == null) {
                statusLineCode = values?.firstOrNull()?.split(' ', limit = 3)?.getOrNull(1)?.toIntOrNull()
            } else if (!values.isNullOrEmpty()) {
                headers[name] = values.filterNotNull()
            }
        }
        synchronized(lifecycleLock) {
            (code ?: statusLineCode)?.takeIf { it >= 100 }?.let { statusCode = it }
            if (fields != null) responseHeaders = headers
        }
    }

    private inline fun <T : Any> readResponseValue(read: () -> T?): T? {
        beginNetworkAccess()
        return read()?.also { observeResponse() }
    }

    override fun getHeaderField(name: String?): String? = readResponseValue { delegate.getHeaderField(name) }
    override fun getHeaderField(pos: Int): String? = readResponseValue { delegate.getHeaderField(pos) }
    override fun getHeaderFieldKey(pos: Int): String? = readResponseValue { delegate.getHeaderFieldKey(pos) }

    override fun getHeaderFields(): Map<String, List<String>> {
        beginNetworkAccess()
        return delegate.headerFields.also { rememberResponse(it) }
    }

    override fun getResponseCode(): Int {
        beginNetworkAccess()
        return try {
            delegate.responseCode.also { if (it >= 100) observeResponse(it) }
        } catch (e: IOException) {
            record(e)
            throw e
        }
    }

    override fun getResponseMessage(): String? = readResponseValue { delegate.responseMessage }

    override fun getRequestMethod(): String = delegate.requestMethod
    override fun setRequestMethod(method: String?) = delegate.setRequestMethod(method)

    override fun getInstanceFollowRedirects(): Boolean = delegate.instanceFollowRedirects
    override fun setInstanceFollowRedirects(follow: Boolean) = delegate.setInstanceFollowRedirects(follow)

    override fun getContentType(): String? = readResponseValue { delegate.contentType }
    override fun getContentLength(): Int {
        beginNetworkAccess()
        return delegate.contentLength.also { if (it >= 0) observeResponse() }
    }

    override fun getContentLengthLong(): Long {
        beginNetworkAccess()
        return delegate.contentLengthLong.also { if (it >= 0) observeResponse() }
    }

    override fun getContentEncoding(): String? = readResponseValue { delegate.contentEncoding }

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
        beginNetworkAccess()
        val stream = recordOnIoFailure { delegate.outputStream }
        return SampleOutputStream(stream).also { outputStream = it }
    }

    override fun getInputStream(): InputStream {
        inputStream?.let { return it }
        beginNetworkAccess()
        val stream = try {
            delegate.inputStream
        } catch (e: IOException) {
            // HTTP 错误也是 getInputStream 的正常异常路径，调用方可以继续读 error stream。
            synchronized(lifecycleLock) { inputFailure = e }
            if (runCatching { delegate.errorStream }.getOrNull() != null) observeResponse()
            throw e
        }
        synchronized(lifecycleLock) { inputFailure = null }
        observeResponse()
        return sampleInputStream(stream).also { inputStream = it }
    }

    override fun getErrorStream(): InputStream? {
        errorStream?.let { return it }
        val stream = delegate.errorStream ?: return null
        beginNetworkAccess()
        observeResponse()
        return sampleInputStream(stream).also { errorStream = it }
    }

    /// 保留原异常。body 开始读写后的 I/O 异常终止当前采集。
    private inline fun <T> recordOnIoFailure(action: () -> T): T {
        return try {
            action()
        } catch (e: IOException) {
            record(e)
            throw e
        }
    }

    private fun sampleInputStream(stream: InputStream): InputStream =
        object : InputStream() {
            override fun read(): Int = recordOnIoFailure {
                val value = stream.read()
                if (value >= 0) {
                    appendResponse(byteArrayOf(value.toByte()), 0, 1)
                } else {
                    record()
                }
                value
            }

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = recordOnIoFailure {
                val n = stream.read(buffer, offset, length)
                if (n > 0) {
                    appendResponse(buffer, offset, n)
                } else if (n == -1) {
                    record()
                }
                n
            }

            override fun available(): Int = stream.available()

            override fun close() {
                try {
                    recordOnIoFailure { stream.close() }
                } finally {
                    record()
                }
            }
        }

    private inner class SampleOutputStream(private val stream: OutputStream) : OutputStream() {
        override fun write(b: Int) = recordOnIoFailure {
            stream.write(b)
            appendRequest(byteArrayOf(b.toByte()), 0, 1)
        }

        override fun write(buffer: ByteArray, offset: Int, length: Int) = recordOnIoFailure {
            stream.write(buffer, offset, length)
            appendRequest(buffer, offset, length)
        }

        override fun flush() = recordOnIoFailure { stream.flush() }
        override fun close() = recordOnIoFailure { stream.close() }
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

    /// 只提交已有的观测数据，记录过程不会调用可能发起请求的底层接口。
    private fun record(error: IOException? = null) {
        val metadata = synchronized(lifecycleLock) {
            if (!started || recorded) return
            recorded = true
            ResponseMetadata(
                requestHeaders = requestHeaders.orEmpty(),
                statusCode = statusCode,
                responseHeaders = responseHeaders,
                error = error ?: inputFailure,
            )
        }
        val (requestBody, requestSize) = synchronized(requestLock) { requestSample.toByteArray() to requestBytes }
        val (responseBody, responseSize) = synchronized(responseLock) { responseSample.toByteArray() to responseBytes }
        runCatching {
            HttpExchangeRecorder.record(
                CapturedExchange(
                    method = delegate.requestMethod,
                    url = delegate.url?.toString() ?: "",
                    requestHeaders = metadata.requestHeaders,
                    requestBodySample = requestBody,
                    requestBodyFullSize = requestSize,
                    requestBodyContentType = contentType(metadata.requestHeaders),
                    statusCode = metadata.statusCode,
                    responseHeaders = metadata.responseHeaders,
                    responseBodySample = responseBody,
                    responseBodyFullSize = responseSize,
                    responseBodyContentType = contentType(metadata.responseHeaders),
                    error = metadata.error?.let { it.message ?: it.javaClass.simpleName },
                    durationMs = ((System.nanoTime() - startNs) / 1_000_000).toInt().coerceAtLeast(0),
                    adapter = adapter,
                ),
                generation,
            )
        }
    }

    private fun contentType(headers: Map<String, List<String>>?): String? =
        headers?.entries?.firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }?.value?.firstOrNull()

    private data class ResponseMetadata(
        val requestHeaders: Map<String, List<String>>,
        val statusCode: Int?,
        val responseHeaders: Map<String, List<String>>?,
        val error: IOException?,
    )
}
