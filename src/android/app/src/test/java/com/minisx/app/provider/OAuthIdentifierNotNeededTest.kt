package com.minisx.app.provider

import com.minisx.app.ProductionSources
import com.minisx.app.auth.ClaudeOAuthManager
import com.minisx.app.data.model.LLMModel
import com.minisx.app.provider.anthropic.AnthropicProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.13 crashed on every retry with "ANTHROPIC_OAUTH_IDENTIFIER_PROMPT is not configured": two code paths
 * that run for EVERY provider read the Claude Code OAuth identifier, which a public build does not have.
 * Only a request that authenticates with Claude Code OAuth may need it.
 */
class OAuthIdentifierNotNeededTest {

    @Test fun `an API-key Anthropic request builds its system prompt without the identifier`() {
        val provider = AnthropicProvider(apiKey = "test-key", model = LLMModel.claudeHaiku45, basePath = "http://localhost")
        val blocks = provider.resolveSystemPrompt("You are helpful.")
        assertNotNull(blocks)
        assertEquals(1, blocks!!.length())
        assertEquals("You are helpful.", blocks.getJSONObject(0).getString("text"))
        assertEquals(null, provider.resolveSystemPrompt(null))
    }

    @Test fun `the safe accessor never throws, and is null or non-empty`() {
        val v = ClaudeOAuthManager.identifierPromptOrNull
        assertTrue(v == null || v.isNotEmpty())
    }

    @Test fun `the prompt rewrite for retry and failover reads the throwing value only for OAuth`() {
        val vm = ProductionSources.read("ui/chat/ChatViewModel.kt")
        val body = vm.substringAfter("private fun systemPromptFor(").substringBefore("\n    }\n")
        assertTrue(body.contains("identifierPromptOrNull"))
        assertTrue("the throwing read must sit behind the OAuth check", body.indexOf("if (oauth)") in 0 until body.indexOf("ANTHROPIC_OAUTH_IDENTIFIER_PROMPT"))
        val provider = ProductionSources.read("provider/anthropic/AnthropicProvider.kt")
        val fn = provider.substringAfter("internal fun resolveSystemPrompt(").substringBefore("// API key path")
        assertTrue("resolveSystemPrompt reads the identifier before checking isOAuth", fn.indexOf("if (isOAuth)") < fn.indexOf("ANTHROPIC_OAUTH_IDENTIFIER_PROMPT"))
    }
}
