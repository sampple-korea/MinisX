package com.minisx.app.provider.openai

import com.minisx.app.data.model.LLMError
import com.minisx.app.data.model.LLMModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * [T-codex-account-pool] Several ChatGPT sign-ins used as one: the first account until its usage
 * limit, then the next, and back again after the limit resets.
 */
class CodexAccountPoolTest {

    private var now = 1_000_000L

    private fun member(id: String, accountId: String? = "acct-$id", token: suspend () -> String = { "tok-$id" }) =
        CodexAccountPool.Member(id, "Account $id", { accountId }, token)

    private fun pool(vararg members: CodexAccountPool.Member) =
        CodexAccountPool(members.toList(), CodexAccountPool.MemoryCooldowns()) { now }

    @Test
    fun `the first account is used until it hits its limit, then the next`() = runBlocking {
        val p = pool(member("a"), member("b"), member("c"))
        assertEquals("a", p.lease().member.instanceId)
        p.reportRateLimited("a", retryAfterMs = 3_600_000)
        assertEquals("b", p.lease().member.instanceId)
        p.reportRateLimited("b", retryAfterMs = 3_600_000)
        assertEquals("c", p.lease().member.instanceId)
    }

    @Test
    fun `an account is first in line again once its limit has reset`() = runBlocking {
        val p = pool(member("a"), member("b"))
        p.reportRateLimited("a", retryAfterMs = 60_000)
        assertEquals("b", p.lease().member.instanceId)
        now += 61_000
        assertEquals("a", p.lease().member.instanceId)
    }

    @Test
    fun `with every account on hold the error says when the first one is back`() = runBlocking {
        val p = pool(member("a"), member("b"))
        p.reportRateLimited("a", retryAfterMs = 120_000)
        p.reportRateLimited("b", retryAfterMs = 30_000)
        assertFalse(p.hasAvailable())
        try {
            p.lease()
            fail("expected RateLimited")
        } catch (e: LLMError.RateLimited) {
            assertEquals(30_000L, e.retryAfterMs)
        }
    }

    @Test
    fun `an account whose token cannot be had is skipped and held`() = runBlocking {
        val p = pool(member("a", token = { throw LLMError.InvalidApiKey() }), member("b"))
        assertEquals("b", p.lease().member.instanceId)
        assertTrue(p.isCoolingDown("a"))
    }

    @Test
    fun `when no account has a usable token the error is about the key, not the limit`() = runBlocking {
        val p = pool(member("a", token = { throw LLMError.InvalidApiKey() }), member("b", token = { throw LLMError.InvalidApiKey() }))
        try {
            p.lease()
            fail("expected InvalidApiKey")
        } catch (_: LLMError.InvalidApiKey) {
        }
    }

    @Test
    fun `a limit without a reset time holds the account briefly`() {
        val p = pool(member("a"), member("b"))
        p.reportRateLimited("a", retryAfterMs = null)
        assertTrue(p.isCoolingDown("a"))
        now += CodexAccountPool.DEFAULT_COOLDOWN_MS + 1
        assertFalse(p.isCoolingDown("a"))
    }

    @Test
    fun `the status lists every account with its hold`() {
        val p = pool(member("a"), member("b"))
        p.reportRateLimited("a", retryAfterMs = 90_000)
        val st = p.status()
        assertEquals(now + 90_000, st[0].coolingUntilMs)
        assertNull(st[1].coolingUntilMs)
    }

    @Test
    fun `the reset time is read from the usage-limit reply`() {
        val inSeconds = """{"error":{"type":"usage_limit_reached","plan_type":"plus","resets_in_seconds":3600}}"""
        assertEquals(3_600_000L, CodexAccountPool.retryAfterMs(inSeconds, 0))
        val at = """{"error":{"type":"usage_limit_reached","resets_at":2000}}"""
        assertEquals(1_500_000L, CodexAccountPool.retryAfterMs(at, 500_000))
        assertNull(CodexAccountPool.retryAfterMs("""{"error":{"message":"slow down"}}""", 0))
        assertNull(CodexAccountPool.retryAfterMs("not json", 0))
    }

    @Test
    fun `a request is built for the account that is in use, and moves on after a limit`() = runBlocking {
        val p = pool(member("a"), member("b"))
        val provider = OpenAIProvider(oauthTokenProvider = { "unused" }, model = LLMModel.codexMini).also { it.accountPool = p }

        val first = provider.buildRequest("{}")
        assertEquals("Bearer tok-a", first.header("Authorization"))
        assertEquals("acct-a", first.header("Chatgpt-Account-Id"))
        assertEquals("a", first.tag(CodexAccountPool.Lease::class.java)?.member?.instanceId)

        p.reportRateLimited("a", retryAfterMs = 3_600_000)
        val second = provider.buildRequest("{}")
        assertEquals("Bearer tok-b", second.header("Authorization"))
        assertEquals("acct-b", second.header("Chatgpt-Account-Id"))
    }
}
