package com.minisx.app.browser

import com.minisx.app.ProductionSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebViewIdentityTest {

    @Test fun `the WebView markers are removed from the default user agent`() {
        val wv = "Mozilla/5.0 (Linux; Android 14; Pixel 8 Build/AP2A.240805.005; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/134.0.6998.135 Mobile Safari/537.36"
        val out = WebViewIdentity.browserLike(wv)
        assertFalse(out.contains("wv"))
        assertFalse(out.contains("Version/"))
        assertEquals(
            "Mozilla/5.0 (Linux; Android 14; Pixel 8 Build/AP2A.240805.005) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.6998.135 Mobile Safari/537.36",
            out,
        )
    }

    @Test fun `a user agent that is already Chrome-like is left as it is`() {
        val chrome = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Mobile Safari/537.36"
        assertEquals(chrome, WebViewIdentity.browserLike(chrome))
    }

    @Test fun `every browsing WebView in the app drops the marker`() {
        assertTrue(ProductionSources.read("ui/preview/WebViewHolder.kt").contains("WebViewIdentity.apply(this)"))
        assertTrue(ProductionSources.read("browser/BrowserUseManager.kt").contains("WebViewIdentity.apply(webView"))
    }
}
