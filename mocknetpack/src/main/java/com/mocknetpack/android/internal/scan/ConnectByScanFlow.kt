package com.mocknetpack.android.internal.scan

import com.mocknetpack.android.ConnectByScanError
import com.mocknetpack.android.internal.connection.ConnectionClient
import com.mocknetpack.android.internal.connection.ConnectionClientError
import com.mocknetpack.android.internal.log.SDKLogger
import kotlinx.coroutines.delay
import org.json.JSONObject

/**
 * 扫码连接核心流程（对齐 iOS `connectByScan(scanner:makeClient:)` 核心，
 * 独立于 UI 便于本地单测）。
 *
 * 流程：可达性探测（重试给权限/网络就绪留时间）→ 令牌注册（可达性 + 令牌
 * 校验二合一）→ 成功：停旧连接 → 持久化服务器地址（重启免扫码 A2）→ 直连启动。
 *
 * 失败分类：
 * - 注册 403 → TOKEN_INVALID（提示刷新二维码）；
 * - 其余失败/探测失败 → UNREACHABLE；
 * - 失败一律不覆盖已保存地址（R1.6）。
 */
internal object ConnectByScanFlow {

    /// 默认探测重试次数与间隔（对齐 iOS probeLocalNetwork 4 次 × 1.5s）。
    private const val PROBE_RETRY_COUNT = 4
    private const val PROBE_RETRY_DELAY_MS = 1_500L

    internal suspend fun execute(
        payload: QRConnectPayload,
        appID: String,
        did: String,
        appVersion: String,
        appName: String?,
        sdkVersion: String,
        osVersion: String,
        clientFactory: (String) -> ConnectionClient,
        probeRetryCount: Int = PROBE_RETRY_COUNT,
        probeRetryDelayMs: Long = PROBE_RETRY_DELAY_MS,
        onStop: () -> Unit,
        onSave: (serverURL: String, appID: String) -> Unit,
        onStart: (serverURL: String, appID: String, did: String) -> Unit,
        onResult: (ConnectByScanError?) -> Unit,
    ) {
        val client = clientFactory(payload.serverURL)

        // 1) 本地网络可达性探测：轻量 GET（必然 404 路径），收到任何 HTTP 响应
        //    即视为连通；传输层错误重试给权限/网络就绪留时间（对齐 iOS）。
        if (!probeWithRetry(client, probeRetryCount, probeRetryDelayMs)) {
            SDKLogger.error("scan connect: server unreachable; not saving address", category = "scan")
            onResult(ConnectByScanError.UNREACHABLE)
            return
        }

        // 2) 令牌注册（可达性 + 令牌校验二合一）。did 复用：与启动注册同一 did。
        val registerSucceeded = try {
            val body = JSONObject()
                .put("app", appID)
                .put("did", did)
                .put("platform", "android")
                .put("osVersion", osVersion)
                .put("sdkVersion", sdkVersion)
                .put("appVersion", appVersion)
                .put("appName", appName ?: JSONObject.NULL)
                .put("pairingToken", payload.token)
            client.post("devices/register", body)
            true
        } catch (e: ConnectionClientError.HttpStatus) {
            if (e.code == 403) {
                SDKLogger.error("scan connect: pairing token rejected (403)", category = "scan")
                onResult(ConnectByScanError.TOKEN_INVALID)
                return
            }
            false
        } catch (e: ConnectionClientError) {
            SDKLogger.error("scan connect: register failed: ${e.message}", category = "scan")
            false
        }
        if (!registerSucceeded) {
            SDKLogger.error("scan connect: register unreachable; not saving address", category = "scan")
            onResult(ConnectByScanError.UNREACHABLE)
            return
        }

        // 3) 成功：停旧连接（幂等）→ 持久化地址（免扫码 A2）→ 启动直连。
        onStop()
        onSave(payload.serverURL, appID)
        onStart(payload.serverURL, appID, did)
        onResult(null)
    }

    private suspend fun probeWithRetry(
        client: ConnectionClient,
        retryCount: Int,
        retryDelayMs: Long,
    ): Boolean {
        repeat(retryCount) { attempt ->
            try {
                client.probe()
                return true
            } catch (e: ConnectionClientError) {
                SDKLogger.info("scan probe attempt ${attempt + 1}/$retryCount failed: ${e.message}", category = "scan")
                if (attempt < retryCount - 1) delay(retryDelayMs)
            }
        }
        return false
    }
}
