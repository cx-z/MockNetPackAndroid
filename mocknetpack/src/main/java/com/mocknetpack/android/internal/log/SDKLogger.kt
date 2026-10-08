package com.mocknetpack.android.internal.log

import android.util.Log

/**
 * SDK 日志（对齐 iOS SDKLogger）：先经注入的 logHandler（宿主/测试捕获），
 * 再落 android.util.Log。
 */
object SDKLogger {
    private const val TAG = "MockNetPack"

    /// 宿主注入的日志回调（ConnectionController 启动时设置）。
    var handler: ((String) -> Unit)? = null

    fun info(message: String, category: String = "sdk") = log(Log.INFO, message, category)
    fun warn(message: String, category: String = "sdk") = log(Log.WARN, message, category)
    fun error(message: String, category: String = "sdk") = log(Log.ERROR, message, category)

    private fun log(level: Int, message: String, category: String) {
        val line = "[$category] $message"
        handler?.invoke(line)
        try {
            Log.println(level, TAG, line)
        } catch (_: Throwable) {
            // JVM 本地单测无 android.util.Log 实现（stub 抛 not mocked）；
            // 日志为最佳努力，不因平台缺失导致 SDK 崩溃。
        }
    }
}
