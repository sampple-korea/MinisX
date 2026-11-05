package com.minisx.app.quota

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * [T-codex-account-pool] What the quota UI shows for a ChatGPT provider that has several accounts:
 * the pooled allowance, not the first account alone. Every reader (the chat's quota dot, the picker
 * hints, the provider's quota card, the widgets) goes through [QuotaStore.state], which is the raw
 * store with the pooled providers' pools replaced by [apply]'s result.
 *
 * Per window, the pooled used share is the mean of the accounts' used shares (each account is one
 * full allowance), the reset is the earliest reset, and the pool is "rejected" only when every
 * account is. So the dot goes red when the whole pool is out, not when only the first account is.
 */
object QuotaPoolOverlay {
    /** One provider's own subject and the subjects of its extra accounts. */
    data class Group(val primary: QuotaSubjectID, val extras: List<QuotaSubjectID>)

    fun apply(st: QuotaState, groups: List<Group>): QuotaState {
        if (groups.isEmpty()) return st
        val pools = st.pools.toMutableMap()
        val counts = HashMap<QuotaSubjectID, Int>()
        for (g in groups) {
            val extras = g.extras.filter { st.poolsOf(it).isNotEmpty() }
            if (extras.isEmpty()) continue
            counts[g.primary] = 1 + extras.size
            val prefix = "${g.primary.vendor.name}:${g.primary.accountKey}:"
            for (own in st.poolsOf(g.primary)) {
                if (own.kind != QuotaPool.Kind.usageWindow && own.kind != QuotaPool.Kind.allowance) continue
                val suffix = own.id.rawValue.removePrefix(prefix)
                val members = listOf(own) + extras.mapNotNull { e ->
                    st.pools[QuotaPoolKey("${e.vendor.name}:${e.accountKey}:$suffix")]
                }
                if (members.size < 2) continue
                pools[own.id] = pool(own, members)
            }
        }
        return st.copy(pools = pools, pooledAccounts = counts)
    }

    private fun pool(own: QuotaPool, members: List<QuotaPool>): QuotaPool {
        val ids = LinkedHashSet<String>().apply { members.forEach { m -> m.windows.forEach { add(it.id) } } }
        val windows = ids.map { id -> window(members.mapNotNull { m -> m.windows.firstOrNull { it.id == id } }) }
        val verdicts = members.map { it.verdict }
        val verdict = when {
            verdicts.all { it == QuotaPool.Verdict.rejected } -> QuotaPool.Verdict.rejected
            verdicts.any { it == QuotaPool.Verdict.allowed } -> QuotaPool.Verdict.allowed
            else -> own.verdict
        }
        return own.copy(windows = windows, verdict = verdict, observedAt = members.maxOf { it.observedAt })
    }

    private fun window(ws: List<QuotaWindow>): QuotaWindow {
        val first = ws.first()
        if (ws.size == 1) return first
        val used = ws.mapNotNull { w ->
            w.reportedUsedFraction ?: w.reportedRemainingFraction?.let { BigDecimal.ONE.subtract(it) }
        }
        val mean = if (used.isEmpty()) null
        else used.fold(BigDecimal.ZERO, BigDecimal::add).divide(BigDecimal(used.size), 6, RoundingMode.HALF_UP)
        return first.copy(
            reportedUsedFraction = mean ?: first.reportedUsedFraction,
            reportedRemainingFraction = null,
            resetsAt = ws.mapNotNull { it.resetsAt }.minOrNull(),
            observedAt = ws.maxOf { it.observedAt },
        )
    }
}
