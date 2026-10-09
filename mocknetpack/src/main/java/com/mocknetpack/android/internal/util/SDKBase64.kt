package com.mocknetpack.android.internal.util

/**
 * Base64 编解码垫片（minSdk 21 兼容）。
 *
 * - Android 运行时优先用 framework 的 [android.util.Base64]（API 1 起即有，
 *   零额外依赖、无需核心库脱糖）。
 * - JVM 单测里 android.jar 是桩（方法调用抛 "not mocked"），catch 后回退到
 *   [java.util.Base64]，保证单测在纯 JVM 下可用。
 */
internal object SDKBase64 {
    fun encode(input: ByteArray): String =
        try {
            android.util.Base64.encodeToString(input, android.util.Base64.NO_WRAP)
        } catch (_: Throwable) {
            java.util.Base64.getEncoder().encodeToString(input)
        }

    fun decode(s: String): ByteArray? = try {
        try {
            android.util.Base64.decode(s, android.util.Base64.DEFAULT)
        } catch (_: Throwable) {
            // JVM 单测桩：android.util.Base64 方法不可用，回退 java.util.Base64。
            // 注意：API 21-25 上 java.util.Base64 类不存在，会抛 NoClassDefFoundError
            // （Error，不是 Exception）——外层 catch 必须兜 Throwable，否则畸形
            // bodyBase64 在新支持的 API 区间会直接 crash。
            java.util.Base64.getDecoder().decode(s)
        }
    } catch (_: Throwable) {
        null
    }
}
