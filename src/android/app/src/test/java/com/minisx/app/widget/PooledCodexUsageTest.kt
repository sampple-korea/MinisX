package com.minisx.app.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [T-codex-account-pool] The widget's pooled figure: what is left across all accounts. */
class PooledCodexUsageTest {

    private fun limit(window: String, remaining: Double, resetsAt: Long? = null) =
        UsageLimit("ChatGPT subscription · $window", remaining, resetsAt, window)

    @Test
    fun `one account is shown as it is`() {
        val only = listOf(limit("5h", 40.0), limit("7d", 80.0))
        assertEquals(only, PooledCodexUsage.combine(listOf(only)))
    }

    @Test
    fun `accounts are pooled per window as the share of the total allowance that is left`() {
        val a = listOf(limit("5h", 0.0, resetsAt = 5_000), limit("7d", 50.0, resetsAt = 90_000))
        val b = listOf(limit("5h", 100.0, resetsAt = 3_000), limit("7d", 70.0, resetsAt = 80_000))
        val pooled = PooledCodexUsage.combine(listOf(a, b))
        val fiveHour = pooled.first { it.window == "5h" }
        assertEquals(50.0, fiveHour.remainingPercent, 0.001)
        assertEquals("the earliest reset", 3_000L, fiveHour.resetsAt)
        assertEquals(2, fiveHour.accounts)
        assertEquals(60.0, pooled.first { it.window == "7d" }.remainingPercent, 0.001)
        assertEquals("tightest first", "5h", pooled.first().window)
    }

    @Test
    fun `a window only some accounts report is pooled over those that do`() {
        val a = listOf(limit("5h", 20.0), limit("7d", 10.0))
        val b = listOf(limit("5h", 60.0))
        val pooled = PooledCodexUsage.combine(listOf(a, b))
        assertEquals(40.0, pooled.first { it.window == "5h" }.remainingPercent, 0.001)
        val weekly = pooled.first { it.window == "7d" }
        assertEquals(10.0, weekly.remainingPercent, 0.001)
        assertEquals(1, weekly.accounts)
        assertTrue(pooled.size == 2)
    }
}
