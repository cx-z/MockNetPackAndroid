package com.mocknetpack.android.internal.capture

import com.mocknetpack.android.MediaRef
import com.mocknetpack.android.internal.connection.ConnectionClient
import com.mocknetpack.android.internal.log.SDKLogger

/**
 * M15 大图字节治理：按 media ref 拉取规则 blob，并做进程内 LRU 缓存。
 *
 * 语义（对齐 iOS RuleMediaCache）：
 * - 规则 `MockResponse.media` 存在时，回放前用
 *   `GET devices/{app}/{did}/mock-rules/{ruleId}/media` 取原始字节。
 * - **LRU**：key = media.sha256（规则编辑后字节变化 → 新 key，不会拿到旧图），
 *   按字节上限（32MB）淘汰最久未用；重复回放同一图片不再走网络。
 * - **失败不吞**：返回 null 由调用方 fail-open —— 记一条带 error 的命中流量并
 *   转发真实请求，业务侧与「未命中规则」表现一致。
 *
 * 线程安全：缓存由 [lock] 保护的 accessOrder LinkedHashMap（命中即移到尾部），
 * 锁内只做内存操作；网络 IO 在调用方线程（OkHttp 拦截线程）同步执行，不持锁。
 */
internal object RuleMediaCache {

    /// 下载客户端工厂：测试注入指向 MockWebServer 的 client。
    @Volatile
    var clientFactory: ((String) -> ConnectionClient)? = null

    /// 缓存字节上限（执行文档口径 32MB，可注入便于测试淘汰）。
    @Volatile
    var maxCacheBytes: Long = 32L * 1024 * 1024

    private val lock = Any()

    /// accessOrder=true：命中即移到尾部，迭代器头部恒为最久未用。
    private val cache = LinkedHashMap<String, ByteArray>(16, 0.75f, true)
    private var cachedBytes = 0L

    /// 按规则取字节；失败返回 null（调用方 fail-open：记 error 日志 + 转发真实请求）。
    internal fun load(ruleID: String, media: MediaRef): ByteArray? {
        synchronized(lock) { cache[media.sha256]?.let { return it } }
        val ctx = TrafficCaptureController.mediaReplayContext ?: return null
        val client = clientFactory?.invoke(ctx.serverURL) ?: ConnectionClient.create(ctx.serverURL)
        return try {
            val data = client.getBytesBlocking("devices/${ctx.app}/${ctx.did}/mock-rules/$ruleID/media")
            if (media.size > 0 && data.size.toLong() != media.size) {
                SDKLogger.error("media size mismatch rule=$ruleID got=${data.size} want=${media.size}", "mock")
                null
            } else {
                store(media.sha256, data)
                data
            }
        } catch (e: Exception) {
            SDKLogger.error("media fetch failed rule=$ruleID: ${e.message}", "mock")
            null
        }
    }

    /// 清空缓存（stop / 测试用）。
    internal fun reset() {
        synchronized(lock) {
            cache.clear()
            cachedBytes = 0
        }
    }

    /// 当前缓存字节数（测试断言用）。
    internal val cachedByteCount: Long
        get() = synchronized(lock) { cachedBytes }

    // MARK: - 缓存实现（调用方不得持锁）

    private fun store(sha256: String, data: ByteArray) {
        if (data.isEmpty()) return
        synchronized(lock) {
            cache.remove(sha256)?.let { cachedBytes -= it.size }
            cache[sha256] = data
            cachedBytes += data.size
            // 单条即可超上限时也要先放入（否则大图永远无法缓存），随后淘汰其余。
            while (cachedBytes > maxCacheBytes && cache.size > 1) {
                val eldest = cache.entries.iterator().next()
                if (eldest.key == sha256) break
                cache.remove(eldest.key)
                cachedBytes -= eldest.value.size
            }
            if (cachedBytes > maxCacheBytes) {
                cache.remove(sha256)
                cachedBytes -= data.size
            }
        }
    }
}
