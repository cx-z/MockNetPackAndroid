package com.mocknetpack.android.internal.capture

import okhttp3.Interceptor
import okhttp3.OkHttpClient

/** Identifies SDK interceptors even after OkHttp copies the list into a new builder. */
internal object OkHttpCaptureInstall {
    fun install(
        builder: OkHttpClient.Builder,
        sdk: Interceptor,
        decorator: ((Interceptor) -> Interceptor)?,
    ) {
        val interceptors = builder.interceptors()
        if (synchronized(builder) { interceptors.any { it === sdk || it is DecoratedCapture } }) return

        val installed = decorator?.let { DecoratedCapture(it(sdk)) } ?: sdk
        synchronized(builder) {
            if (interceptors.none { it === sdk || it is DecoratedCapture }) {
                builder.addInterceptor(installed)
            }
        }
    }

    private class DecoratedCapture(private val delegate: Interceptor) : Interceptor by delegate
}
