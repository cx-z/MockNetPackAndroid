package com.mocknetpack.android.internal.h5

import android.os.Handler
import android.os.SystemClock
import com.mocknetpack.android.TrafficEntry
import com.mocknetpack.android.internal.capture.TrafficCaptureController
import java.util.Date

/** Correlates the main-frame Client callbacks with the document's timing sample on the UI thread. */
internal class H5NavigationCapture(
    private val schedule: (Runnable, Long) -> Unit,
    private val cancel: (Runnable) -> Unit,
    private val activeWindow: () -> H5RuleController.Window? = H5RuleController::window,
    private val uptime: () -> Long = SystemClock::elapsedRealtime,
    private val submit: (TrafficEntry, H5RuleController.Window) -> Unit = H5WebViewCapture::submitNavigation,
) {
    constructor(main: Handler) : this({ task, delay -> main.postDelayed(task, delay) }, main::removeCallbacks)

    private class Load(val url: String, val window: H5RuleController.Window?, val startedUptime: Long?) {
        val startedAt = Date()
        var completed = false
        var awaitingStart = false
        var finalURL: String = url
        var fallback: Runnable? = null
    }

    private var load: Load? = null

    fun started(url: String?) {
        val active = activeWindow()
        val current = load
        if (current?.awaitingStart == true && current.url == url && current.window === active) {
            current.awaitingStart = false
            return
        }
        clear()
        val address = url ?: return
        if (H5Endpoint.parse(address) == null) return
        load = Load(address, active, uptime())
    }

    fun clear() {
        load?.fallback?.let(cancel)
        load = null
    }

    fun matches(url: String?): Boolean = load?.let { it.finalURL == url || it.url == url } == true

    /** Lets Navigation Timing supply available HTTP status before the callback-only fallback. */
    fun finished(url: String?) {
        val current = load ?: return
        if (current.completed) {
            if (matches(url)) current.awaitingStart = false
            return
        }
        if (current.window == null || current.fallback != null || url == null || H5Endpoint.parse(url) == null) return
        current.finalURL = url
        val fallback = Runnable {
            if (load === current && !current.completed) finish(current, url, stage = "h5NavigationFinished")
        }
        current.fallback = fallback
        schedule(fallback, 500)
    }

    fun pageFinished(entry: TrafficEntry, window: H5RuleController.Window) {
        val current = load
        if (current == null || current.window !== window || !matches(entry.url)) {
            submit(entry, window)
        } else if (!current.completed) {
            current.completed = true
            current.fallback?.let(cancel)
            submit(entry, window)
        }
    }

    /** The main-frame HTTP callback may arrive before onPageStarted and already contains the response. */
    fun httpError(
        url: String,
        method: String,
        status: Int,
        reason: String?,
        requestHeaders: Map<String, String>?,
        responseHeaders: Map<String, String>?,
    ) {
        if (H5Endpoint.parse(url) == null) return
        val active = activeWindow() ?: return
        val current = load
        val matching = current?.takeIf { matches(url) }
        if (matching != null && !matching.completed && matching.window !== active) return
        val target = matching?.takeUnless { it.completed } ?: Load(url, active, null).also {
            clear()
            it.awaitingStart = true
            load = it
        }
        finish(target, url, "httpResponse", method, error = reason, status = status,
            requestHeaders = requestHeaders, responseHeaders = responseHeaders)
    }

    fun failed(
        url: String?,
        method: String = "UNKNOWN",
        code: Int? = null,
        error: String? = null,
        domain: String? = null,
        requestHeaders: Map<String, String>? = null,
        stage: String = "h5NavigationFailed",
    ) {
        val current = load ?: return
        val address = url ?: return
        if (current.completed || H5Endpoint.parse(address) == null) return
        current.finalURL = address
        finish(current, address, stage, method, code, error, domain, requestHeaders = requestHeaders)
    }

    fun rendererGone(didCrash: Boolean) {
        failed(
            load?.finalURL,
            error = if (didCrash) "WebView renderer crashed" else "WebView renderer exited",
            domain = "WebView",
        )
        clear()
    }

    private fun finish(
        current: Load,
        url: String,
        stage: String,
        method: String = "UNKNOWN",
        code: Int? = null,
        error: String? = null,
        domain: String? = null,
        status: Int? = null,
        requestHeaders: Map<String, String>? = null,
        responseHeaders: Map<String, String>? = null,
    ) {
        val window = current.window ?: return
        if (activeWindow() !== window) return
        val endpoint = H5Endpoint.parse(url) ?: return
        current.completed = true
        current.fallback?.let(cancel)
        submit(
            TrafficEntry(
                timestamp = TrafficCaptureController.rfc3339(current.startedAt),
                method = method,
                url = url,
                path = endpoint.path,
                query = endpoint.query,
                requestHeaders = requestHeaders.orEmpty().mapValues { listOf(it.value) },
                requestBody = "",
                statusCode = status,
                responseHeaders = responseHeaders?.mapValues { listOf(it.value) },
                error = error,
                durationMs = current.startedUptime?.let {
                    (uptime() - it).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
                } ?: 0,
                adapter = H5CaptureSample.NAVIGATION,
                terminalStage = stage,
                errorDomain = domain,
                errorCode = code,
            ),
            window,
        )
    }
}
