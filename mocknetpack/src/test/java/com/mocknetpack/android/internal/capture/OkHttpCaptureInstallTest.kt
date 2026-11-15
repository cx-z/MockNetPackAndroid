package com.mocknetpack.android.internal.capture

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class OkHttpCaptureInstallTest {
    private val sdk = Interceptor { it.proceed(it.request()) }

    @Test
    fun `repeated and derived builders retain one decorated interceptor`() {
        val builder = OkHttpClient.Builder()
        var decoratorCalls = 0
        OkHttpCaptureInstall.install(builder, sdk) { inner ->
            assertSame(sdk, inner)
            decoratorCalls++
            Interceptor { inner.intercept(it) }
        }
        OkHttpCaptureInstall.install(builder, sdk, null)
        val original = builder.build()
        val derived = original.newBuilder()
        OkHttpCaptureInstall.install(derived, sdk, null)

        assertEquals(1, decoratorCalls)
        assertEquals(1, original.interceptors().size)
        assertEquals(1, derived.interceptors().size)
        assertSame(original.interceptors().single(), derived.interceptors().single())
    }

    @Test
    fun `existing SDK singleton on builder is recognized`() {
        val builder = OkHttpClient.Builder().addInterceptor(sdk)
        OkHttpCaptureInstall.install(builder, sdk, null)
        assertSame(sdk, builder.interceptors().single())
    }
}
