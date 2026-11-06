package com.minisx.app.browser

import android.webkit.WebView
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.minisx.app.logging.AppLogger

/**
 * Makes the app's WebViews present themselves the way Chrome does.
 *
 * Android's WebView announces itself twice: its user agent carries `; wv` and a `Version/4.0` token,
 * and every request carries `X-Requested-With: <the app's package name>`. Plenty of sites (Korean
 * community and portal sites among them) answer such a request with an empty 403 or 5xx, which the
 * WebView shows as "웹페이지를 사용할 수 없음 / net::ERR_HTTP_RESPONSE_CODE_FAILURE" for the whole
 * page. Chrome sends neither, so neither do we.
 */
object WebViewIdentity {
    private const val TAG = "WebViewIdentity"

    /** The WebView default user agent without the tokens that mark it as an embedded WebView. */
    fun browserLike(ua: String): String =
        ua.replace("; wv", "")
            .replace(Regex(" Version/\\d+(\\.\\d+)*"), "")
            .replace(Regex("\\s{2,}"), " ")
            .trim()

    /**
     * Applies the Chrome-like user agent to [webView] (only when [rewriteUserAgent]: a profile the
     * user or the agent picked is left alone) and stops the `X-Requested-With` header.
     */
    fun apply(webView: WebView, rewriteUserAgent: Boolean = true) {
        val settings = webView.settings
        if (rewriteUserAgent) {
            val current = settings.userAgentString.orEmpty()
            if (current.isNotEmpty()) {
                val rewritten = browserLike(current)
                if (rewritten != current) settings.userAgentString = rewritten
            }
        }
        runCatching {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
                // An empty allow-list: no origin gets the header.
                WebSettingsCompat.setRequestedWithHeaderOriginAllowList(settings, emptySet())
            }
        }.onFailure { AppLogger.warning(TAG, "could not drop X-Requested-With: ${it.message}") }
    }
}
