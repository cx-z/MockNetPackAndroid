package com.mocknetpack.android.internal.mock

import com.mocknetpack.android.MockResponse
import com.mocknetpack.android.MockRule
import com.mocknetpack.android.MockRuleList
import com.mocknetpack.android.internal.connection.ConnectionClient
import com.mocknetpack.android.internal.connection.ConnectionClientError
import com.mocknetpack.android.internal.log.SDKLogger
import org.json.JSONArray
import org.json.JSONObject

/**
 * 本地 Mock 规则控制器（对齐 iOS MockRuleController）：持有当前生效规则
 * 快照，供 MockNetPackInterceptor 匹配回包。
 *
 * 语义（需求 6.5/6.6 + 决策 E1/fail-open A）：
 * - 快照来自心跳后按 `rulesVersion` 增量拉取 `GET .../mock-rules?sinceVersion=`。
 *   仅服务端 effective=true 的规则会被下发并参与匹配（契约保证，SDK 不重复过滤）。
 * - 匹配键 = Method（大小写不敏感）+ URL 路径（不含 Query/Body）；不区分 host。
 * - **fail-open A**：从未拉到快照时拉取失败 → 不 Mock，走真实网络；
 *   已有快照后本次拉取失败 → 沿用旧快照继续 Mock。
 * - 规则跨会话保留，但仅在抓包会话 capturing 期间生效——Interceptor 的
 *   会话开关跟随心跳，idle 时本控制器即便有快照也不会被询问。
 */
object MockRuleController {

    private val lock = Any()

    /// 本地已生效快照对应的服务端规则版本。
    private var localVersion = 0

    private var rules: List<MockRule> = emptyList()

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

    /// 拉取成功后更新快照（同步加锁）。
    private fun apply(list: MockRuleList) {
        synchronized(lock) {
            localVersion = list.version
            rules = list.rules
        }
    }

    // MARK: - 匹配

    /// 按 Method + URL 路径查找本地生效规则；无匹配返回 null（走真实网络）。
    /// 命中时把规则 ID 写入 response.ruleId，随命中流量上报 matchedRuleId。
    internal fun match(method: String, path: String): MockResponse? {
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
    internal fun reset() {
        synchronized(lock) {
            localVersion = 0
            rules = emptyList()
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
        )
    }
}
