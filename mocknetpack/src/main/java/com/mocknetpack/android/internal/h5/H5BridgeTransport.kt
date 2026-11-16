package com.mocknetpack.android.internal.h5

import android.os.SystemClock
import org.json.JSONObject

/** Splits transfers below WebView IPC limits. One bounded sample is assembled per document. */
internal object H5BridgeTransport {
    const val MAX_PAYLOAD = 5 * 1024 * 1024
    const val MAX_ENVELOPE = 384 * 1024
    private const val CHUNK_CHARS = 32 * 1024

    class Transfer(
        val rpc: Long,
        val parts: Int,
        val expiresAt: Long,
        val buffer: StringBuilder = StringBuilder(),
        var nextPart: Int = 0,
    )

    fun payload(page: H5Page, message: JSONObject, rpc: Long): String? {
        if (!message.has("part")) return message.opt("payload") as? String
        val part = message.optInt("part", -1)
        val total = message.optInt("total", -1)
        val chunk = message.opt("chunk") as? String ?: return null
        if (total !in 2..192 || part !in 0 until total || chunk.length > CHUNK_CHARS) return null
        val now = SystemClock.elapsedRealtime()
        var transfer = page.transfer
        if (part == 0) {
            transfer = Transfer(rpc, total, now + 1_500)
            page.transfer = transfer
        }
        if (transfer == null || transfer.rpc != rpc || transfer.parts != total ||
            transfer.expiresAt <= now || transfer.nextPart != part ||
            transfer.buffer.length + chunk.length > MAX_PAYLOAD
        ) return null
        transfer.buffer.append(chunk)
        transfer.nextPart++
        if (transfer.nextPart != total) return null
        page.transfer = null
        return transfer.buffer.toString()
    }

    fun reply(page: H5Page, rpc: Long, value: JSONObject?) {
        val envelope = JSONObject().put("document", page.document).put("rpc", rpc)
        val encoded = value?.toString().orEmpty()
        if (encoded.length > MAX_PAYLOAD) {
            page.proxy.postMessage(envelope.toString())
            return
        }
        if (encoded.length <= CHUNK_CHARS) {
            page.proxy.postMessage(envelope.put("value", value ?: JSONObject.NULL).toString())
            return
        }
        val chunks = ArrayList<String>()
        var start = 0
        while (start < encoded.length) {
            var end = minOf(start + CHUNK_CHARS, encoded.length)
            if (end < encoded.length && encoded[end - 1].isHighSurrogate()) end--
            chunks.add(encoded.substring(start, end))
            start = end
        }
        envelope.put("total", chunks.size)
        for ((index, chunk) in chunks.withIndex()) {
            page.proxy.postMessage(envelope.put("part", index).put("chunk", chunk).toString())
        }
    }
}
