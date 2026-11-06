package com.minisx.app.agent

import com.minisx.app.data.model.LLMError
import com.minisx.app.provider.RetryAfterHeader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class RetryPolicyTest {

    private val transient = LLMError.TransientError("boom", httpStatus = 503)
    private val network = LLMError.NetworkError(IOException("down"))

    private fun wait(d: RetryPolicy.Decision) = d as RetryPolicy.Decision.Wait

    @Test fun `backoff doubles from 200 ms with no jitter`() {
        assertEquals(listOf(200L, 400L, 800L, 1600L, 3200L), (1..5).map { RetryPolicy.backoffMs(it, 1.0) })
    }

    @Test fun `jitter stays within ten percent`() {
        assertEquals(180L, RetryPolicy.backoffMs(1, 0.9))
        assertEquals(220L, RetryPolicy.backoffMs(1, 1.1))
    }

    @Test fun `a transient failure is retried five times then given up`() {
        val state = RetryPolicy.State()
        val delays = (1..5).map { wait(RetryPolicy.decide(state, transient, true, 1.0)) }
        assertEquals(listOf(1, 2, 3, 4, 5), delays.map { it.attempt })
        assertTrue(delays.all { it.kind == RetryPolicy.Kind.BACKOFF })
        assertEquals(RetryPolicy.Decision.GiveUp, RetryPolicy.decide(state, transient, true, 1.0))
    }

    @Test fun `the server's Retry-After wins over the backoff`() {
        val advised = LLMError.TransientError("slow down", 503, retryAfterMs = 7_000)
        val d = wait(RetryPolicy.decide(RetryPolicy.State(), advised, true, 1.0))
        assertEquals(7_000L, d.delayMs)
        assertEquals(RetryPolicy.Kind.SERVER_ADVISED, d.kind)
    }

    @Test fun `a Retry-After too long to wait for is left to the fallback`() {
        val advised = LLMError.TransientError("later", 503, retryAfterMs = 600_000)
        assertEquals(RetryPolicy.Decision.GiveUp, RetryPolicy.decide(RetryPolicy.State(), advised, true))
    }

    @Test fun `a dead connection reconnects 5 s then doubling up to 60 s without end`() {
        val state = RetryPolicy.State()
        val delays = (1..8).map { wait(RetryPolicy.decide(state, network, true)).delayMs }
        assertEquals(listOf(5_000L, 10_000L, 20_000L, 40_000L, 60_000L, 60_000L, 60_000L, 60_000L), delays)
        assertEquals(0, state.retries)
        assertEquals(8, state.connectionRetries)
    }

    @Test fun `without unbounded reconnecting a connection failure uses the normal budget`() {
        val state = RetryPolicy.State()
        repeat(5) { assertEquals(RetryPolicy.Kind.BACKOFF, wait(RetryPolicy.decide(state, network, false, 1.0)).kind) }
        assertEquals(RetryPolicy.Decision.GiveUp, RetryPolicy.decide(state, network, false, 1.0))
    }

    @Test fun `other errors are not retried here`() {
        val state = RetryPolicy.State()
        assertEquals(RetryPolicy.Decision.GiveUp, RetryPolicy.decide(state, LLMError.RateLimited(1_000), true))
        assertEquals(RetryPolicy.Decision.GiveUp, RetryPolicy.decide(state, LLMError.InvalidApiKey(), true))
        assertEquals(RetryPolicy.Decision.GiveUp, RetryPolicy.decide(state, LLMError.ProviderError("no"), true))
    }

    @Test fun `Retry-After reads seconds, milliseconds and an HTTP date`() {
        assertEquals(30_000L, RetryAfterHeader.parseMs("30", null, 0))
        assertEquals(1_500L, RetryAfterHeader.parseMs("30", "1500", 0))
        assertEquals(2_500L, RetryAfterHeader.parseMs("2.5", null, 0))
        val now = 784111777000L - 10_000
        assertEquals(10_000L, RetryAfterHeader.parseMs("Sun, 06 Nov 1994 08:49:37 GMT", null, now))
        assertEquals(0L, RetryAfterHeader.parseMs("Sun, 06 Nov 1994 08:49:37 GMT", null, 784111777000L + 5_000))
        assertNull(RetryAfterHeader.parseMs("soon", null, 0))
        assertNull(RetryAfterHeader.parseMs(null, null, 0))
    }

    @Test fun `the advice is attached to a transient error and a rate limit, once`() {
        val t = RetryAfterHeader.attach(LLMError.TransientError("x", 503), "12", null, 0) as LLMError.TransientError
        assertEquals(12_000L, t.retryAfterMs)
        assertEquals(503, t.httpStatus)
        val keep = RetryAfterHeader.attach(LLMError.TransientError("x", 503, 99), "12", null, 0) as LLMError.TransientError
        assertEquals(99L, keep.retryAfterMs)
        val r = RetryAfterHeader.attach(LLMError.RateLimited(), "5", null, 0) as LLMError.RateLimited
        assertEquals(5_000L, r.retryAfterMs)
        assertTrue(RetryAfterHeader.attach(LLMError.InvalidApiKey(), "5", null, 0) is LLMError.InvalidApiKey)
    }
}
