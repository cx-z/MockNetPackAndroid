package com.mocknetpack.android.internal.store

import android.content.Context
import com.mocknetpack.android.MockNetPack
import java.util.UUID

/**
 * did 生成与持久化（对齐 iOS DIDStore）。
 * 契约约束：
 * - SDK 生成，App 作用域内唯一；
 * - App 内持久化（重启不变）；
 * - 不依赖设备硬件标识（隐私/合规；同机多 App 各自独立）。
 *
 * 实现：随机 UUID + SharedPreferences；key 带 appID 前缀，不同 App 互不串扰
 * （服务端以 (app, did) 维度隔离）。
 */
object DIDStore {

    private const val PREFS = "mocknetpack"

    private fun key(appId: String) = "mocknetpack.did.v1.$appId"

    /// 取回（或首次生成并持久化）指定 App 的 did。
    fun did(appId: String): String {
        val prefs = MockNetPack.context().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(key(appId), null)?.takeIf { it.isNotEmpty() }?.let { return it }
        val newDID = UUID.randomUUID().toString().lowercase()
        prefs.edit().putString(key(appId), newDID).apply()
        return newDID
    }

    /// 清除指定 App 的 did（仅测试用）。
    fun reset(appId: String) {
        MockNetPack.context().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(key(appId)).apply()
    }
}
