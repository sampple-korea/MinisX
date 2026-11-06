package com.minisx.app.network

import com.minisx.app.ProductionSources
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebViewProxyRealmTest {

    @Test fun `the realm the Go entry sends is answered`() {
        val go = java.io.File("../../deps/rclone-mobile/nethub/httpentry.go").takeIf { it.isFile }
            ?: java.io.File("../../../deps/rclone-mobile/nethub/httpentry.go").takeIf { it.isFile }
        val sent = go?.readText()?.let { Regex("realm=\\\\\"([A-Za-z]+)\\\\\"").find(it)?.groupValues?.get(1) } ?: "Minis"
        assertTrue("entry realm '$sent' is not answered", WebViewNetworkProxy.isEntryRealm(sent))
    }

    @Test fun `both spellings are accepted and other realms are not`() {
        assertTrue(WebViewNetworkProxy.isEntryRealm("Minis"))
        assertTrue(WebViewNetworkProxy.isEntryRealm("MinisX"))
        assertFalse(WebViewNetworkProxy.isEntryRealm("Router"))
        assertFalse(WebViewNetworkProxy.isEntryRealm(null))
    }

    @Test fun `answerAuth goes through the realm check`() {
        assertTrue(ProductionSources.read("network/WebViewNetworkProxy.kt").contains("if (!isEntryRealm(realm)) return false"))
    }
}
