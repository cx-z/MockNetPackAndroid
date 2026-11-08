package com.mocknetpack.android

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.mocknetpack.android.internal.capture.MockNetPackInterceptor
import com.mocknetpack.android.internal.capture.TrafficCaptureController
import com.mocknetpack.android.internal.connection.ConnectionController
import com.mocknetpack.android.internal.scan.QRScannerActivity
import com.mocknetpack.android.internal.store.ServerAddressStore

/**
 * MockNetPackAndroid 公共入口（连接层门面，对齐 iOS MockNetPackKit）。
 *
 * 本 SDK 自身不做 Debug/Release 判断——是否集成、如何隔离构建完全由业务
 * App 决定；接入方要求 Release 产物不含本 SDK，由接入方构建集成层保证
 * （对应 iOS 侧 tasks/-SDK接入要点.md 的要求）。
 *
 * 与 iOS 的必要差异：Android 需要先 `initialize(context)`（SharedPreferences
 * 需要 Context）；拦截器由宿主显式加入 OkHttpClient（见 `interceptor()`）。
 */
object MockNetPack {

    /// SDK 版本号（发布版本；接入方通过 GitHub/JitPack 按版本号集成）。
    /// 与 `gradle.properties` 的 version 严格一致（AN-10 P0 对齐：运行时 1.0.1 = 发布 1.0.1）；
    /// 插件版本同样取此值，禁止混用。
    const val VERSION = "1.0.1"

    /// SDK 自身请求的跳过标记 header（对齐 iOS X-MockNetPack-Skip）。
    const val SKIP_HEADER = "X-MockNetPack-Skip"

    /// 请求/响应体截断上限（契约 v0.2.0：固定 1MB，不做配置项）。
    const val BODY_LIMIT_BYTES = 1_048_576L

    @Volatile
    private var appContext: Context? = null

    /// 扫码连接挂起回调（connectByScan 注册；QRScannerActivity 完成后主线程派发）。
    @Volatile
    private var pendingScanCallback: ((ConnectByScanError?) -> Unit)? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 已初始化的 ApplicationContext（内部使用；未初始化时抛 IllegalStateException）。 */
    internal fun context(): Context = checkNotNull(appContext) {
        "MockNetPack.initialize(context) must be called before any SDK usage"
    }

    /// 宿主 App 的 versionName（注册载荷 appVersion；取不到返回 null）。
    internal fun hostVersionName(): String? {
        return try {
            context().packageManager.getPackageInfo(context().packageName, 0).versionName
        } catch (_: Exception) {
            null
        }
    }

    /// 宿主 App 显示名（注册载荷 appName；Web 设备列表优先展示，回退 null）。
    internal fun hostAppName(): String? {
        return try {
            val appInfo = context().packageManager.getApplicationInfo(context().packageName, 0)
            context().packageManager.getApplicationLabel(appInfo).toString()
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 初始化（幂等）：持有 ApplicationContext，供 SharedPreferences
     * （DIDStore / ServerAddressStore）使用。Android 平台必要差异：
     * iOS 的 UserDefaults 无需上下文，Android 需要。
     */
    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * 返回抓包/Mock 拦截器（OkHttp application interceptor）。
     * 宿主在其 OkHttpClient.Builder 上加入：`OkHttpClient.Builder().addInterceptor(MockNetPack.interceptor())`。
     * 这是 Android 端相对 iOS「swizzle 全局注入、业务零改动」的必要差异：
     * Android 没有可 swizzle 的全局 URL 加载系统，OkHttp 是事实标准；
     * 业务侧接入成本为每个 OkHttpClient 工厂一行（Debug-only）。
     */
    fun interceptor(): okhttp3.Interceptor = singletonInterceptor

    /// 单例拦截器（AN-10 M3a-1）：`install` / 手动 `addInterceptor` 共用同一实例，
    /// 避免每次 `interceptor()` 都 new 一个（同一 client 被重复注入时也不会多份状态）。
    private val singletonInterceptor: okhttp3.Interceptor by lazy { MockNetPackInterceptor() }

    /// 已注入过的 Builder（弱引用，避免持有宿主 builder）。`install` 按 builder 幂等：
    /// 宿主显式 install + 插件在 `OkHttpClient(Builder)` 构造处织入 install 时，
    /// 同一次构建只加一份拦截器，不会双份上报（AN-10 M3a-1 硬要求）。
    /// 构建期插桩是否生效（AN-10 M3a-2 capabilities）：`install` 若由
    /// `okhttp3.OkHttpClient.<init>` 的栈帧触发，说明插件织入成功。
    private val pluginInstrumented = java.util.concurrent.atomic.AtomicBoolean(false)

    /// SDK 能力声明（随注册上报，平台设备页展示"能抓到什么"）。
    internal val capabilities: com.mocknetpack.android.SdkCapabilities
        get() = com.mocknetpack.android.SdkCapabilities(
            adapters = listOf("okhttp", "httpurlconnection", "business"),
            instrumented = pluginInstrumented.get(),
        )

    private val installedBuilders =
        java.util.Collections.synchronizedMap(
            java.util.WeakHashMap<okhttp3.OkHttpClient.Builder, Boolean>(),
        )

    /// 供插桩插件在 `OkHttpClient(Builder)` 构造入口调用：给该 builder 注入采集拦截器。
    ///
    /// - 幂等：同一个 Builder 多次 install 只生效一次（宿主显式调用 + 插件织入不重复）；
    /// - [decorator] 可选：宿主用自己的拦截器包住 SDK 拦截器（如解码/Mock 之外的
    ///   业务加解密），SDK 仍在内层，语义与手动 `addInterceptor(Crypto(interceptor()))`
    ///   一致；
    /// - fail-open：任何异常只打日志，不影响宿主构建 client。
    @JvmOverloads
    @JvmStatic
    fun install(
        builder: okhttp3.OkHttpClient.Builder,
        decorator: ((okhttp3.Interceptor) -> okhttp3.Interceptor)? = null,
    ) {
        if (installedBuilders.put(builder, true) != null) return
        try {
            val sdk = singletonInterceptor
            builder.addInterceptor(decorator?.invoke(sdk) ?: sdk)
            // 首次 install 时判断调用来源：来自 OkHttpClient 构造 = 插件织入生效。
            if (!pluginInstrumented.get()) {
                pluginInstrumented.set(calledFromOkHttpClientConstructor())
            }
            com.mocknetpack.android.internal.log.SDKLogger.info(
                "interceptor installed (v$VERSION, decorator=${decorator != null})",
                category = "install",
            )
        } catch (t: Throwable) {
            installedBuilders.remove(builder)
            com.mocknetpack.android.internal.log.SDKLogger.warn(
                "install failed, skip instrumentation: ${t.javaClass.simpleName}",
                category = "install",
            )
        }
    }

    /**
     * HttpURLConnection 采集入口（AN-10 M2）：包一层再返回同一个语义的连接。
     *
     * 接入方式（两行）：
     * ```kotlin
     * val raw = URL(url).openConnection() as HttpURLConnection
     * val conn = MockNetPack.wrap(raw)   // 先 wrap，再设置请求属性 / 写 body
     * ```
     *
     * 语义保证：
     * - SDK 未接管（无有效会话、也不在启动暂存期）→ 原样返回，零开销；
     * - 接管时返回包装实例：请求体边写边抄样本、响应体边读边抄样本，都不缓冲
     *   全量、不改变字节，调用方读写到的内容与未包装时一致；
     * - 只在首个终止事件（读完 EOF / close / disconnect / 连接失败）上报一条
     *   记录，重复调用不会重复上报（adapter=`httpurlconnection`）。
     *
     * 注意：包装实例是 `HttpURLConnection` 子类，`is HttpsURLConnection` 判断
     * 会失效；Go 侧 `openConnection()` 覆盖不到的库内部连接仍需各自接入。
     */
    @JvmStatic
    fun wrap(connection: java.net.HttpURLConnection): java.net.HttpURLConnection =
        com.mocknetpack.android.internal.capture.UrlConnectionCapture.wrap(connection)

    /**
     * 同 [wrap]，但显式指定来源标识（默认 `httpurlconnection`）。
     * 业务把同一个包装器复用到自研栈时，可用自定义值（如 `cronet`）区分来源。
     */
    @JvmStatic
    fun wrap(connection: java.net.HttpURLConnection, adapter: String): java.net.HttpURLConnection =
        com.mocknetpack.android.internal.capture.UrlConnectionCapture.wrap(connection, adapter)

    /**
     * 业务自报一条 HTTP 交换（AN-10 D4a 的 `business` 来源）：供 SDK 无法注入的
     * 栈（Cronet / 自研长连接 / 三方 SDK 内部 client）在拿到请求响应后主动上报。
     *
     * 只在 SDK 接管期（有效会话或启动暂存）生效，其余情况静默丢弃；上报字段
     * 与抓包条目同构（body 走同一套 1MB 采样与 base64 规则），adapter 默认
     * `business`。该 API 不参与 Mock 匹配，只做采集。
     */
    @JvmOverloads
    @JvmStatic
    fun reportHttp(
        method: String,
        url: String,
        requestHeaders: Map<String, List<String>> = emptyMap(),
        requestBody: ByteArray? = null,
        statusCode: Int? = null,
        responseHeaders: Map<String, List<String>> = emptyMap(),
        responseBody: ByteArray? = null,
        durationMs: Int = 0,
        error: String? = null,
        adapter: String = "business",
    ) {
        val generation = com.mocknetpack.android.internal.capture.TrafficCaptureController
            .httpCaptureGeneration ?: return
        com.mocknetpack.android.internal.capture.HttpExchangeRecorder.record(
            com.mocknetpack.android.internal.capture.CapturedExchange(
                method = method,
                url = url,
                requestHeaders = requestHeaders,
                requestBodySample = requestBody,
                requestBodyFullSize = requestBody?.size?.toLong() ?: 0,
                statusCode = statusCode,
                responseHeaders = responseHeaders,
                responseBodySample = responseBody,
                responseBodyFullSize = responseBody?.size?.toLong() ?: 0,
                error = error,
                durationMs = durationMs,
                adapter = adapter.ifBlank { "business" },
            ),
            generation,
        )
    }

    /**
     * 无参启动：读取扫码连接持久化的服务器地址直连（DIDStore 设备标识复用）。
     * 幂等：已在运行则忽略。
     *
     * **调用方不应当在代码中写死服务器地址**——服务器地址的唯一来源是扫码连接
     * （`connectByScan` 成功后 SDK 持久化地址，重启免扫码 A2）；未配置时进入
     * UNCONFIGURED 状态（不发起网络，等待扫码）。
     *
     * @param did 业务方提供的设备 ID（可选；传入后 SDK 不再自行生成 did）。
     */
    fun start(did: String? = null) {
        val app = context().packageName
        val saved = ServerAddressStore.load(app)
        if (saved != null) {
            startWithServer(server = saved, did = did)
        } else {
            ConnectionController.INSTANCE.startUnconfigured(
                appID = app,
                externalDID = did,
                appVersion = hostVersionName() ?: "",
                appName = hostAppName(),
                sdkVersion = VERSION,
                osVersion = android.os.Build.VERSION.RELEASE ?: "",
                capabilities = capabilities,
                logHandler = null,
            )
        }
    }

    /**
     * 带服务器地址启动（**internal**）：仅扫码流程（QRScannerActivity →
     * ConnectByScanFlow）在注册成功并持久化地址后使用。
     * 调用方业务代码不可直接注入服务器地址——请使用无参 `start(did)`。
     *
     * @param server 服务器 base URL（形如 `http://host:4290/api/v1`）。
     * @param appId App 标识，默认取 applicationId；did 持久化的作用域。
     * @param did 设备 ID（扫码流程传入 DIDStore 已生成的 did）。
     * @param appVersion App 版本，默认取 versionName。
     * @param logHandler 日志回调（可选；默认 android.util.Log）。
     */
    internal fun startWithServer(
        server: String,
        appId: String? = null,
        did: String? = null,
        appVersion: String? = null,
        logHandler: ((String) -> Unit)? = null,
    ) {
        val app = appId ?: context().packageName
        val ver = appVersion ?: hostVersionName() ?: ""
        ConnectionController.INSTANCE.start(
            server = server,
            appID = app,
            externalDID = did,
            appVersion = ver,
            appName = hostAppName(),
            sdkVersion = VERSION,
            osVersion = android.os.Build.VERSION.RELEASE ?: "",
            capabilities = capabilities,
            logHandler = logHandler,
        )
    }

    /// 停止连接层：停心跳、清状态（did 保留，下次 start 复用）。
    fun stop() {
        ConnectionController.INSTANCE.stop()
    }

    /// 连接层是否在运行。
    val isRunning: Boolean get() = ConnectionController.INSTANCE.isRunning

    /// 本设备 did（start 后可用；App 作用域内唯一、重启不变）。
    val did: String? get() = ConnectionController.INSTANCE.did

    /// 连接状态（UNCONFIGURED / CONNECTING / CONNECTED / OFFLINE / TOKEN_INVALID）。
    val connectionState: ConnectionState get() = ConnectionController.INSTANCE.connectionState

    /// 会话状态（IDLE / CAPTURING），由心跳响应 session 字段推导。
    val sessionState: SessionState get() = ConnectionController.INSTANCE.sessionState

    /// 会话状态变化回调（主线程派发）。
    var onSessionStateChange: ((SessionState) -> Unit)?
        get() = ConnectionController.INSTANCE.onSessionStateChange
        set(value) {
            ConnectionController.INSTANCE.onSessionStateChange = value
        }

    /// 连接状态变化回调（主线程派发）。
    var onConnectionStateChange: ((ConnectionState) -> Unit)?
        get() = ConnectionController.INSTANCE.onConnectionStateChange
        set(value) {
            ConnectionController.INSTANCE.onConnectionStateChange = value
        }

    /// 已持久化的服务器地址（start() 无参直连用；扫码成功后写入；Debug 页展示用）。
    val savedServerURL: String?
        get() = ServerAddressStore.load(context().packageName)

    /// 清除已持久化的服务器地址（Debug 页「重置服务器」→ 回到 UNCONFIGURED）。
    fun resetServer() {
        ServerAddressStore.clear(context().packageName)
    }

    /**
     * 注册一个二进制协议编解码器。一次注册同时接管两条链路：
     * - `responseDecrypt`（展示链路）：真实响应密文 → 业务解密 → 可读文本；
     * - `responseEncrypt`（回放链路）：编辑文本 → UTF-8 → 按 compression 压缩 → 业务加密。
     * 匹配：按注册顺序，contentType（大小写不敏感）包含 contentTypeKey 即命中，首个命中生效。
     * 可注册多个 codec（不同 contentTypeKey）。
     */
    fun registerBinaryCodec(
        contentTypeKey: String,
        compression: BinaryCompression,
        responseEncrypt: (ByteArray) -> ByteArray?,
        responseDecrypt: (ByteArray) -> ByteArray?,
        requestEncrypt: ((ByteArray) -> ByteArray?)? = null,
        requestDecrypt: ((ByteArray) -> ByteArray?)? = null,
    ) {
        TrafficCaptureController.registerBinaryCodec(
            contentTypeKey = contentTypeKey,
            compression = compression,
            responseEncrypt = responseEncrypt,
            responseDecrypt = responseDecrypt,
            requestEncrypt = requestEncrypt,
            requestDecrypt = requestDecrypt,
        )
    }

    /**
     * 扫码连接：启动 CameraX 扫码界面 → 解析校验 → 携带配对令牌注册
     * （一次完成可达性 + 令牌校验）→ 成功则停旧连接、持久化地址并启动直连。
     * 失败（R1.6）不覆盖已保存地址。
     *
     * 结果经 QRScannerActivity → deliverScanResult 回传本回调（主线程）。
     * 相机权限由扫码界面运行时请求（拒绝 → CAMERA_DENIED）。
     */
    fun connectByScan(
        activity: android.app.Activity,
        completion: (ConnectByScanResult) -> Unit,
    ) {
        pendingScanCallback = { error ->
            completion(
                if (error == null) ConnectByScanResult.Success
                else ConnectByScanResult.Failure(error),
            )
        }
        activity.startActivity(Intent(activity, QRScannerActivity::class.java))
    }

    /// 扫码结果通道：QRScannerActivity 完成时调用（主线程派发宿主回调，幂等）。
    internal fun deliverScanResult(error: ConnectByScanError?) {
        val callback = pendingScanCallback
        pendingScanCallback = null
        mainHandler.post { callback?.invoke(error) }
    }

    /// 兜底：用户返回未完成扫码时由扫码界面 onDestroy 调用（仅当结果未送达）。
    internal fun deliverScanResultIfPending(error: ConnectByScanError) {
        if (pendingScanCallback != null) deliverScanResult(error)
    }
}

/// install 是否由 OkHttpClient 的构造栈帧触发（插件织入的判据）。
private fun calledFromOkHttpClientConstructor(): Boolean =
    try {
        Throwable().stackTrace.any {
            it.className == "okhttp3.OkHttpClient" && it.methodName == "<init>"
        }
    } catch (_: Throwable) {
        false
    }
