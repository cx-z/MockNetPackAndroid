package com.mocknetpack.android.internal.store

import android.content.Context
import com.mocknetpack.android.MockNetPack
import org.json.JSONArray
import org.json.JSONObject

/**
 * Mock 规则快照持久化，用于冷启动时匹配请求。
 *
 * 与 ServerAddressStore 同模式：SharedPreferences + 懒加载 prefs（构造不碰
 * context，单测无 Android context 时可安全构造；首次 IO 失败由调用方降级）。
 *
 * 语义：
 * - 快照 = {version, rules:[...]}（effective=true 规则集，字段对齐契约 MockRule）。
 * - 冷启动时加载 → 启动握手期即可用上次同步的快照匹配 Mock（规则同步在心跳后，
 *   冷启动进程内快照为空是「启动早请求无法 Mock」的根因）。
 * - 断连保护：连接失败达阈值（见 ConnectionController）调用 clear() 永久清除
 *   （内存+磁盘），连接恢复后以 sinceVersion=0 重新从服务端下载。
 */
internal class MockRuleSnapshotStore {

    private val prefs by lazy {
        MockNetPack.context().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    /// 保存快照（版本 + 规则集 JSON 数组）。
    fun save(version: Int, rulesJson: JSONArray) {
        prefs.edit()
            .putString(KEY, JSONObject().put("version", version).put("rules", rulesJson).toString())
            .apply()
    }

    /// 读取快照；无快照/损坏返回 null（调用方保持空快照，不 Mock）。
    fun load(): JSONObject? {
        val raw = prefs.getString(KEY, null) ?: return null
        if (raw.isEmpty()) return null
        return try {
            JSONObject(raw)
        } catch (e: Exception) {
            null
        }
    }

    /// 清除快照（断连保护 / 永久拒绝时调用）。
    fun clear() {
        prefs.edit().remove(KEY).apply()
    }

    private companion object {
        const val PREFS = "mocknetpack.mock.rules.v1"
        const val KEY = "snapshot"
    }
}
