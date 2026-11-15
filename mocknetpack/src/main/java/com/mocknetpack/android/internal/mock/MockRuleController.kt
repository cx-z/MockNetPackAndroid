package com.mocknetpack.android.internal.mock

import com.mocknetpack.android.MediaRef
import com.mocknetpack.android.MockResponse
import com.mocknetpack.android.MockRule
import com.mocknetpack.android.MockRuleList
import com.mocknetpack.android.internal.connection.ConnectionClient
import com.mocknetpack.android.internal.connection.ConnectionClientError
import com.mocknetpack.android.internal.log.SDKLogger
import com.mocknetpack.android.internal.store.MockRuleSnapshotStore
import org.json.JSONArray
import org.json.JSONObject

/**
 * 本地 Mock 规则控制器（对齐 iOS MockRuleController）：持有当前生效规则
 * 快照，供 MockNetPackInterceptor 匹配回包。
 *
 * 规则匹配与快照行为：
 * - 快照来自心跳后按 `rulesVersion` 增量拉取 `GET .../mock-rules?sinceVersion=`。
 *   仅服务端 effective=true 的规则会被下发并参与匹配（契约保证，SDK 不重复过滤）。
 * - 匹配键 = Method（大小写不敏感）+ URL 路径（不含 Query/Body）；不区分 host。
 * - **fail-open A**：从未拉到快照时拉取失败 → 不 Mock，走真实网络；
 *   已有快照后本次拉取失败 → 沿用旧快照继续 Mock。
 * - **快照持久化**：同步成功的快照落盘（MockRuleSnapshotStore）；冷启动懒加载，
 *   使启动握手期（规则下载前）的业务请求也能命中上次同步的快照。
 * - **断连保护**：连接失败达阈值 / 永久拒绝时 `clearSnapshot()` 清空内存+磁盘，
 *   连接恢复后以 sinceVersion=0 重新从服务端下载（旧快照不恢复）。
 * - 规则跨会话保留；Interceptor 的接管判定跟随门闩（capturing 或 启动缓冲），
 *   idle 时本控制器即便有快照也不会被询问。
 */
object MockRuleController {

    private val lock = Any()

    /// 本地已生效快照对应的服务端规则版本。
    private var localVersion = 0

    private var rules: List<MockRule> = emptyList()

    /// 持久化 Store：默认 null，`ensureLoaded()` 首次使用时能力探测创建
    /// （无 Android context 的单测环境降级为 null → 仅内存快照、不落盘）。
    @Volatile
    internal var snapshotStore: MockRuleSnapshotStore? = null

    /// 是否已尝试过磁盘加载（幂等，进程内仅一次；reset() 后重置，同进程
    /// stop→start 可重新从磁盘加载）。
    @Volatile
    private var loaded = false

    // MARK: - 快照加载与持久化

    /// 首次使用前从磁盘加载快照（幂等；失败则保持空快照，不 Mock）。
    /// 整个 构造→加载→解析→赋值 在锁内完成：与 `clearSnapshot()`（同锁）
    /// 互斥，避免「加载读到了旧磁盘、clear 已清内存、随后又写回」的复活竞态。
    /// 磁盘读/清为一次性懒加载路径（冷启动早期无并发），锁内毫秒级可接受。
    private fun ensureLoaded() {
        if (loaded) return
        synchronized(lock) {
            if (loaded) return
            loaded = true
            // ① 构造/探测 store：无 Android context（单测）或 prefs 不可用 →
            // 禁用持久化，仅内存快照（fail-open，不挡业务）。
            val store: MockRuleSnapshotStore
            try {
                val s = snapshotStore ?: MockRuleSnapshotStore()
                snapshotStore = s
                store = s
            } catch (t: Throwable) {
                snapshotStore = null
                return
            }
            // ② 加载 + 解析 + 赋值：结构合法但字段非法（契约损坏）时保留 store、
            // 清掉坏文件，下次同步可正常重写磁盘——避免坏快照永久禁用持久化。
            try {
                val json = store.load() ?: return
                val list = parseRuleList(json)
                localVersion = list.version
                rules = list.rules
                SDKLogger.info("rules snapshot loaded version=${list.version} active=${list.rules.size}", category = "mock")
            } catch (t: Throwable) {
                SDKLogger.error("rules snapshot load failed: ${t.message}; clearing corrupt snapshot", category = "mock")
                try {
                    store.clear()
                } catch (_: Throwable) {
                    // 清坏文件失败不阻断：保持空快照继续运行。
                }
            }
        }
    }

    /// 断连保护：清空内存 + 磁盘快照（连接失败达阈值 / 永久拒绝时调用）。
    /// 清空后 match 必返回 null → 不再 Mock；连接恢复后 syncIfNeeded 以
    /// sinceVersion=0 重新从服务端下载全量规则（用户决策：旧快照不恢复）。
    /// 内存 + 磁盘清除均持锁：与 ensureLoaded()（锁内加载+解析+赋值）互斥，
    /// 杜绝「清内存后、清磁盘前」窗口内读回旧盘导致规则复活。
    /// SharedPreferences apply() 为异步写（内存 map 立即更新），锁内毫秒级，
    /// 且 clearSnapshot 仅断连保护/永久拒绝低频路径调用，可接受。
    internal fun clearSnapshot() {
        synchronized(lock) {
            localVersion = 0
            rules = emptyList()
            try {
                snapshotStore?.clear()
            } catch (t: Throwable) {
                SDKLogger.error("rules snapshot clear failed: ${t.message}", category = "mock")
            }
        }
    }

    // MARK: - 增量拉取

    /// 心跳成功后调用：服务端版本与本地不一致时增量拉取，否则 no-op。
    /// 拉取失败按 fail-open A 处理（保留旧快照；首次失败本地仍为空，即不 Mock）。
    /// suspend：内部走 ConnectionClient.get（网络 IO），由心跳协程调用。
    internal suspend fun syncIfNeeded(
        client: ConnectionClient,
        app: String,
        did: String,
        serverVersion: Int,
    ) {
        ensureLoaded()
        val since = pendingPullVersion(serverVersion) ?: return
        try {
            val json = client.get("devices/$app/$did/mock-rules?sinceVersion=$since")
            val list = parseRuleList(json)
            apply(list)
            SDKLogger.info("rules synced version=${list.version} active=${list.rules.size}", category = "mock")
        } catch (e: ConnectionClientError) {
            // fail-open A：拉取失败，沿用旧快照（首次失败则本地仍为空，即不 Mock）。
            SDKLogger.error("rules pull failed: ${e.message}; keeping snapshot", category = "mock")
        }
    }

    /// 返回需要拉取时的 sinceVersion；版本一致返回 null（避免无谓拉取）。
    private fun pendingPullVersion(serverVersion: Int): Int? = synchronized(lock) {
        if (serverVersion == localVersion) null else localVersion
    }

    /// 拉取成功后更新快照（锁内更新 + 锁外持久化）。
    private fun apply(list: MockRuleList) {
        synchronized(lock) {
            localVersion = list.version
            rules = list.rules
        }
        try {
            snapshotStore?.save(list.version, rulesToJsonArray(list.rules))
        } catch (t: Throwable) {
            SDKLogger.error("rules snapshot persist failed: ${t.message}", category = "mock")
        }
    }

    // MARK: - 匹配

    /// 按 Method + URL 路径查找本地生效规则；无匹配返回 null（走真实网络）。
    /// 命中时把规则 ID 写入 response.ruleId，随命中流量上报 matchedRuleId。
    internal fun match(method: String, path: String): MockResponse? {
        ensureLoaded()
        return synchronized(lock) {
            val m = method.uppercase()
            for (r in rules) {
                if (r.method.uppercase() == m && r.path == path) {
                    return@synchronized r.response.copy(ruleId = r.id)
                }
            }
            null
        }
    }

    /// 清空本地快照（连接层 stop 时调用；did 保留，下次会话重新拉取）。
    /// 磁盘快照保留（下次冷启动仍可启动期 Mock）；`loaded=false` 使同进程
    /// stop→start 后首次 match/syncIfNeeded 重新从磁盘加载（对齐冷启动语义）。
    internal fun reset() {
        synchronized(lock) {
            localVersion = 0
            rules = emptyList()
            loaded = false
        }
    }

    /// 测试注入：直接写入快照（绕过网络拉取）。
    internal fun applyForTesting(rules: List<MockRule>, version: Int) {
        synchronized(lock) {
            localVersion = version
            this.rules = rules
        }
    }

    // MARK: - 契约 JSON 解析（org.json 手写，字段名对齐契约 MockRuleList）

    internal fun parseRuleList(json: JSONObject): MockRuleList {
        val version = json.optInt("version", 0)
        val arr = json.optJSONArray("rules") ?: JSONArray()
        val list = mutableListOf<MockRule>()
        for (i in 0 until arr.length()) {
            list.add(parseRule(arr.getJSONObject(i)))
        }
        return MockRuleList(version = version, rules = list)
    }

    private fun parseRule(json: JSONObject): MockRule {
        return MockRule(
            id = json.optString("id"),
            method = json.optString("method"),
            path = json.optString("path"),
            response = parseResponse(json.optJSONObject("response")),
            enabled = json.optBoolean("enabled", false),
            effective = json.optBoolean("effective", false),
        )
    }

    private fun parseResponse(json: JSONObject?): MockResponse {
        if (json == null) return MockResponse(statusCode = 200)
        val headers = json.optJSONObject("headers")?.let { obj ->
            val map = mutableMapOf<String, String>()
            for (k in obj.keys()) map[k] = obj.optString(k)
            map
        }
        return MockResponse(
            statusCode = json.optInt("statusCode", 200),
            headers = headers,
            body = json.optString("body").takeIf { !json.isNull("body") },
            bodyBase64 = json.optString("bodyBase64").takeIf { !json.isNull("bodyBase64") },
            // 服务端 omitempty：仅 delayMs>0 时下发；缺 key/0/null 一律视为无延迟。
            delayMs = json.optInt("delayMs").takeIf { json.has("delayMs") && !json.isNull("delayMs") && it > 0 },
            // 大图规则只带 media 引用（老服务端无该字段 → null，走原路径）。
            media = json.optJSONObject("media")?.let { m ->
                MediaRef(
                    sha256 = m.optString("sha256"),
                    size = m.optLong("size", 0),
                    contentType = m.optString("contentType").takeIf { m.has("contentType") && !m.isNull("contentType") },
                )
            },
        )
    }

    // MARK: - 快照序列化（持久化用；字段对齐契约 MockRule，ruleId 不落盘）

    /// 规则集 → JSON 数组（供 MockRuleSnapshotStore 持久化）。
    internal fun rulesToJsonArray(rules: List<MockRule>): JSONArray {
        val arr = JSONArray()
        for (r in rules) arr.put(ruleToJson(r))
        return arr
    }

    private fun ruleToJson(r: MockRule): JSONObject {
        val resp = JSONObject().put("statusCode", r.response.statusCode)
        r.response.headers?.let { h ->
            val ho = JSONObject()
            for ((k, v) in h) ho.put(k, v)
            resp.put("headers", ho)
        }
        r.response.body?.let { resp.put("body", it) }
        r.response.bodyBase64?.let { resp.put("bodyBase64", it) }
        r.response.delayMs?.let { resp.put("delayMs", it) }
        // 快照持久化必须带上 media，否则冷启动回放会退化成空 body。
        r.response.media?.let { m ->
            resp.put(
                "media",
                JSONObject().put("sha256", m.sha256).put("size", m.size).apply {
                    m.contentType?.let { put("contentType", it) }
                },
            )
        }
        return JSONObject()
            .put("id", r.id)
            .put("method", r.method)
            .put("path", r.path)
            .put("response", resp)
            .put("enabled", r.enabled)
            .put("effective", r.effective)
    }
}
