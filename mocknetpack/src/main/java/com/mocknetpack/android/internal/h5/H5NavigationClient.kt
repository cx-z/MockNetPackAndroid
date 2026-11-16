package com.mocknetpack.android.internal.h5

import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Message
import android.view.KeyEvent
import android.webkit.ClientCertRequest
import android.webkit.HttpAuthHandler
import android.webkit.RenderProcessGoneDetail
import android.webkit.SafeBrowsingResponse
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.RequiresApi

/** Observes main-frame loads while the original client owns all WebView decisions and callbacks. */
@Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
internal class H5NavigationClient(
    val original: WebViewClient,
    val capture: H5NavigationCapture,
    private val rendererGone: () -> Unit,
) : WebViewClient() {
    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
        capture.started(url)
        original.onPageStarted(view, url, favicon)
    }

    override fun onPageFinished(view: WebView, url: String?) {
        capture.finished(url)
        original.onPageFinished(view, url)
    }

    @RequiresApi(23)
    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
        if (request.isForMainFrame) {
            capture.failed(
                request.url.toString(), request.method, error.errorCode, error.description.toString(),
                "WebView", requestHeaders = request.requestHeaders,
            )
        }
        original.onReceivedError(view, request, error)
    }

    override fun onReceivedError(view: WebView, errorCode: Int, description: String?, failingUrl: String?) {
        if (capture.matches(failingUrl)) {
            capture.failed(failingUrl, code = errorCode, error = description, domain = "WebView")
        }
        original.onReceivedError(view, errorCode, description, failingUrl)
    }

    @RequiresApi(23)
    override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
        if (request.isForMainFrame) {
            capture.httpError(
                request.url.toString(),
                request.method,
                status = errorResponse.statusCode,
                reason = errorResponse.reasonPhrase,
                requestHeaders = request.requestHeaders,
                responseHeaders = errorResponse.responseHeaders,
            )
        }
        original.onReceivedHttpError(view, request, errorResponse)
    }

    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
        if (capture.matches(error.url)) {
            capture.failed(
                error.url, code = error.primaryError, error = "TLS certificate error",
                domain = "WebView.SslError", stage = "h5NavigationIssue",
            )
        }
        original.onReceivedSslError(view, handler, error)
    }

    @RequiresApi(26)
    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        capture.rendererGone(detail.didCrash())
        rendererGone()
        return original.onRenderProcessGone(view, detail)
    }

    @RequiresApi(24)
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
        original.shouldOverrideUrlLoading(view, request)

    override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
        original.shouldOverrideUrlLoading(view, url)

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
        original.shouldInterceptRequest(view, request)

    override fun shouldInterceptRequest(view: WebView, url: String): WebResourceResponse? =
        original.shouldInterceptRequest(view, url)

    override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) =
        original.doUpdateVisitedHistory(view, url, isReload)

    override fun onFormResubmission(view: WebView, dontResend: Message, resend: Message) =
        original.onFormResubmission(view, dontResend, resend)

    override fun onLoadResource(view: WebView, url: String?) = original.onLoadResource(view, url)

    @RequiresApi(23)
    override fun onPageCommitVisible(view: WebView, url: String) = original.onPageCommitVisible(view, url)

    override fun onReceivedClientCertRequest(view: WebView, request: ClientCertRequest) =
        original.onReceivedClientCertRequest(view, request)

    override fun onReceivedHttpAuthRequest(view: WebView, handler: HttpAuthHandler, host: String?, realm: String?) =
        original.onReceivedHttpAuthRequest(view, handler, host, realm)

    override fun onReceivedLoginRequest(view: WebView, realm: String?, account: String?, args: String?) =
        original.onReceivedLoginRequest(view, realm, account, args)

    @RequiresApi(27)
    override fun onSafeBrowsingHit(view: WebView, request: WebResourceRequest, threatType: Int, callback: SafeBrowsingResponse) =
        original.onSafeBrowsingHit(view, request, threatType, callback)

    override fun onScaleChanged(view: WebView, oldScale: Float, newScale: Float) =
        original.onScaleChanged(view, oldScale, newScale)

    override fun onTooManyRedirects(view: WebView, cancelMsg: Message, continueMsg: Message) =
        original.onTooManyRedirects(view, cancelMsg, continueMsg)

    override fun onUnhandledKeyEvent(view: WebView, event: KeyEvent) = original.onUnhandledKeyEvent(view, event)

    override fun shouldOverrideKeyEvent(view: WebView, event: KeyEvent): Boolean =
        original.shouldOverrideKeyEvent(view, event)
}
