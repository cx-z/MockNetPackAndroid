package com.mocknetpack.android.internal.store

import android.content.Context
import com.mocknetpack.android.MockNetPack

/**
 * 服务器地址持久化（对齐 iOS ServerAddressStore）。
 *
 * 扫码连接的核心收益：客户端不再写死服务器地址。扫码成功后地址写入本地，
 * 之后 start() 无参直连、重启免扫码（A2）；地址变更只需重新扫码（A3 切换）。
 *
 * 与 DIDStore 同模式：SharedPreferences + appID 前缀 key，不同 App 互不串扰。
 * 协议：值存 URL 的字符串；非法/空值按未配置处理。
 */
object ServerAddressStore {

    private const val PREFS = "mocknetpack"

    private fun key(appId: String) = "mocknetpack.server.v1.$appId"

    /// 保存服务器地址。
    fun save(server: String, appId: String) {
        MockNetPack.context().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(key(appId), server).apply()
    }

    /// 读取已保存的服务器地址；未配置/值非法返回 null。
    fun load(appId: String): String? {
        val raw = MockNetPack.context().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(key(appId), null) ?: return null
        if (raw.isEmpty()) return null
        val uri = try { java.net.URI(raw) } catch (_: Exception) { return null }
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        val host = uri.host ?: return null
        if (host.isEmpty()) return null
        return raw
    }

    /// 清除服务器地址（Debug 页「重置服务器」）。
    fun clear(appId: String) {
        MockNetPack.context().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(key(appId)).apply()
    }
}
