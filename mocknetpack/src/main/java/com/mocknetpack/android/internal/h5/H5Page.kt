package com.mocknetpack.android.internal.h5

import android.os.SystemClock
import androidx.webkit.JavaScriptReplyProxy
import org.json.JSONObject
import java.util.UUID

/** UI-thread state for a single document and its currently bound capture window. */
internal class H5Page(
    val document: String,
    val sourceOrigin: String,
    val proxy: JavaScriptReplyProxy,
) {
    data class Receipt(
        val requestID: Int,
        val ruleID: String,
        val method: String,
        val url: String,
        val source: String,
        val expiresAt: Long,
    )

    var window: H5RuleController.Window? = null
        private set
    var token: String? = null
        private set
    var transfer: H5BridgeTransport.Transfer? = null

    private val completed = HashSet<Int>()
    private val receipts = LinkedHashMap<String, Receipt>()
    private var highestID = 0

    fun bind(active: H5RuleController.Window?) {
        if (window === active) return
        window = active
        token = active?.let { UUID.randomUUID().toString() }
        transfer = null
        completed.clear()
        receipts.clear()
        highestID = 0
    }

    /** Reject repeated samples while permitting ordinary completion reordering. */
    fun accept(id: Int): Boolean {
        if (id !in 1..1_000_000_000 || id <= highestID - 1024) return false
        if (!completed.add(id)) return false
        highestID = maxOf(highestID, id)
        if (completed.size >= 1024) completed.removeAll { it <= highestID - 1024 }
        return true
    }

    /** Check the request key against the native snapshot before sending the canned body. */
    fun makeReceipt(message: JSONObject, active: H5RuleController.Window): JSONObject? {
        val now = SystemClock.elapsedRealtime()
        receipts.values.removeAll { it.expiresAt <= now }
        if (receipts.size >= 32) return null
        val method = message.optString("method")
        val url = message.optString("url")
        val source = message.optString("source")
        val id = message.optInt("id")
        val endpoint = H5Endpoint.parse(url) ?: return null
        if (!H5Endpoint.validMethod(method) || id !in 1..1_000_000_000 ||
            (source != H5CaptureSample.FETCH && source != H5CaptureSample.XHR)
        ) return null
        val key = endpoint.key(method)
        if (key != message.optString("key")) return null
        val rule = H5RuleController.reply(active, key) ?: return null
        val receiptID = UUID.randomUUID().toString()
        receipts[receiptID] = Receipt(id, rule.getString("ruleId"), method, url, source, now + 40_000)
        val reply = JSONObject()
        for (name in rule.keys()) reply.put(name, rule.get(name))
        return reply.put("receipt", receiptID)
    }

    fun takeReceipt(message: JSONObject): Receipt? {
        val id = message.optString("receipt")
        val receipt = receipts[id] ?: return null
        if (receipt.requestID != message.optInt("id") || receipt.expiresAt <= SystemClock.elapsedRealtime()) return null
        return receipts.remove(id)
    }
}
