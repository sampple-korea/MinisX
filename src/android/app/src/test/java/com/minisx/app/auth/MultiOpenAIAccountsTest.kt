package com.minisx.app.auth

import android.content.Context
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [T-multi-openai-accounts] Several ChatGPT accounts can be added as separate OpenAI OAuth
 * providers: they are told apart by e-mail, a second sign-in asks which account is signing in,
 * and an account that is already added is refused.
 */
@RunWith(RobolectricTestRunner::class)
class MultiOpenAIAccountsTest {

    private fun jwt(payload: String) =
        "eyJhbGciOiJub25lIn0." + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray()) + ".sig"

    @Test
    fun `the e-mail is read from the id_token`() {
        val claims = OpenAIOAuthManager.claimsFromIdToken(
            jwt("""{"email":"me@example.com","https://api.openai.com/auth":{"chatgpt_account_id":"acct-1","chatgpt_plan_type":"plus"}}"""),
        )!!
        assertEquals("me@example.com", claims.email)
        assertEquals("acct-1", claims.accountId)
    }

    @Test
    fun `a token bundle gives the e-mail from the id_token first, then from the access_token`() {
        val both = JSONObject()
            .put("id_token", jwt("""{"email":"id@example.com"}"""))
            .put("access_token", jwt("""{"email":"access@example.com"}"""))
        assertEquals("id@example.com", OpenAIOAuthManager.claimsFromTokens(both)!!.email)
        val accessOnly = JSONObject().put("access_token", jwt("""{"email":"access@example.com"}"""))
        assertEquals("access@example.com", OpenAIOAuthManager.claimsFromTokens(accessOnly)!!.email)
        assertNull("nothing to read", OpenAIOAuthManager.claimsFromTokens(JSONObject().put("id_token", jwt("""{"sub":"x"}"""))))
    }

    @Test
    fun `an account that is already added is found, a new one is not`() {
        val others = mapOf("inst-a" to "acct-1", "inst-b" to "acct-2", "inst-c" to null)
        assertEquals("inst-b", OpenAIOAuthManager.duplicateOf("acct-2", others))
        assertNull(OpenAIOAuthManager.duplicateOf("acct-3", others))
        assertNull("no account id yet - cannot tell, so not refused", OpenAIOAuthManager.duplicateOf(null, others))
        assertNull(OpenAIOAuthManager.duplicateOf("", others))
    }

    @Test
    fun `the sign-in page is told to ask for credentials only when other accounts exist`() {
        val context = org.robolectric.RuntimeEnvironment.getApplication() as Context
        val plain = OpenAIOAuthManager(context, "inst-plain").buildAuthorizationUrl()
        assertFalse(plain.contains("prompt="))
        val manager = OpenAIOAuthManager(context, "inst-second").also { it.forceAccountPrompt = true }
        val forced = manager.buildAuthorizationUrl()
        assertTrue(forced.contains("&prompt=login"))
        // everything else stays as the Codex sign-in expects it
        assertTrue(forced.contains("client_id=app_EMoamEEZ73f0CkXaXp7hrann"))
        assertTrue(forced.contains("codex_cli_simplified_flow=true"))
    }
}
