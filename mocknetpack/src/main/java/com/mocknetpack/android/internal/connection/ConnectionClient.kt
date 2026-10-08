package com.mocknetpack.android.internal.connection

import com.mocknetpack.android.MockNetPack
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/// 连接层 HTTP 错误（对齐 iOS ConnectionClientError）。
sealed class ConnectionClientError(cause: Throwable? = null) : Exception(cause) {
    /// 非 2xx 响应（携带状态码）。
    class HttpStatus(val code: Int) : ConnectionClientError()
    /// 网络层错误（连接失败 / 超时）。
    class Network(cause: IOException) : ConnectionClientError(cause)
    /// 无法构造 / 校验 URL。
    object InvalidURL : ConnectionClientError()
    /// 响应 JSON 解码失败。
    class Decoding(cause: Exception) : ConnectionClientError(cause)
}

/**
 * 极简 JSON HTTP 客户端（对齐 iOS ConnectionClient）。
 *
 * - 进程级共享 OkHttpClient（**不含拦截器**）：SDK 自身请求（注册/心跳/
 *   流量上传/规则拉取）统一复用连接池；同时恒带跳过标记 header
 *   `X-MockNetPack-Skip: 1`（双保险：即便宿主注入拦截器也永不递归抓自身）。
 * - 契约模型用 org.json 手写解析（零第三方序列化依赖，保持 iOS 侧
 *   「无第三方代码依赖」的理念；Android 运行时 org.json 由 framework 提供）。
 * - 请求 10s 超时；非 2xx 抛 [ConnectionClientError.HttpStatus]。
 * - [probe]：GET 必然 404 的路径，收到任何 HTTP 响应（含 404）即视为连通。
 */
class ConnectionClient private constructor(
    private val baseURL: String,
    private val client: OkHttpClient,
) {

    companion object {
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
        private const val REQUEST_TIMEOUT_SECONDS = 10L
        private const val PROBE_TIMEOUT_SECONDS = 3L

        @Volatile
        private var sharedHttpClient: OkHttpClient? = null
        private val sharedLock = Any()

        /// 进程级共享 OkHttpClient（惰性创建、连接池复用）。
        /// 不含任何拦截器——SDK 自身请求永不进入 MockNetPackInterceptor；
        /// 请求恒带 skipHeader 为双保险。
        private fun sharedClient(): OkHttpClient {
            sharedHttpClient?.let { return it }
            synchronized(sharedLock) {
                sharedHttpClient?.let { return it }
                val built = OkHttpClient.Builder()
                    .connectTimeout(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .readTimeout(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .build()
                sharedHttpClient = built
                return built
            }
        }

        /**
         * 校验 baseURL（http/https + host 非空）并规范化：
         * - origin 形式（`http://host:port`，无路径）自动补 `/api/v1`
         *   （技术方案约定 server 参数形如 `http://host:4290/api/v1`）；
         * - 非法 URL / 非 http(s) / host 为空 → 抛 [ConnectionClientError.InvalidURL]。
         */
        fun create(baseURL: String): ConnectionClient {
            val normalized = normalizeBaseURL(baseURL) ?: throw ConnectionClientError.InvalidURL
            return ConnectionClient(normalized, sharedClient())
        }

        private fun normalizeBaseURL(raw: String): String? {
            val trimmed = raw.trim().trimEnd('/')
            if (trimmed.isEmpty()) return null
            val uri = try {
                URI(trimmed)
            } catch (_: Exception) {
                return null
            }
            val scheme = uri.scheme?.lowercase() ?: return null
            if (scheme != "http" && scheme != "https") return null
            val host = uri.host ?: return null
            if (host.isEmpty()) return null
            val path = uri.path
            if (path.isNullOrEmpty() || path == "/") {
                val authority = uri.authority ?: return null
                return "$scheme://$authority/api/v1"
            }
            return trimmed
        }
    }

    /// 发起 JSON POST 并返回响应 JSON；非 2xx 抛 HttpStatus；网络错误抛 Network。
    /// 网络 IO 在 Dispatchers.IO 执行，不阻塞连接层串行调度线程。
    suspend fun post(path: String, body: JSONObject?): JSONObject = withContext(Dispatchers.IO) {
        val url = "$baseURL/$path"
        val requestBody: RequestBody = body?.let {
            it.toString().toRequestBody(JSON_MEDIA_TYPE)
        } ?: ByteArray(0).toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .header("Content-Type", "application/json")
            .header(MockNetPack.SKIP_HEADER, "1")
            .build()
        executeJson(request)
    }

    /// 发起 JSON GET 并返回响应 JSON；非 2xx 抛 HttpStatus；网络错误抛 Network。
    suspend fun get(path: String): JSONObject = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$baseURL/$path")
            .get()
            .header("Accept", "application/json")
            .header(MockNetPack.SKIP_HEADER, "1")
            .build()
        executeJson(request)
    }

    /// 轻量可达性探测（扫码连接前使用）：GET 必然 404 的路径，收到任何
    /// HTTP 响应（含 404）即视为连通；传输层错误（不可达）抛 Network。
    suspend fun probe() {
        withContext(Dispatchers.IO) {
            // 共享连接池派生短超时客户端（3s，对齐 iOS probe 语义）。
            val probeClient = client.newBuilder()
                .readTimeout(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build()
            val request = Request.Builder()
                .url("$baseURL/__mocknetpack_probe__")
                .get()
                .header(MockNetPack.SKIP_HEADER, "1")
                .build()
            try {
                probeClient.newCall(request).execute().use {
                    // 收到任何 HTTP 响应即视为连通；状态码（含 404）不判断。
                }
            } catch (e: IOException) {
                throw ConnectionClientError.Network(e)
            }
        }
    }

    private fun executeJson(request: Request): JSONObject {
        try {
            client.newCall(request).execute().use { response ->
                val code = response.code
                if (code !in 200..299) throw ConnectionClientError.HttpStatus(code)
                val bodyText = response.body?.string() ?: ""
                return try {
                    JSONObject(bodyText)
                } catch (e: Exception) {
                    throw ConnectionClientError.Decoding(e)
                }
            }
        } catch (e: ConnectionClientError) {
            throw e
        } catch (e: IOException) {
            throw ConnectionClientError.Network(e)
        }
    }
}
