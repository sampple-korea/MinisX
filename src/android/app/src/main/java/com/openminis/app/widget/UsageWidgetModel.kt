package com.openminis.app.widget

import com.openminis.app.quota.QuotaFreshness
import com.openminis.app.quota.QuotaFreshnessPolicy
import com.openminis.app.quota.QuotaPool
import com.openminis.app.quota.utilization
import java.math.BigDecimal

/** One displayable allowance window: how much is left and when it resets. */
data class UsageLimit(
    val label: String,
    /** 0..100, what is LEFT (the gauge fills with this). */
    val remainingPercent: Double,
    /** Epoch ms, or null when the vendor did not report one. */
    val resetsAt: Long?,
)

/** What a usage widget shows. Only quota figures: no credentials, prompts or account names. */
data class UsageWidgetModel(
    val state: State,
    val limits: List<UsageLimit> = emptyList(),
    /** Newest observation behind [limits], epoch ms (0 when none). */
    val updatedAt: Long = 0L,
    /** The provider instance the numbers belong to; null when none matches. */
    val instanceId: String? = null,
) {
    enum class State {
        /** No enabled provider of this kind is configured. */
        NO_PROVIDER,
        /** A provider exists but has reported no usable window yet (or all are stale / reset). */
        NO_DATA,
        READY,
    }

    /** The tightest window: the number the widget leads with. */
    val tightest: UsageLimit? get() = limits.firstOrNull()

    companion object {
        const val MAX_LIMITS = 8
        /** At or below this many percent left the gauge turns to the warning colour. */
        const val LOW_PERCENT = 20.0

        /**
         * The windows of [pools] that are still meaningful at [now], tightest first.
         *
         * A window is dropped when its reset time has passed (the figure describes a
         * period that is over) or when it is too old to trust, exactly as the in-app
         * quota section does. Balances and rate limits have no percentage and never show.
         */
        fun limitsOf(pools: List<QuotaPool>, now: Long, freshness: QuotaFreshnessPolicy): List<UsageLimit> {
            val rows = ArrayList<UsageLimit>()
            for (pool in pools) {
                if (pool.kind != QuotaPool.Kind.usageWindow && pool.kind != QuotaPool.Kind.allowance) continue
                for (w in pool.windows) {
                    if (freshness.freshness(w, now) == QuotaFreshness.expired) continue
                    val used = w.utilization(pool.kind, pool.unlimited)?.percent ?: continue
                    val remaining = BigDecimal(100).subtract(used).toDouble().coerceIn(0.0, 100.0)
                    val name = w.calendarPeriod?.name ?: w.id
                    val label = pool.vendorLabel?.takeIf { it.isNotBlank() }?.let { "$it · $name" } ?: name
                    rows += UsageLimit(label.take(100), remaining, w.resetsAt)
                }
            }
            return rows.sortedBy { it.remainingPercent }.take(MAX_LIMITS)
        }

        fun isLow(remainingPercent: Double) = remainingPercent <= LOW_PERCENT
    }
}
