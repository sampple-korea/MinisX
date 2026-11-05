package com.minisx.app.widget

import com.minisx.app.quota.QuotaConfidence
import com.minisx.app.quota.QuotaDecimalParser
import com.minisx.app.quota.QuotaFreshnessPolicy
import com.minisx.app.quota.QuotaPool
import com.minisx.app.quota.QuotaPoolKey
import com.minisx.app.quota.QuotaProvenance
import com.minisx.app.quota.QuotaSource
import com.minisx.app.quota.QuotaSubjectID
import com.minisx.app.quota.QuotaUnit
import com.minisx.app.quota.QuotaVendor
import com.minisx.app.quota.QuotaWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a usage widget derives from the quota pools: remaining %, tightest first, expired dropped. */
class UsageWidgetModelTest {

    private val now = 1_800_000_000_000L
    private val prov = QuotaProvenance(QuotaSource.officialQuery, QuotaConfidence.documented, "test")
    private val sub = QuotaSubjectID(QuotaVendor.codexSubscription, "h")
    private val policy = QuotaFreshnessPolicy()

    private fun window(id: String, used: String, resetsAt: Long? = now + 3_600_000L, observedAt: Long = now) = QuotaWindow(
        id = id, isRolling = true, reportedUsedFraction = QuotaDecimalParser.parseString(used),
        resetsAt = resetsAt, observedAt = observedAt, provenance = prov,
    )

    private fun pool(family: String, windows: List<QuotaWindow>, label: String? = null, kind: QuotaPool.Kind = QuotaPool.Kind.usageWindow) = QuotaPool(
        id = QuotaPoolKey.of(sub, family), subjectId = sub, kind = kind, unit = QuotaUnit.PercentOnly,
        vendorLabel = label, windows = windows, provenance = prov, observedAt = now,
    )

    @Test fun `remaining is the complement of the reported used fraction`() {
        val rows = UsageWidgetModel.limitsOf(listOf(pool("p", listOf(window("5h", "0.37")))), now, policy)
        assertEquals(1, rows.size)
        assertEquals(63.0, rows[0].remainingPercent, 0.0001)
        assertEquals("5h", rows[0].label)
        assertEquals(now + 3_600_000L, rows[0].resetsAt)
    }

    @Test fun `tightest window comes first`() {
        val rows = UsageWidgetModel.limitsOf(
            listOf(pool("p", listOf(window("7d", "0.20"), window("5h", "0.90")))), now, policy,
        )
        assertEquals(listOf("5h", "7d"), rows.map { it.label })
        assertEquals("5h", UsageWidgetModel(UsageWidgetModel.State.READY, rows).tightest?.label)
    }

    @Test fun `a window whose reset time has passed is dropped`() {
        val rows = UsageWidgetModel.limitsOf(
            listOf(pool("p", listOf(window("5h", "0.90", resetsAt = now - 1), window("7d", "0.10")))), now, policy,
        )
        assertEquals(listOf("7d"), rows.map { it.label })
    }

    @Test fun `a window observed too long ago is dropped`() {
        val old = now - 2 * 3_600_000L
        val rows = UsageWidgetModel.limitsOf(
            listOf(pool("p", listOf(window("5h", "0.5", resetsAt = null, observedAt = old)))), now, policy,
        )
        assertTrue(rows.isEmpty())
    }

    @Test fun `balances and rate limits never show`() {
        val rows = UsageWidgetModel.limitsOf(
            listOf(
                pool("bal", listOf(window("day", "0.5")), kind = QuotaPool.Kind.prepaidBalance),
                pool("rl", listOf(window("min", "0.5")), kind = QuotaPool.Kind.rateLimit),
            ),
            now, policy,
        )
        assertTrue(rows.isEmpty())
    }

    @Test fun `a vendor label prefixes the window and over-use clamps to zero`() {
        val rows = UsageWidgetModel.limitsOf(
            listOf(pool("m", listOf(window("5h", "1.20")), label = "GPT-5.3-Codex-Spark")), now, policy,
        )
        assertEquals("GPT-5.3-Codex-Spark · 5h", rows[0].label)
        assertEquals(0.0, rows[0].remainingPercent, 0.0)
    }

    @Test fun `at most eight rows`() {
        val windows = (1..12).map { window("w$it", "0.${it % 9}") }
        assertEquals(UsageWidgetModel.MAX_LIMITS, UsageWidgetModel.limitsOf(listOf(pool("p", windows)), now, policy).size)
    }

    @Test fun `low means twenty percent or less left`() {
        assertTrue(UsageWidgetModel.isLow(20.0))
        assertTrue(!UsageWidgetModel.isLow(20.1))
    }
}
