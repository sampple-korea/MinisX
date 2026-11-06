package com.minisx.app.assistant

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AssistantTest {

    @After fun tearDown() = AssistantInvocations.clearForTest()

    private fun inv(at: Long = System.currentTimeMillis(), pkg: String? = "com.example.app") =
        AssistantInvocation(screenshot = null, screenText = "hello", packageName = pkg, webUri = null, source = "session", capturedAtMs = at)

    @Test fun `a token is redeemed once`() {
        val token = AssistantInvocations.register(inv())
        assertEquals(32, token.length)
        assertNotNull(AssistantInvocations.redeem(token))
        assertNull(AssistantInvocations.redeem(token))
    }

    @Test fun `tokens are random and unknown ones are refused`() {
        val a = AssistantInvocations.register(inv())
        val b = AssistantInvocations.register(inv())
        assertNotEquals(a, b)
        assertNull(AssistantInvocations.redeem("0".repeat(32)))
        assertNull(AssistantInvocations.redeem(null))
        assertNull(AssistantInvocations.redeem(""))
    }

    @Test fun `an old invocation expires`() {
        val token = AssistantInvocations.register(inv(at = 1_000L))
        assertNull(AssistantInvocations.redeem(token, now = 1_000L + 130_000L))
    }

    @Test fun `screen lines are trimmed, deduplicated and capped`() {
        val out = AssistantContext.composeScreenText(listOf("  Hello   world ", "Hello world", "", "Second"), maxChars = 100)
        assertEquals("Hello world\nSecond", out)
        val capped = AssistantContext.composeScreenText(listOf("a".repeat(40), "b".repeat(40), "c".repeat(40)), maxChars = 90)
        assertEquals("${"a".repeat(40)}\n${"b".repeat(40)}", capped)
        assertNull(AssistantContext.composeScreenText(listOf("", "  ")))
    }

    @Test fun `the screenshot is scaled down to the longest side`() {
        assertEquals(1080 to 2400, AssistantContext.fit(1080, 2400, maxSide = 2400))
        assertEquals(720 to 1600, AssistantContext.fit(1080, 2400, maxSide = 1600).let { (w, h) -> w.coerceAtLeast(0) to h })
        assertEquals(1 to 1, AssistantContext.fit(0, 0))
    }

    @Test fun `the screen document says it is not the MinisX chat`() {
        val doc = AssistantContext.screenDocument(inv(), hasImage = true)
        assertTrue(doc.contains("NOT the MinisX chat"))
        assertTrue(doc.contains("App: com.example.app"))
        assertTrue(doc.contains("screenshot of it is attached"))
        assertTrue(AssistantContext.screenDocument(inv(), hasImage = false).contains("No screenshot is available"))
    }

    // ── wiring pins ────────────────────────────────────────────────────────

    private fun manifest(): String {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val m = File(dir, "src/main/AndroidManifest.xml").takeIf { it.isFile } ?: File(dir, "app/src/main/AndroidManifest.xml").takeIf { it.isFile }
            if (m != null) return m.readText()
            dir = dir.parentFile
        }
        error("manifest not found")
    }

    @Test fun `the manifest declares the assistant, its session, the forwarder and ASSIST`() {
        val m = manifest()
        assertTrue(m.contains("android.service.voice.VoiceInteractionService"))
        assertTrue(m.contains("android.permission.BIND_VOICE_INTERACTION"))
        assertTrue(m.contains(".assistant.AssistantSessionService"))
        assertTrue(m.contains(".assistant.AssistantRecognitionService"))
        assertTrue(m.contains("android.intent.action.ASSIST"))
    }

    @Test fun `an invocation never travels in a URL`() {
        val main = com.minisx.app.ProductionSources.read("MainActivity.kt")
        assertTrue(main.contains("AssistantInvocations.redeem("))
        val handler = com.minisx.app.ProductionSources.read("deeplink/DeepLinkHandler.kt")
        assertTrue("the public deep-link parser must not know assistant invocations", !handler.contains("assistant", ignoreCase = true))
    }

    @Test fun `the first message of an assistant chat takes the screen`() {
        val vm = com.minisx.app.ProductionSources.read("ui/chat/ChatViewModel.kt")
        assertTrue(vm.contains("attachAssistantContext()"))
        assertTrue(vm.contains("currentModelHasNativeVision && shot != null"))
    }
}
