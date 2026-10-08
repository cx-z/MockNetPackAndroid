package com.mocknetpack.android.internal.scan

/**
 * 扫码连接二维码载荷（对齐 iOS QRConnectPayload）。
 *
 * 二维码格式：`mocknetpack://connect?v=1&u=<base64url(server)>&a=<appID>&t=<token>`
 * - `u`：Web 同源服务器地址（`${location.origin}/api/v1`），base64url 编码；
 * - `a`：appID（必须与当前 App 匹配，防错扫）；
 * - `t`：配对令牌（10 分钟有效，D5）。
 *
 * did 不在二维码内（D6 澄清）：did 由设备本地持有（DIDStore / 业务方设备 ID），
 * 二维码只回答「连哪个服务器、以谁的身份注册」。
 */
data class QRConnectPayload(
    /// 服务器 base URL（如 `http://host:4290/api/v1`）。
    val serverURL: String,
    /// 二维码声明的 appID。
    val appID: String,
    /// 配对令牌。
    val token: String,
)

/// 二维码解析错误分类（对齐 iOS QRConnectParseError）。
sealed class QRConnectParseError {
    /// scheme/host 不是 mocknetpack://connect。
    data object NotMockNetPack : QRConnectParseError()
    /// 协议版本不支持（v != 1）。
    data object UnsupportedVersion : QRConnectParseError()
    /// u 缺失 / 解码失败 / 非 http(s) / host 为空。
    data object InvalidServer : QRConnectParseError()
    /// appID 缺失。
    data object MissingAppID : QRConnectParseError()
    /// 二维码 appID 与当前 App 不匹配（防错扫）。
    data object AppMismatch : QRConnectParseError()
    /// 令牌缺失。
    data object MissingToken : QRConnectParseError()
}

/// 解析结果载体。
sealed class QRConnectResult {
    data class Success(val payload: QRConnectPayload) : QRConnectResult()
    data class Failure(val error: QRConnectParseError) : QRConnectResult()
}

/**
 * 扫码二维码解析器（从 iOS QRConnectParser 移植；纯 Kotlin、无 android 依赖，
 * 便于本地单测）。已在骨架阶段实现：AN-4 仅剩相机/扫码 UI 与注册流程。
 */
object QRConnectParser {

    private const val SCHEME = "mocknetpack"
    private const val HOST = "connect"

    private const val BASE64_ALPHABET =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    /**
     * 解析并校验二维码内容。
     * @param raw 二维码原始文本。
     * @param expectedAppID 当前 App 标识（applicationId）；二维码 appID 必须与之匹配。
     */
    fun parse(raw: String, expectedAppID: String): QRConnectResult {
        val text = raw.trim()

        // scheme://host?query
        val schemeEnd = text.indexOf("://")
        if (schemeEnd <= 0) return failure(QRConnectParseError.NotMockNetPack)
        if (!text.substring(0, schemeEnd).equals(SCHEME, ignoreCase = true)) {
            return failure(QRConnectParseError.NotMockNetPack)
        }
        val rest = text.substring(schemeEnd + 3)
        val slash = rest.indexOf('/')
        val authority = if (slash >= 0) rest.substring(0, slash) else rest
        val host = authority.substringBefore('?')
        if (!host.equals(HOST, ignoreCase = true)) {
            return failure(QRConnectParseError.NotMockNetPack)
        }
        val query = authority.substringAfter('?', missingDelimiterValue = "")
        val params = query.split('&').mapNotNull { kv ->
            val eq = kv.indexOf('=')
            if (eq <= 0) null else kv.substring(0, eq) to kv.substring(eq + 1)
        }.toMap()

        // 版本（v=1）。
        if (params["v"] != "1") return failure(QRConnectParseError.UnsupportedVersion)

        // 服务器地址（u = base64url）。
        val uRaw = params["u"] ?: return failure(QRConnectParseError.InvalidServer)
        val uData = base64URLDecode(uRaw) ?: return failure(QRConnectParseError.InvalidServer)
        val serverRaw = String(uData, Charsets.UTF_8)
        if (!isValidServer(serverRaw)) return failure(QRConnectParseError.InvalidServer)

        // appID（a）——必须与当前 App 匹配。
        val appID = params["a"] ?: return failure(QRConnectParseError.MissingAppID)
        if (appID.isEmpty()) return failure(QRConnectParseError.MissingAppID)
        if (appID != expectedAppID) return failure(QRConnectParseError.AppMismatch)

        // 配对令牌（t）。
        val token = params["t"] ?: return failure(QRConnectParseError.MissingToken)
        if (token.isEmpty()) return failure(QRConnectParseError.MissingToken)

        return QRConnectResult.Success(QRConnectPayload(serverRaw, appID, token))
    }

    private fun isValidServer(raw: String): Boolean {
        val uri = try { java.net.URI(raw) } catch (_: Exception) { return false }
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme != "http" && scheme != "https") return false
        val host = uri.host ?: return false
        return host.isNotEmpty()
    }

    /// base64url 解码（RFC 4648 §5：- _ 替代 + /，去 padding）。非法字符返回 null。
    private fun base64URLDecode(value: String): ByteArray? {
        var s = value.replace('-', '+').replace('_', '/')
        while (s.length % 4 != 0) s += "="
        return try {
            decodeBase64(s)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun decodeBase64(s: String): ByteArray {
        if (s.isEmpty()) return ByteArray(0)
        val out = java.io.ByteArrayOutputStream(s.length * 3 / 4 + 1)
        var buffer = 0
        var bits = 0
        for (c in s) {
            if (c == '=') break
            val v = BASE64_ALPHABET.indexOf(c)
            if (v < 0) throw IllegalArgumentException("invalid base64 char: $c")
            buffer = (buffer shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write((buffer shr bits) and 0xFF)
            }
        }
        return out.toByteArray()
    }

    private fun failure(error: QRConnectParseError): QRConnectResult = QRConnectResult.Failure(error)
}
