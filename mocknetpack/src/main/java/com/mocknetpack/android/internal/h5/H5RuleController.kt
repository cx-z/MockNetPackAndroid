package com.mocknetpack.android.internal.h5

import com.mocknetpack.android.MockNetPack
import com.mocknetpack.android.internal.capture.TrafficCaptureController
import com.mocknetpack.android.internal.connection.ConnectionClient
import com.mocknetpack.android.internal.log.SDKLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject

/** The H5 projection stays in memory for a single confirmed capture window. */
internal object H5RuleController {
    internal class Window(val generation: Long, val startedAt: Long)

    private data class Target(val window: Window, val version: Int)
    private data class Projection(val version: Int, val rules: Map<String, JSONObject>)

    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var installed = false
    private var target: Target? = null
    private var projection: Projection? = null
    private var compatible = false
    private var retryAt = 0L
    private var interruptedAt = 0L
    private var pull: Job? = null

    fun install() = synchronized(lock) { installed = true }

    /** Invalidates document tokens and outstanding sync results on stop or disconnect. */
    fun clear() {
        synchronized(lock) {
            if (target == null) return
            interruptedAt = System.currentTimeMillis()
            invalidateLocked()
        }
        H5WebViewCapture.refreshPages()
    }

    /** Schedules the SDK projection off the heartbeat coroutine. */
    fun sync(client: ConnectionClient, app: String, did: String, version: Int) {
        val capture = TrafficCaptureController.h5Window
        var changed = false
        synchronized(lock) {
            if (!installed) return
            if (capture == null) {
                changed = target != null
                invalidateLocked()
            } else {
                val previous = target
                val window = previous?.window?.takeIf { it.generation == capture.generation }
                    ?: Window(capture.generation, if (interruptedAt >= capture.startedAt) {
                        System.currentTimeMillis()
                    } else {
                        capture.startedAt
                    })
                if (window !== previous?.window) invalidateLocked()
                if (previous?.window !== window || previous.version != version) {
                    if ((projection?.version ?: -1) < version) {
                        projection = null
                    }
                    pull?.cancel()
                    pull = null
                    target = Target(window, version)
                    retryAt = 0
                    changed = true
                }
                val requested = checkNotNull(target)
                if (projection == null && pull == null && System.nanoTime() >= retryAt) {
                    pull = scope.launch { download(client, app, did, requested) }
                }
            }
        }
        if (changed) H5WebViewCapture.refreshPages()
    }

    private fun invalidateLocked() {
        pull?.cancel()
        pull = null
        target = null
        projection = null
        compatible = false
        retryAt = 0
    }

    private suspend fun download(client: ConnectionClient, app: String, did: String, requested: Target) {
        val result = try {
            client.get("devices/$app/$did/mock-rules?sinceVersion=0&scope=h5")
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        val supported = result?.let { supportsAndroid(it) }
        val snapshot = if (supported == true) parseProjection(checkNotNull(result), requested.version) else null
        synchronized(lock) {
            if (target !== requested) return
            pull = null
            if (supported != null) compatible = supported
            projection = snapshot
            retryAt = System.nanoTime() + if (supported == false) 60_000_000_000L else 10_000_000_000L
        }
        if (supported == false) SDKLogger.info("H5 unavailable: platform does not recognize Android WebView sources", category = "h5")
        H5WebViewCapture.refreshPages()
    }

    private fun parseProjection(json: JSONObject, expected: Int): Projection? {
        val version = json.optInt("version", -1)
        if (version < expected) return null
        val rules = json.optJSONArray("rules") ?: return null
        val result = LinkedHashMap<String, JSONObject>()
        for (index in 0 until rules.length()) {
            val rule = rules.optJSONObject(index) ?: continue
            if (rule.optString("scope") != "h5" || !rule.optBoolean("effective")) continue
            val id = rule.optString("id")
            val response = rule.optJSONObject("response") ?: continue
            val method = rule.optString("method")
            val path = rule.optString("path")
            val endpoint = H5Endpoint.parse(rule.optString("origin") + path) ?: continue
            if (id.isEmpty() || endpoint.path != path || !H5Endpoint.validMethod(method)) continue
            val status = response.optInt("statusCode")
            val body = response.optString("body", "")
            val delay = response.optInt("delayMs", 0)
            if (status !in 200..299 && status !in 400..599) continue
            if (body.toByteArray(Charsets.UTF_8).size > MockNetPack.BODY_LIMIT_BYTES ||
                response.has("media") || response.optString("bodyBase64").isNotEmpty() ||
                delay !in 0..30_000 ||
                ((method.equals("HEAD", true) || status == 204 || status == 205) && body.isNotEmpty())
            ) continue
            result[endpoint.key(method)] = JSONObject()
                .put("ruleId", id)
                .put("status", status)
                .put("headers", response.optJSONObject("headers") ?: JSONObject())
                .put("body", body)
                .put("delayMs", delay)
        }
        return Projection(version, result)
    }

    internal fun supportsAndroid(json: JSONObject): Boolean {
        if (json.optString("scope") != "h5") return false
        val adapters = json.optJSONArray("h5TrafficAdapters") ?: return false
        val values = (0 until adapters.length()).map(adapters::optString).toSet()
        return H5CaptureSample.adapters.all(values::contains)
    }

    /** Returns the active H5 window only after the platform has advertised Android support. */
    fun window(): Window? {
        val generation = TrafficCaptureController.h5Window?.generation ?: return null
        return synchronized(lock) { target?.window?.takeIf { compatible && it.generation == generation } }
    }

    fun keys(window: Window): Set<String> = synchronized(lock) {
        if (compatible && target?.window === window) projection?.rules?.keys.orEmpty() else emptySet()
    }

    fun reply(window: Window, key: String): JSONObject? = synchronized(lock) {
        if (compatible && target?.window === window) projection?.rules?.get(key) else null
    }
}
