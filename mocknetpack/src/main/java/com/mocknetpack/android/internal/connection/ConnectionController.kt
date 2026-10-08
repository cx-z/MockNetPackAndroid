package com.mocknetpack.android.internal.connection

import com.mocknetpack.android.CaptureSession
import com.mocknetpack.android.ConnectionState
import com.mocknetpack.android.DeviceView
import com.mocknetpack.android.HeartbeatResponse
import com.mocknetpack.android.RegisterDeviceRequest
import com.mocknetpack.android.RegisterDeviceResponse
import com.mocknetpack.android.ServerConfig
import com.mocknetpack.android.SessionState
import com.mocknetpack.android.internal.capture.TrafficCaptureController
import com.mocknetpack.android.internal.log.SDKLogger
import com.mocknetpack.android.internal.mock.MockRuleController
import com.mocknetpack.android.internal.store.DIDStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * 连接层控制器（对齐 iOS ConnectionController）：设备注册、心跳循环、
 * 断线退避、会话状态推导。
 *
 * 设计要点（与 iOS 一致）：
 * - 全部可变状态由 `lock` 保护；调度在专用**单线程**调度器上串行执行
 *   （协程 + inflight 标志），同一时刻至多一个注册/心跳在途。
 * - 心跳间隔以服务端下发 `serverConfig.heartbeatIntervalSeconds` 为准
 *   （钳制 2..300，仅防误配保护；契约实际下发 capturing 3s / idle 5s——
 *   3s 必须放行，否则"快速感知规则变更"被抬成 5s）；失败按指数退避 1s→30s。
 * - 心跳/注册 404（device_not_registered）→ 静默停循环（不弹窗、不重试），
 *   重启 App 后若 Web 已注册则自动恢复。
 * - 注册 403（携带配对令牌时）→ 令牌无效/过期：停循环并上抛
 *   TOKEN_INVALID 可展示状态，不再退避重试同一枚过期令牌。
 * - 代次（generation）：start/stop 递增；在途注册/心跳返回后先校验代次，
 *   旧代结果直接丢弃——防止 stop() 后旧心跳把连接状态"翻活"。
 * - 回调在主线程派发（Dispatchers.Main）。
 */
class ConnectionController internal constructor() {

    companion object {
        val INSTANCE = ConnectionController()

        /// 心跳间隔钳制：服务端下发值放行 2..300（下限 2s 挡误配、不挡契约 3s）。
        internal const val HEARTBEAT_MIN_SECONDS = 2
        internal const val HEARTBEAT_MAX_SECONDS = 300
        /// 退避上限（秒）。
        internal const val BACKOFF_MAX_SECONDS = 30

        /// 默认串行调度器：单线程 IO dispatcher（连接循环同刻至多一个在途）。
        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        private fun defaultScheduler(): CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)
    }

    // MARK: - 测试注入点

    /// 客户端工厂：测试可注入指向 MockServer 的客户端（对齐 iOS clientFactory）。
    var clientFactory: ((String) -> ConnectionClient)? = null
    /// 覆盖服务端下发的心跳间隔（秒）；测试用（0.1s 级加速验证）。null = 服务端下发。
    var heartbeatIntervalOverrideSeconds: Double? = null
    /// 初始退避基数（秒）；测试可调小。
    var backoffBaseSeconds: Double = 1.0
    /// 串行调度器；默认单线程，测试可替换（如 Unconfined）。
    @Volatile
    internal var schedulerDispatcher: CoroutineDispatcher = defaultScheduler()
    /// 回调主线程派发器；测试经 Dispatchers.setMain 替换。
    @Volatile
    internal var mainDispatcher: CoroutineDispatcher = Dispatchers.Main

    private val lock = Any()

    private var isRunningValue = false
    /// 连接代次：start/stop 时递增。在途注册/心跳返回后先校验代次，旧代结果直接丢弃。
    private var generationValue = 0
    private var serverURLValue: String? = null
    private var appIDValue: String? = null
    private var appVersionValue: String? = null
    private var appNameValue: String? = null
    private var sdkVersionValue: String = ""
    private var osVersionValue: String = ""
    private var didValue: String? = null
    private var logHandler: ((String) -> Unit)? = null

    /// 扫码连接：本次连接会话使用的配对令牌与设备显示名（一次性注册通道；
    /// 连接会话内保留——网络抖动重注册时仍可复用，stop/start 时重置）。
    private var pairingTokenValue: String? = null
    private var deviceNameValue: String? = null

    private var serverConfig = ServerConfig(heartbeatIntervalSeconds = 5, heartbeatTimeoutSeconds = 60)
    private var registered = false
    /// 设备未在 Web 注册（register/heartbeat 404）→ 静默停循环，不再重试。
    private var unregistered = false
    private var currentBackoff = 1.0
    private var inflight = false

    private var connectionStateValue: ConnectionState = ConnectionState.OFFLINE
    private var sessionStateValue: SessionState = SessionState.IDLE

    private var scope: CoroutineScope? = null

    // MARK: - 对外回调（主线程派发）

    var onSessionStateChange: ((SessionState) -> Unit)? = null
        get() = synchronized(lock) { field }
        set(value) {
            synchronized(lock) { field = value }
        }

    var onConnectionStateChange: ((ConnectionState) -> Unit)? = null
        get() = synchronized(lock) { field }
        set(value) {
            synchronized(lock) { field = value }
        }

    // MARK: - 生命周期

    /**
     * 启动连接层（幂等：已在运行则直接返回）。did 首次生成后持久化。
     * 技术方案约定 appID 由门面解析（默认 applicationId）后传入。
     */
    fun start(
        server: String,
        appID: String?,
        externalDID: String? = null,
        appVersion: String?,
        appName: String? = null,
        sdkVersion: String,
        osVersion: String,
        pairingToken: String? = null,
        deviceName: String? = null,
        logHandler: ((String) -> Unit)? = null,
    ) {
        synchronized(lock) {
            if (isRunningValue) return
            isRunningValue = true
            generationValue += 1
            serverURLValue = server
            appIDValue = appID
            appVersionValue = appVersion
            appNameValue = appName
            sdkVersionValue = sdkVersion
            osVersionValue = osVersion
            // 业务方传入 did（如业务方 Keychain deviceID）则直接用；否则 SDK 自行生成。
            didValue = externalDID ?: DIDStore.did(appID ?: "")
            this.logHandler = logHandler
            serverConfig = ServerConfig(heartbeatIntervalSeconds = 5, heartbeatTimeoutSeconds = 60)
            registered = false
            unregistered = false
            currentBackoff = backoffBaseSeconds
            pairingTokenValue = pairingToken
            deviceNameValue = deviceName
            scope = CoroutineScope(SupervisorJob() + schedulerDispatcher)
        }

        log("MockNetPack connecting to $server app=${appID ?: "?"} did=${did ?: "?"}")
        setConnectionState(ConnectionState.CONNECTING)
        scheduleNext(now = true)
        // 启动流量采集（会话未激活时不拦截；Android 拦截器已由宿主注入）。
        val resolvedDid = synchronized(lock) { didValue }
        TrafficCaptureController.start(
            serverURL = server,
            appID = appID ?: "",
            did = resolvedDid ?: "",
            logHandler = logHandler,
        )
    }

    /// 无服务器启动：仅解析 did 并置 UNCONFIGURED，不发起网络。
    /// 供 `MockNetPack.start(did?)` 无参门面在未保存地址时调用——保证后续扫码
    /// 复用同一 did（扫码注册与启动注册使用同一设备标识，不因扫码新生成）。
    fun startUnconfigured(
        appID: String?,
        externalDID: String? = null,
        appVersion: String?,
        appName: String? = null,
        sdkVersion: String,
        osVersion: String,
        logHandler: ((String) -> Unit)? = null,
    ) {
        synchronized(lock) {
            if (isRunningValue) return
            isRunningValue = true
            generationValue += 1
            serverURLValue = null
            appIDValue = appID
            appVersionValue = appVersion
            appNameValue = appName
            sdkVersionValue = sdkVersion
            osVersionValue = osVersion
            didValue = externalDID ?: DIDStore.did(appID ?: "")
            this.logHandler = logHandler
            pairingTokenValue = null
            deviceNameValue = null
            registered = false
            unregistered = false
        }

        log("MockNetPack unconfigured: no saved server address (scan to connect)")
        // 预注入采集开关（幂等；未连接时 isCapturing=false 不拦业务，
        // 扫码激活后即全量抓取——首次接入在扫码前创建的 OkHttpClient 也携带拦截器）。
        TrafficCaptureController.arm()
        setConnectionState(ConnectionState.UNCONFIGURED)
    }

    /// 停止连接层：停调度、清状态（did 保留，下次 start 复用）。
    fun stop() {
        val wasRunning = synchronized(lock) {
            if (!isRunningValue) {
                false
            } else {
                isRunningValue = false
                generationValue += 1 // 作废所有在途循环，旧代结果不得再应用
                inflight = false
                pairingTokenValue = null
                deviceNameValue = null
                scope?.cancel()
                scope = null
                true
            }
        }
        if (!wasRunning) return
        log("MockNetPack stopped")
        // 先停止采集（清空待上传批次），再广播 idle 状态；Mock 快照清空
        // （did 保留，下次会话重新拉取）。
        TrafficCaptureController.stop()
        MockRuleController.reset()
        setConnectionState(ConnectionState.OFFLINE)
        setSessionState(SessionState.IDLE)
    }

    // MARK: - 对外只读状态

    val isRunning: Boolean get() = synchronized(lock) { isRunningValue }
    val did: String? get() = synchronized(lock) { didValue }

    /// 当前连接会话的 appID（扫码流程复用与启动一致的 appID/did 作用域）。
    val resolvedAppID: String? get() = synchronized(lock) { appIDValue }

    val connectionState: ConnectionState get() = synchronized(lock) { connectionStateValue }
    val sessionState: SessionState get() = synchronized(lock) { sessionStateValue }

    // MARK: - 调度（串行）

    /// 在串行调度器上调度下一轮注册/心跳；now=true 立即执行。
    private fun scheduleNext(now: Boolean = false, delaySeconds: Double? = null) {
        val s = synchronized(lock) { scope } ?: return
        s.launch {
            if (!now && delaySeconds != null && delaySeconds > 0) {
                delay((delaySeconds * 1000).toLong())
            }
            runIfIdle()
        }
    }

    /// 若无在途任务则开启一轮注册/心跳。捕获当前代次，在途结果只属于该代。
    private suspend fun runIfIdle() {
        val proceed = synchronized(lock) {
            if (!isRunningValue || inflight || unregistered) {
                false
            } else {
                inflight = true
                true
            }
        }
        if (!proceed) return
        val gen = synchronized(lock) { generationValue }
        performCycle(gen)
    }

    /// 单轮：注册（若未注册）→ 心跳 → 处理结果并调度下一轮。
    private suspend fun performCycle(gen: Int) {
        val info = synchronized(lock) { Triple(serverURLValue, appIDValue, didValue) }
        val serverURL = info.first
        val appID = info.second
        val did = info.third
        if (serverURL == null || appID == null || did == null) {
            finishCycle(gen)
            return
        }

        val client = makeClient(serverURL)

        // 1) 需要注册：未注册过（或上次心跳 404 置为未注册）。
        val needRegister = synchronized(lock) { !registered }
        if (needRegister) {
            performRegister(client = client, appID = appID, did = did, gen = gen)
            return // performRegister 内已调度下一轮
        }

        // 2) 心跳。
        try {
            val resp = heartbeat(client = client, appID = appID, did = did)
            // 代次校验：await 返回后 stop() 可能已发生，旧代结果不得回翻状态。
            if (!isCurrent(gen)) {
                finishCycle(gen)
                return
            }
            synchronized(lock) {
                currentBackoff = backoffBaseSeconds
                registered = true
                resp.serverConfig?.let { serverConfig = it }
            }
            setConnectionState(ConnectionState.CONNECTED)
            // 先驱动流量采集启停，再广播会话状态——外部 onSessionStateChange 回调可能
            // 立即发起业务请求，此时采集开关必须已就绪（否则首批请求漏采）。
            TrafficCaptureController.updateSession(
                capturing = resp.session != null,
                sessionID = resp.session?.id,
            )
            setSessionState(if (resp.session != null) SessionState.CAPTURING else SessionState.IDLE)
            // AN-3: 心跳成功后按服务端 rulesVersion 增量拉取 Mock 规则
            // （fail-open A：拉取失败沿用旧快照；首次失败则不 Mock）。
            MockRuleController.syncIfNeeded(
                client = client,
                app = appID ?: "",
                did = did,
                serverVersion = resp.rulesVersion ?: 0,
            )
            finishCycle(gen)
            scheduleNext(delaySeconds = heartbeatInterval())
        } catch (e: ConnectionClientError) {
            if (e is ConnectionClientError.HttpStatus && e.code == 404) {
                // 设备未在 Web 注册（或被删除）→ 静默停循环，不自动重试。
                synchronized(lock) {
                    registered = false
                    unregistered = true
                }
                setConnectionState(ConnectionState.OFFLINE)
                log("heartbeat 404: device not registered; SDK idle (re-register in Web and restart App)")
                finishCycle(gen)
                // 不 scheduleNext。
            } else {
                // 网络错误 / 5xx / 解码失败 → 指数退避重连。
                val backoff = advanceBackoff()
                setConnectionState(ConnectionState.OFFLINE)
                log("heartbeat failed: ${e.message}; retrying in ${backoff.toInt()}s")
                finishCycle(gen)
                scheduleNext(delaySeconds = backoff)
            }
        } catch (e: CancellationException) {
            finishCycle(gen)
            throw e
        }
    }

    // MARK: - 注册

    private suspend fun performRegister(client: ConnectionClient, appID: String, did: String, gen: Int) {
        val meta = synchronized(lock) {
            RegisterDeviceRequest(
                app = appID,
                did = did,
                platform = "android",
                osVersion = osVersionValue,
                sdkVersion = sdkVersionValue,
                appVersion = appVersionValue ?: "",
                appName = appNameValue,
                pairingToken = pairingTokenValue,
                deviceName = deviceNameValue,
            )
        }
        try {
            val resp = parseRegisterResponse(client.post("devices/register", meta.toJson()))
            // 代次校验：旧代注册结果直接丢弃。
            if (!isCurrent(gen)) {
                finishCycle(gen)
                return
            }
            synchronized(lock) {
                registered = true
                serverConfig = resp.serverConfig
            }
            log("registered app=$appID did=$did")
            setConnectionState(ConnectionState.CONNECTED)
            finishCycle(gen)
            // register 成功后立即心跳（去掉原 2s 硬延迟）：LAN 下 ~1s 内完成
            // 启动连接与规则同步。
            scheduleNext(now = true)
        } catch (e: ConnectionClientError) {
            if (!isCurrent(gen)) {
                finishCycle(gen)
                return
            }
            if (e is ConnectionClientError.HttpStatus && e.code == 404) {
                // 服务端拒绝注册（设备未在 Web 手动注册）→ 静默停循环。
                synchronized(lock) {
                    registered = false
                    unregistered = true
                }
                setConnectionState(ConnectionState.OFFLINE)
                log("register 404: device not registered on server; SDK idle (register the device in Web)")
                finishCycle(gen)
                // 不 scheduleNext。
            } else if (e is ConnectionClientError.HttpStatus && e.code == 403) {
                // 携带配对令牌注册收到 403（pairing_token_invalid）→ 令牌无效/
                // 过期（二维码已失效）。停连并上抛可展示状态，不再无限退避重连——
                // 重试同一枚过期令牌永远只会再拿到 403；业务方应提示「二维码已过期，
                // 请刷新」。未携带令牌时的 403 保持原有退避语义。
                val hasToken = synchronized(lock) { pairingTokenValue != null }
                if (hasToken) {
                    synchronized(lock) {
                        registered = false
                        unregistered = true
                    }
                    setConnectionState(ConnectionState.TOKEN_INVALID)
                    log("register 403: pairing token invalid or expired; SDK idle (re-scan a fresh QR)")
                    finishCycle(gen)
                    return // 不 scheduleNext。
                }
                val backoff = advanceBackoff()
                setConnectionState(ConnectionState.OFFLINE)
                log("register httpStatus=403; retrying in ${backoff.toInt()}s")
                finishCycle(gen)
                scheduleNext(delaySeconds = backoff)
            } else if (e is ConnectionClientError.HttpStatus) {
                val backoff = advanceBackoff()
                setConnectionState(ConnectionState.OFFLINE)
                log("register httpStatus=${e.code}; retrying in ${backoff.toInt()}s")
                finishCycle(gen)
                scheduleNext(delaySeconds = backoff)
            } else {
                val backoff = advanceBackoff()
                setConnectionState(ConnectionState.OFFLINE)
                log("register failed: ${e.message}; retrying in ${backoff.toInt()}s")
                finishCycle(gen)
                scheduleNext(delaySeconds = backoff)
            }
        } catch (e: CancellationException) {
            finishCycle(gen)
            throw e
        }
    }

    // MARK: - 心跳

    private suspend fun heartbeat(client: ConnectionClient, appID: String, did: String): HeartbeatResponse {
        val sdk = synchronized(lock) { sdkVersionValue }
        val respJson = client.post(
            "devices/$appID/$did/heartbeat",
            JSONObject().put("sdkVersion", sdk),
        )
        return parseHeartbeatResponse(respJson)
    }

    // MARK: - 辅助

    private fun makeClient(serverURL: String): ConnectionClient {
        clientFactory?.let { return it(serverURL) }
        return ConnectionClient.create(serverURL)
    }

    /// 生效心跳间隔（秒）：测试 override 优先；否则服务端下发值钳制 2..300。
    /// 注意 override 为浮点（测试用 0.1s 级加速），不可经 toInt() 截断（0.1→0 会
    /// 变成无间隔循环）；Int 钳制语义仅供 effectiveHeartbeatIntervalSeconds() 测试断言。
    internal fun effectiveHeartbeatIntervalSeconds(): Int {
        heartbeatIntervalOverrideSeconds?.let { return it.toInt() }
        return synchronized(lock) {
            serverConfig.heartbeatIntervalSeconds.coerceIn(
                HEARTBEAT_MIN_SECONDS,
                HEARTBEAT_MAX_SECONDS,
            )
        }
    }

    private fun heartbeatInterval(): Double {
        heartbeatIntervalOverrideSeconds?.let { return it }
        return synchronized(lock) {
            serverConfig.heartbeatIntervalSeconds.coerceIn(
                HEARTBEAT_MIN_SECONDS,
                HEARTBEAT_MAX_SECONDS,
            ).toDouble()
        }
    }

    /// 返回本次退避时长并翻倍（上限 30s）。
    private fun advanceBackoff(): Double {
        return synchronized(lock) {
            val b = currentBackoff
            currentBackoff = minOf(b * 2, BACKOFF_MAX_SECONDS.toDouble())
            b
        }
    }

    /// 结束一轮循环；仅当代次仍为当前代时才清 inflight（stop→start 快速切换后，
    /// 旧在途循环不得清掉新一轮的 inflight 标志）。
    private fun finishCycle(gen: Int) {
        synchronized(lock) {
            if (generationValue == gen) {
                inflight = false
            }
        }
    }

    /// gen 是否仍是当前连接代次（stop 后即为 false，旧代结果丢弃）。
    private fun isCurrent(gen: Int): Boolean {
        return synchronized(lock) { isRunningValue && generationValue == gen }
    }

    private fun setConnectionState(state: ConnectionState) {
        val changed = synchronized(lock) {
            if (connectionStateValue == state) {
                false
            } else {
                connectionStateValue = state
                true
            }
        }
        if (!changed) return
        val handler = synchronized(lock) { onConnectionStateChange }
        val dispatcher = mainDispatcher
        CoroutineScope(SupervisorJob() + dispatcher).launch { handler?.invoke(state) }
    }

    private fun setSessionState(state: SessionState) {
        val changed = synchronized(lock) {
            if (sessionStateValue == state) {
                false
            } else {
                sessionStateValue = state
                true
            }
        }
        if (!changed) return
        val handler = synchronized(lock) { onSessionStateChange }
        val dispatcher = mainDispatcher
        CoroutineScope(SupervisorJob() + dispatcher).launch { handler?.invoke(state) }
    }

    /// 输出一条 SDK 日志：先经注入的 logHandler（宿主/测试捕获），再落 SDKLogger。
    private fun log(message: String, category: String = "connection") {
        val handler = synchronized(lock) { logHandler }
        handler?.invoke(message)
        SDKLogger.info(message, category)
    }

    // MARK: - 契约 JSON 解析（org.json 手写，对齐 iOS Codable 模型）

    private fun parseRegisterResponse(json: JSONObject): RegisterDeviceResponse {
        val deviceJson = json.optJSONObject("device")
        val scJson = json.optJSONObject("serverConfig")
        return RegisterDeviceResponse(
            device = DeviceView(
                app = deviceJson?.optString("app") ?: "",
                did = deviceJson?.optString("did") ?: "",
                status = deviceJson?.optString("status") ?: "",
                lastSeenAt = deviceJson?.takeIf { !it.isNull("lastSeenAt") }?.optString("lastSeenAt"),
                registeredAt = deviceJson?.takeIf { !it.isNull("registeredAt") }?.optString("registeredAt"),
            ),
            serverConfig = ServerConfig(
                heartbeatIntervalSeconds = scJson?.optInt("heartbeatIntervalSeconds", 5) ?: 5,
                heartbeatTimeoutSeconds = scJson?.optInt("heartbeatTimeoutSeconds", 60) ?: 60,
            ),
        )
    }

    private fun parseHeartbeatResponse(json: JSONObject): HeartbeatResponse {
        val scJson = json.optJSONObject("serverConfig")
        val sessionJson = json.optJSONObject("session")
        return HeartbeatResponse(
            ok = json.optBoolean("ok", false),
            serverTime = json.optString("serverTime", ""),
            serverConfig = scJson?.let {
                ServerConfig(
                    heartbeatIntervalSeconds = it.optInt("heartbeatIntervalSeconds", 5),
                    heartbeatTimeoutSeconds = it.optInt("heartbeatTimeoutSeconds", 60),
                )
            },
            session = sessionJson?.let { parseSession(it) },
            rulesVersion = if (json.has("rulesVersion") && !json.isNull("rulesVersion")) json.optInt("rulesVersion") else null,
        )
    }

    private fun parseSession(json: JSONObject): CaptureSession {
        return CaptureSession(
            id = json.optString("id"),
            app = json.optString("app"),
            did = json.optString("did"),
            status = json.optString("status"),
            startedAt = json.optString("startedAt"),
            endedAt = json.takeIf { !it.isNull("endedAt") }?.optString("endedAt"),
            requestCount = json.optInt("requestCount"),
            viewerCount = json.optInt("viewerCount"),
        )
    }
}

/// 注册请求序列化（org.json 手写；契约字段见 RegisterDeviceRequest）。
private fun RegisterDeviceRequest.toJson(): JSONObject {
    val json = JSONObject()
        .put("app", app)
        .put("did", did)
        .put("platform", platform)
        .put("osVersion", osVersion)
        .put("sdkVersion", sdkVersion)
        .put("appVersion", appVersion)
    appName?.let { json.put("appName", it) }
    pairingToken?.let { json.put("pairingToken", it) }
    deviceName?.let { json.put("deviceName", it) }
    return json
}
