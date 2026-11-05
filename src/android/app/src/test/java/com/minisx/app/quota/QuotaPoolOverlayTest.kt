package com.minisx.app.quota

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/** [T-codex-account-pool] Several ChatGPT accounts under one provider read as one pooled allowance. */
class QuotaPoolOverlayTest {
    private val now = 1_800_000_000_000L
    private val prov = QuotaProvenance(QuotaSource.officialQuery, QuotaConfidence.documented, "test")
    private val first = QuotaSubjectID(QuotaVendor.codexSubscription, "instance:first")
    private val second = QuotaSubjectID(QuotaVendor.codexSubscription, "instance:second")
    private val third = QuotaSubjectID(QuotaVendor.codexSubscription, "instance:third")

    private fun window(id: String, used: String, resetsAt: Long? = null) = QuotaWindow(
        id = id, isRolling = true, reportedUsedFraction = BigDecimal(used), resetsAt = resetsAt,
        observedAt = now, provenance = prov,
    )

    private fun pool(subject: QuotaSubjectID, verdict: QuotaPool.Verdict?, vararg windows: QuotaWindow) = QuotaPool(
        id = QuotaPoolKey.of(subject, "codex"), subjectId = subject, kind = QuotaPool.Kind.usageWindow,
        unit = QuotaUnit.PercentOnly, windows = windows.toList(), verdict = verdict, provenance = prov, observedAt = now,
    )

    private fun state(vararg pools: QuotaPool) = QuotaState(pools = pools.associateBy { it.id })

    @Test
    fun `a provider without extra accounts is left as it is`() {
        val a = pool(first, null, window("5h", "1.0"))
        val st = state(a)
        assertEquals(st, QuotaPoolOverlay.apply(st, listOf(QuotaPoolOverlay.Group(first, listOf(second)))))
        assertEquals(st, QuotaPoolOverlay.apply(st, emptyList()))
    }

    @Test
    fun `an exhausted first account does not make the pool look exhausted`() {
        val a = pool(first, QuotaPool.Verdict.rejected, window("5h", "1.0", resetsAt = now + 7_200_000), window("7d", "0.47"))
        val b = pool(second, QuotaPool.Verdict.allowed, window("5h", "0.0", resetsAt = now + 3_600_000), window("7d", "0.20"))
        val out = QuotaPoolOverlay.apply(state(a, b), listOf(QuotaPoolOverlay.Group(first, listOf(second))))
        val pooled = out.pools.getValue(a.id)
        assertEquals(0.5, pooled.windows.first { it.id == "5h" }.reportedUsedFraction!!.toDouble(), 0.0001)
        assertEquals(0.335, pooled.windows.first { it.id == "7d" }.reportedUsedFraction!!.toDouble(), 0.0001)
        assertEquals("the earliest reset", now + 3_600_000, pooled.windows.first { it.id == "5h" }.resetsAt)
        assertEquals(QuotaPool.Verdict.allowed, pooled.verdict)
        assertEquals(2, out.pooledAccounts[first])
    }

    @Test
    fun `the pool is rejected only when every account is`() {
        val a = pool(first, QuotaPool.Verdict.rejected, window("5h", "1.0"))
        val b = pool(second, QuotaPool.Verdict.rejected, window("5h", "1.0"))
        val c = pool(third, QuotaPool.Verdict.allowed, window("5h", "0.1"))
        val group = QuotaPoolOverlay.Group(first, listOf(second, third))
        assertEquals(QuotaPool.Verdict.rejected, QuotaPoolOverlay.apply(state(a, b), listOf(QuotaPoolOverlay.Group(first, listOf(second)))).pools.getValue(a.id).verdict)
        assertEquals(QuotaPool.Verdict.allowed, QuotaPoolOverlay.apply(state(a, b, c), listOf(group)).pools.getValue(a.id).verdict)
        assertEquals(3, QuotaPoolOverlay.apply(state(a, b, c), listOf(group)).pooledAccounts[first])
    }

    @Test
    fun `the raw per-account pools are not touched and other providers are unaffected`() {
        val a = pool(first, null, window("5h", "1.0"))
        val b = pool(second, null, window("5h", "0.0"))
        val other = QuotaSubjectID(QuotaVendor.claudeSubscription, "instance:claude")
        val c = pool(other, null, window("5h", "0.3"))
        val out = QuotaPoolOverlay.apply(state(a, b, c), listOf(QuotaPoolOverlay.Group(first, listOf(second))))
        assertEquals(b, out.pools[b.id])
        assertEquals(c, out.pools[c.id])
        assertNull(out.pooledAccounts[other])
        assertNotNull(out.pools[a.id])
        assertTrue(out.pools[a.id] != a)
    }
}
