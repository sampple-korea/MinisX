package com.minisx.app.data

import com.minisx.app.data.db.toProviderConfig
import com.minisx.app.data.db.toSnapshot
import com.minisx.app.data.model.ProviderConfig
import com.minisx.app.data.model.ProviderCredential
import com.minisx.app.data.model.ProviderInstance
import com.minisx.app.data.model.ProviderType
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-codex-account-pool] The extra ChatGPT accounts of a provider must survive the database
 * round trip: the config is rebuilt from Room on launch and after every save, so a field Room does
 * not hold comes back empty - the added account then silently vanished from the settings screen.
 */
class AccountPoolPersistenceTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `the extra accounts of a provider survive a database round trip`() {
        val cfg = ProviderConfig()
        cfg.instances.add(
            ProviderInstance(
                id = "p1", label = "OpenAI", providerType = ProviderType.openAI, credentialType = ProviderCredential.oauth,
                poolAccountIds = listOf("acct-b", "acct-c"),
            ),
        )
        cfg.instances.add(
            ProviderInstance(id = "p2", label = "Other", providerType = ProviderType.anthropic, credentialType = ProviderCredential.apiKey),
        )
        val restored = cfg.toSnapshot(json).toProviderConfig(json)
        assertEquals(listOf("acct-b", "acct-c"), restored.instances.first { it.id == "p1" }.poolAccountIds)
        assertTrue(restored.instances.first { it.id == "p2" }.poolAccountIds.isEmpty())
    }
}
