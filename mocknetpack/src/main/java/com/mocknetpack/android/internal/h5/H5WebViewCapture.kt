package com.mocknetpack.android.internal.h5

import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.mocknetpack.android.MockNetPack
import com.mocknetpack.android.R
import com.mocknetpack.android.TrafficEntry
import com.mocknetpack.android.internal.capture.TrafficCaptureController
import com.mocknetpack.android.internal.log.SDKLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.WeakHashMap
import java.util.Collections

/** Registers the document-start script and validates messages from opted-in WebViews. */
internal object H5WebViewCapture {
    private const val BRIDGE = "__mockNetPackAndroidHTTP"
    private val origins = setOf("*")
    private val documentID = Regex("^[0-9a-f]{32}$")
    private val main by lazy { Handler(Looper.getMainLooper()) }
    @OptIn(ExperimentalCoroutinesApi::class)
    private val worker = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))

    private class Installation(val credential: String, val script: ScriptHandler) {
        var page: H5Page? = null
        var navigation: H5NavigationClient? = null
        val departedDocuments = ArrayDeque<String>()
    }

    // WebView, reply proxies, partial messages and admission counters are UI-thread confined.
    private val views = Collections.newSetFromMap(WeakHashMap<WebView, Boolean>())
    private var pendingSamples = 0
    private var pendingHitSummaries = 0
    private var scriptSource: String? = null

    fun install(view: WebView, includeNavigationDiagnostics: Boolean) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            log("Install H5 on the UI thread before the first navigation")
            return
        }
        if (!view.settings.javaScriptEnabled) {
            log("H5 not installed: JavaScript is disabled")
            return
        }
        try {
            val installation = installation(view) ?: register(view) ?: return
            if (includeNavigationDiagnostics) installNavigation(view, installation)
        } catch (error: RuntimeException) {
            log("H5 installation unavailable: ${error.javaClass.simpleName}")
        } catch (error: java.io.IOException) {
            log("H5 script unavailable: ${error.javaClass.simpleName}")
        }
    }

    private fun register(view: WebView): Installation? {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) ||
            !WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
        ) {
            log("H5 not installed: WebView provider lacks document-start script or the message listener")
            return null
        }

        val credential = UUID.randomUUID().toString()
        val script = scriptSource ?: listOf("bridge", "capture", "mock", "diagnostics")
            .joinToString("\n") { name ->
                view.context.assets.open("mocknetpack/http-$name.js").bufferedReader().use { it.readText() }
            }.also { scriptSource = it }

        WebViewCompat.addWebMessageListener(view, BRIDGE, origins) { sender, message, origin, mainFrame, proxy ->
            if (!mainFrame || (origin.scheme != "http" && origin.scheme != "https")) return@addWebMessageListener
            if (message.type != androidx.webkit.WebMessageCompat.TYPE_STRING) return@addWebMessageListener
            val raw = message.data ?: return@addWebMessageListener
            if (raw.length > H5BridgeTransport.MAX_ENVELOPE) return@addWebMessageListener
            try {
                receive(sender, JSONObject(raw), origin.toString(), proxy)
            } catch (_: Exception) {
                // A malformed message or a document lost during a reply cannot change page behavior.
            }
        }
        try {
            val handle = WebViewCompat.addDocumentStartJavaScript(
                view, script.replace("__MNP_BRIDGE_CREDENTIAL__", credential), origins,
            )
            return Installation(credential, handle).also {
                view.setTag(R.id.mocknetpack_h5_installation, it)
                views.add(view)
                H5RuleController.install()
            }
        } catch (error: RuntimeException) {
            WebViewCompat.removeWebMessageListener(view, BRIDGE)
            throw error
        }
    }

    private fun installNavigation(view: WebView, installation: Installation) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.GET_WEB_VIEW_CLIENT)) {
            log("H5 navigation callbacks unavailable on this WebView provider")
            return
        }
        val original = WebViewCompat.getWebViewClient(view)
        if (original === installation.navigation) return
        val capture = H5NavigationCapture(main)
        val client = H5NavigationClient(original, capture) {
            installation.page = null
            installation.departedDocuments.clear()
        }
        view.webViewClient = client
        installation.navigation = client
    }

    fun uninstall(view: WebView) {
        if (Looper.myLooper() != Looper.getMainLooper()) return
        val installation = installation(view) ?: return
        views.remove(view)
        view.setTag(R.id.mocknetpack_h5_installation, null)
        installation.page?.let { page ->
            runCatching {
                page.proxy.postMessage(JSONObject().put("document", page.document).put("disable", true).toString())
            }
        }
        runCatching { installation.script.remove() }
        runCatching { WebViewCompat.removeWebMessageListener(view, BRIDGE) }
        val navigation = installation.navigation ?: return
        navigation.capture.clear()
        runCatching {
            if (WebViewCompat.getWebViewClient(view) === navigation) view.webViewClient = navigation.original
        }
    }

    /** Uses each document's own reply channel; a departed renderer never receives a new token. */
    fun refreshPages() {
        main.post {
            val active = H5RuleController.window()
            views.toList().mapNotNull(::installation).forEach { installed ->
                val page = installed.page ?: return@forEach
                page.bind(active)
                runCatching {
                    page.proxy.postMessage(JSONObject().put("document", page.document).put("refresh", true).toString())
                }
            }
        }
    }

    private fun receive(view: WebView, message: JSONObject, origin: String, proxy: JavaScriptReplyProxy) {
        val installation = installation(view) ?: return
        val document = message.optString("document")
        val kind = message.optString("kind")
        val rpc = message.optLong("rpc")
        if (rpc <= 0L || !documentID.matches(document) ||
            message.optString("credential") != installation.credential
        ) return

        val active = H5RuleController.window()
        if (kind == "hello") {
            hello(installation, document, origin, proxy, rpc, active, message.optBoolean("restored"))
            return
        }

        val page = installation.page ?: return
        if (page.document != document || page.sourceOrigin != origin || page.proxy !== proxy) return
        if (active == null || page.window !== active || message.optString("token") != page.token) {
            H5BridgeTransport.reply(page, rpc, null)
            return
        }
        when (kind) {
            "mock" -> H5BridgeTransport.reply(page, rpc, page.makeReceipt(message, active))
            "record", "mockHit" -> {
                val payload = H5BridgeTransport.payload(page, message, rpc) ?: return
                val id = message.optInt("id")
                if (kind == "mockHit") {
                    val receipt = page.takeReceipt(message)
                    if (receipt != null && page.accept(id)) {
                        enqueue(payload, active, receipt, message.optString("summary"), installation.navigation?.capture)
                    }
                } else if (page.accept(id)) {
                    enqueue(payload, active, null, "", installation.navigation?.capture)
                }
                H5BridgeTransport.reply(page, rpc, null)
            }
        }
    }

    private fun hello(
        installation: Installation,
        document: String,
        origin: String,
        proxy: JavaScriptReplyProxy,
        rpc: Long,
        active: H5RuleController.Window?,
        restored: Boolean,
    ) {
        val previous = installation.page
        val page = if (previous?.document == document && previous.sourceOrigin == origin && previous.proxy === proxy) {
            previous
        } else {
            if (document in installation.departedDocuments && !restored) return
            installation.departedDocuments.remove(document)
            previous?.document?.let(installation.departedDocuments::addLast)
            if (installation.departedDocuments.size > 32) installation.departedDocuments.removeFirst()
            H5Page(document, origin, proxy).also { installation.page = it }
        }
        page.bind(active)
        val reply = JSONObject().put("limit", MockNetPack.BODY_LIMIT_BYTES)
        if (active != null) {
            reply.put("token", page.token)
                .put("startedAt", active.startedAt)
                .put("mockKeys", JSONArray(H5RuleController.keys(active)))
        }
        H5BridgeTransport.reply(page, rpc, reply)
    }

    private fun enqueue(
        payload: String,
        window: H5RuleController.Window,
        receipt: H5Page.Receipt?,
        summary: String,
        navigation: H5NavigationCapture?,
    ) {
        val useSummary = pendingSamples >= 8
        if (useSummary && (receipt == null || pendingHitSummaries >= 8 || summary.length !in 1..65_536)) return
        val text = if (useSummary) summary else payload
        if (useSummary) pendingHitSummaries++ else pendingSamples++
        worker.launch {
            try {
                if (H5RuleController.window() !== window || text.toByteArray(Charsets.UTF_8).size > H5BridgeTransport.MAX_PAYLOAD) return@launch
                val sample = H5CaptureSample.fromPage(JSONObject(text), window) ?: return@launch
                val entry = if (receipt == null) sample else {
                    if (sample.adapter != receipt.source || sample.method != receipt.method || sample.url != receipt.url) return@launch
                    sample.copy(mocked = true, matchedRuleId = receipt.ruleID)
                }
                if (entry.adapter == H5CaptureSample.NAVIGATION && navigation != null) {
                    main.post { navigation.pageFinished(entry, window) }
                } else {
                    record(entry, window)
                }
            } catch (_: Exception) {
                // Sampling should never change the page or tear down the worker.
            } finally {
                main.post { if (useSummary) pendingHitSummaries-- else pendingSamples-- }
            }
        }
    }

    fun submitNavigation(entry: TrafficEntry, window: H5RuleController.Window) {
        worker.launch { record(entry, window) }
    }

    private fun record(entry: TrafficEntry, window: H5RuleController.Window) {
        if (H5RuleController.window() !== window) return
        val controller = TrafficCaptureController
        val size = controller.entryJson(entry).toString().toByteArray(Charsets.UTF_8).size
        if (size <= H5BridgeTransport.MAX_PAYLOAD) {
            controller.record(entry, window.generation, size)
        } else {
            val omitted = "[正文未保留：超过 HTTP 上传预算]"
            val summary = entry.copy(
                requestBody = omitted.takeIf { entry.requestBody.isNotEmpty() }.orEmpty(),
                requestBodyBase64 = null,
                responseBody = omitted.takeIf { entry.responseBody != null },
                responseBodyBase64 = null,
            )
            val summarySize = controller.entryJson(summary).toString().toByteArray(Charsets.UTF_8).size
            if (summarySize <= H5BridgeTransport.MAX_PAYLOAD) controller.record(summary, window.generation, summarySize)
        }
    }

    private fun log(message: String) = SDKLogger.info(message, category = "h5")

    private fun installation(view: WebView): Installation? =
        view.getTag(R.id.mocknetpack_h5_installation) as? Installation
}
