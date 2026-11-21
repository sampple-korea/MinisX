package com.minisx.app.provider

import com.minisx.app.data.model.ThinkingLevel
import com.minisx.app.provider.openai.OpenAIModelsApi
import com.minisx.app.provider.openai.OpenAIProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-codex-client-0171] The models openai/codex 0.171.0 added to its bundled catalog, on the Codex
 * OAuth path. Facts are the catalog's own (codex-rs/models-manager/models.json at rust-v0.171.0):
 * gpt-6.2-sol 500K, gpt-6.2-astra 272K, gpt-6.1-terra 500K; minimal client version 0.166.0 for the
 * 6.2 pair, which the advertised client version has to clear.
 */
class GPT62ModelsTest {

    private val list get() = OpenAIModelsApi.fetchModelsOAuth()
    private fun model(id: String) = list.first { it.id == id }

    @Test fun `the advertised client clears the 6_2 floor`() {
        fun v(s: String) = s.split('.').map { it.toInt() }
        val client = v(OpenAIProvider.CODEX_CLIENT_VERSION)
        val floor = v("0.166.0")
        assertTrue(client[0] > floor[0] || (client[0] == floor[0] && client[1] >= floor[1]))
    }

    @Test fun `the new models are listed once, with the catalog windows`() {
        for (id in listOf("gpt-6.2-sol", "gpt-6.2-astra", "gpt-6.1-terra")) assertEquals(id, 1, list.count { it.id == id })
        assertEquals(500_000, model("gpt-6.2-sol").contextWindow)
        assertEquals(272_000, model("gpt-6.2-astra").contextWindow)
        assertEquals(500_000, model("gpt-6.1-terra").contextWindow)
        assertEquals("GPT-6.2 Sol", model("gpt-6.2-sol").displayName)
    }

    @Test fun `the new flagship leads, and 6_1 Sol still follows Astra`() {
        val ids = list.map { it.id }
        assertTrue(ids.indexOf("gpt-6.2-sol") < ids.indexOf("gpt-6-astra"))
        assertEquals(ids.indexOf("gpt-6-astra") + 1, ids.indexOf("gpt-6.1-sol"))
    }

    @Test fun `all three declare reasoning and reach max`() {
        for (id in listOf("gpt-6.2-sol", "gpt-6.2-astra", "gpt-6.1-terra")) {
            assertEquals(id, true, model(id).supportsReasoning)
            assertEquals(id, ThinkingLevel.MAX, ThinkingLevelCatalog.declaredMaxLevel(id))
            assertEquals(id, ThinkingLevel.MAX, model(id).catalogMaxThinkingLevel)
        }
        // The dash spelling some relays use.
        assertEquals(ThinkingLevel.MAX, ThinkingLevelCatalog.declaredMaxLevel("gpt-6-2-sol"))
        assertEquals(ThinkingLevel.MAX, ThinkingLevelCatalog.declaredMaxLevel("gpt-6-1-terra"))
    }
}
