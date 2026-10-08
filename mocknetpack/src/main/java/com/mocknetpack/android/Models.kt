package com.mocknetpack.android

// ============================================================================
// MockNetPackAndroid 连接层/流量/Mock 契约模型
// 对齐 server/openapi/mocknetpack.yaml 与 iOS MockNetPackKit/Sources/Models.swift
// ============================================================================

/// 设备连接状态（SDK 侧视角；服务端另有 derived offline 判定）。
enum class ConnectionState {
    /// 未配置服务器：无已保存地址，start() 无参直接进入，等待扫码连接。
    UNCONFIGURED,
    /// 启动/注册中，尚未确认在线。
    CONNECTING,
    /// 心跳成功，服务端在线。
    CONNECTED,
    /// 心跳失败（网络/5xx），退避重连中。
    OFFLINE,
    /// 配对令牌无效/过期：携带配对令牌注册收到 403 → 停止连接循环并上抛。
    TOKEN_INVALID,
}

/// 抓包会话状态（由心跳响应 session 字段推导；SDK 据此启停采集）。
enum class SessionState {
    /// 待命：无激活会话。
    IDLE,
    /// 抓包中：服务端存在激活会话，SDK 应采集/执行 Mock。
    CAPTURING,
}

/// 二进制协议压缩方式（对齐 iOS BinaryCompression）。
enum class BinaryCompression {
    /// 不压缩（仅 UTF-8 转换 + 业务加解密）。
    NONE,
    /// 标准 gzip（1f 8b 头），由 SDK 内置 GZIPOutputStream 产出。
    GZIP,
}

/// 扫码连接错误分类（对齐 iOS ConnectByScanError）。
enum class ConnectByScanError {
    /// 用户关闭扫码界面。
    CANCELLED,
    /// 相机不可用（模拟器/无摄像头）。
    CAMERA_UNAVAILABLE,
    /// 用户拒绝相机权限。
    CAMERA_DENIED,
    /// 二维码内容不是合法 mocknetpack 连接协议。
    INVALID_PAYLOAD,
    /// 二维码 appID 与当前 App 不匹配（防错扫）。
    APP_MISMATCH,
    /// 配对令牌无效/过期（服务端 403 pairing_token_invalid → 提示「二维码已过期，请刷新」）。
    TOKEN_INVALID,
    /// 服务器不可达 / 注册失败（非 403）。
    UNREACHABLE,
}

/// 扫码连接结果回调载体。
sealed class ConnectByScanResult {
    data object Success : ConnectByScanResult()
    data class Failure(val error: ConnectByScanError) : ConnectByScanResult()
}

// ============================================================================
// 连接层契约（对齐 iOS Models.swift 上部）
// ============================================================================

/// 服务端下发的心跳配置（契约 ServerConfig）。
internal data class ServerConfig(
    val heartbeatIntervalSeconds: Int,
    val heartbeatTimeoutSeconds: Int,
)

/// 心跳响应（契约 HeartbeatResponse）。
internal data class HeartbeatResponse(
    val ok: Boolean,
    val serverTime: String,
    val serverConfig: ServerConfig?,
    val session: CaptureSession?,
    val rulesVersion: Int?,
)

/// 抓包会话（契约 CaptureSession；仅用于状态判断）。
internal data class CaptureSession(
    val id: String,
    val app: String,
    val did: String,
    val status: String,
    val startedAt: String,
    val endedAt: String?,
    val requestCount: Int,
    val viewerCount: Int,
) {
    val isCapturing: Boolean get() = status == "capturing"
}

/// 心跳请求（契约 HeartbeatRequest；可选上报 sdkVersion）。
internal data class HeartbeatRequest(val sdkVersion: String)

/// 设备注册请求（契约 RegisterDeviceRequest；可选 pairingToken/deviceName）。
internal data class RegisterDeviceRequest(
    val app: String,
    val did: String,
    val platform: String,
    val osVersion: String,
    val sdkVersion: String,
    val appVersion: String,
    /// App 显示名（可选；Web 显示回退 applicationId）。
    val appName: String? = null,
    /// 扫码配对令牌：来自二维码；有效时未知 (app, did) 自动注册，已存在则复用。
    val pairingToken: String? = null,
    /// 扫码自动注册时的设备显示名（可选）。
    val deviceName: String? = null,
)

/// 设备注册响应（契约 RegisterDeviceResponse）。
internal data class RegisterDeviceResponse(
    val device: DeviceView,
    val serverConfig: ServerConfig,
)

/// 设备视图（契约 Device schema 子集；仅校验 app/did）。
internal data class DeviceView(
    val app: String,
    val did: String,
    val status: String,
    val lastSeenAt: String? = null,
    val registeredAt: String? = null,
)

// ============================================================================
// 流量契约（对齐 iOS Models.swift 中部）
// ============================================================================

/// 一条 HTTP 请求/响应的完整信息（契约 TrafficEntry）。
/// id / sessionId 由服务端生成，SDK 不携带；seq 由服务端盖戳，SDK 不持有。
internal data class TrafficEntry(
    /// 请求发起时间（RFC3339）。
    val timestamp: String,
    val method: String,
    val url: String,
    val path: String,
    val query: String,
    val requestHeaders: Map<String, List<String>>,
    val requestBody: String,
    /// 二进制请求体 base64（无法 UTF-8 解码时填充；否则 null）。
    val requestBodyBase64: String? = null,
    /// 请求体经协议解码器解出的可读 UTF-8 文本（仅 Web 展示）。
    val requestBodyDecoded: String? = null,
    /// 响应状态码；请求失败时为 null。
    val statusCode: Int? = null,
    val responseHeaders: Map<String, List<String>>? = null,
    val responseBody: String? = null,
    /// 二进制响应体 base64（无法 UTF-8 解码时填充；否则 null）。
    val responseBodyBase64: String? = null,
    /// 协议解码器解出的可读响应体文本（仅展示用，回放仍走 base64）。
    val responseBodyDecoded: String? = null,
    /// 错误信息；成功时为 null。
    val error: String? = null,
    val durationMs: Int,
    /// 命中 Mock 规则回包时为 true（契约 v0.3.0）。
    val mocked: Boolean = false,
    /// 命中的规则 ID：仅 mocked=true 时非空。
    val matchedRuleId: String? = null,
)

/// 批量上传请求（契约 TrafficUploadRequest；单批 ≤500 条，由 SDK 攒批控制）。
internal data class TrafficUploadRequest(
    val app: String,
    val did: String,
    val sessionId: String,
    val entries: List<TrafficEntry>,
)

/// 上传响应（契约 TrafficUploadResponse）。
internal data class TrafficUploadResponse(
    val accepted: Boolean,
    val count: Int,
)

// ============================================================================
// Mock 规则契约（对齐 iOS Models.swift 下部）
// ============================================================================

/// Mock 回包（契约 MockResponse）。
internal data class MockResponse(
    val statusCode: Int,
    val headers: Map<String, String>? = null,
    val body: String? = null,
    /// 二进制回包 base64；存在时按原始字节回包，body 仅作展示。
    val bodyBase64: String? = null,
    /// 命中该回包的规则 ID（match 时填充，随命中流量上报 matchedRuleId）。
    val ruleId: String? = null,
)

/// 一条设备级 Mock 规则（契约 MockRule）。SDK 只关心匹配与回包字段。
internal data class MockRule(
    val id: String,
    val method: String,
    val path: String,
    val response: MockResponse,
    val enabled: Boolean,
    /// 服务端计算的运行时生效态；SDK 只对 effective=true 的规则回包。
    val effective: Boolean,
)

/// 规则下发响应（契约 MockRuleList）。增量拉取时 version 为当前版本、
/// rules 为 effective=true 的规则集（版本未变时为空数组）。
internal data class MockRuleList(
    val version: Int,
    val rules: List<MockRule>,
)
