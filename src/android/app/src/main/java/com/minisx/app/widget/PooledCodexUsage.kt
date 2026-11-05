package com.minisx.app.widget

import android.content.Context
import com.minisx.app.auth.OAuthManager
import com.minisx.app.auth.OpenAIOAuthManager
import com.minisx.app.logging.AppLogger
import com.minisx.app.quota.CodexSubscriptionQuotaAdapter
import com.minisx.app.quota.QuotaHttp
import com.minisx.app.quota.QuotaOkHttpTransport
import com.minisx.app.quota.QuotaStore
import com.minisx.app.quota.QuotaSubjectID
import com.minisx.app.quota.QuotaVendor
import java.util.concurrent.ConcurrentHashMap

/**
 * [T-codex-account-pool] Usage of the extra ChatGPT accounts under one provider, and the pooled
 * figure the widget shows: how much allowance is left across ALL the accounts.
 *
 * The provider's own (first) account keeps being measured by [QuotaStore]; the extra accounts are
 * asked here, with the same usage query and parser, and cached for a few minutes.
 */
object PooledCodexUsage {
    private const val TAG = "PooledCodexUsage"
    private const val STALE_MS = 5 * 60_000L

    private class Cached(val limits: List<UsageLimit>, val fetchedAt: Long)

    private val cache = ConcurrentHashMap<String, Cached>()

    /**
     * The windows of each extra account (storage id to limits), asking again for any that is older
     * than a few minutes - or all of them when [force]. An account that cannot be read now keeps its
     * last figure; one that never could is left out.
     */
    suspend fun extraAccountLimits(
        context: Context,
        providerInstanceId: String,
        extraIds: List<String>,
        force: Boolean,
    ): Map<String, List<UsageLimit>> {
        val now = System.currentTimeMillis()
        val out = LinkedHashMap<String, List<UsageLimit>>()
        for (id in extraIds) {
            if (!OAuthManager.hasStoredCredential(context, id)) continue
            val cached = cache[id]
            if (force || cached == null || now - cached.fetchedAt > STALE_MS) {
                fetch(context, providerInstanceId, id, now)?.let { cache[id] = Cached(it, now) }
            }
            cache[id]?.let { out[id] = it.limits }
        }
        return out
    }

    private suspend fun fetch(context: Context, providerInstanceId: String, id: String, now: Long): List<UsageLimit>? = try {
        val manager = OpenAIOAuthManager(context, id)
        val token = manager.validAccessToken() ?: throw IllegalStateException("no token")
        val headers = buildMap {
            put("User-Agent", "codex_cli_rs/${com.minisx.app.provider.openai.OpenAIProvider.CODEX_CLIENT_VERSION} (Android; arm64)")
            put("Originator", "codex_cli_rs")
            manager.accountId?.let { put("ChatGPT-Account-Id", it) }
        }
        val transport = QuotaOkHttpTransport(com.minisx.app.network.NetworkRuntime.providerTag(providerInstanceId))
        val r = transport.get("https://${CodexSubscriptionQuotaAdapter.ENDPOINT}", token, headers)
        QuotaHttp.error(r, CodexSubscriptionQuotaAdapter.ENDPOINT, now)?.let { throw it }
        val snapshot = CodexSubscriptionQuotaAdapter.parseUsage(r.body, QuotaSubjectID(QuotaVendor.codexSubscription, id), now = now)
        UsageWidgetModel.limitsOf(snapshot.pools, now, QuotaStore.freshnessPolicy)
    } catch (e: Exception) {
        AppLogger.warning(TAG, "usage of an extra account could not be read: ${e.javaClass.simpleName}: ${e.message}")
        null
    }

    /**
     * One figure per window across [perAccount]: the share of the pooled allowance that is left,
     * i.e. the mean of the accounts' remaining percentages (each account is one full allowance), the
     * earliest reset among them, and how many accounts reported that window. Tightest first.
     */
    fun combine(perAccount: List<List<UsageLimit>>): List<UsageLimit> {
        if (perAccount.size <= 1) return perAccount.firstOrNull().orEmpty()
        val byWindow = LinkedHashMap<String, MutableList<UsageLimit>>()
        for (limits in perAccount) for (l in limits) byWindow.getOrPut(l.window) { mutableListOf() } += l
        return byWindow.values.map { group ->
            val first = group.first()
            UsageLimit(
                label = first.label,
                remainingPercent = group.map { it.remainingPercent }.average(),
                resetsAt = group.mapNotNull { it.resetsAt }.minOrNull(),
                window = first.window,
                accounts = group.size,
            )
        }.sortedBy { it.remainingPercent }.take(UsageWidgetModel.MAX_LIMITS)
    }
}
