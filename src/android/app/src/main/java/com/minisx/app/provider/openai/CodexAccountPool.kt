package com.minisx.app.provider.openai

import android.content.Context
import com.minisx.app.data.model.LLMError
import org.json.JSONObject

/**
 * [T-codex-account-pool] Several ChatGPT/Codex sign-ins used as one: requests go to the first
 * account that is not cooling down, and an account that hits its usage limit (HTTP 429) is put on
 * hold until the limit resets, so the next request - and the retry of the one that just failed -
 * uses the next account. When the held account's limit lifts it is first in line again
 * (fill-first, in the order the providers are listed).
 *
 * Pure logic over a [CooldownStore] and a clock so it can be tested without a device.
 */
class CodexAccountPool(
    val members: List<Member>,
    private val cooldowns: CooldownStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    class Member(
        val instanceId: String,
        val label: String,
        val accountId: () -> String?,
        val token: suspend () -> String,
    )

    /** The account a request was built for, with its token. Carried on the OkHttp request. */
    class Lease(val member: Member, val token: String)

    class Status(val member: Member, val coolingUntilMs: Long?)

    interface CooldownStore {
        fun untilMs(instanceId: String): Long
        fun set(instanceId: String, untilMs: Long)
    }

    class MemoryCooldowns : CooldownStore {
        private val map = java.util.concurrent.ConcurrentHashMap<String, Long>()
        override fun untilMs(instanceId: String) = map[instanceId] ?: 0L
        override fun set(instanceId: String, untilMs: Long) { map[instanceId] = untilMs }
    }

    /** Survives a restart, so a limit that resets in three hours is still known after the app is killed. */
    class PrefsCooldowns private constructor(context: Context) : CooldownStore {
        private val prefs = context.applicationContext.getSharedPreferences("codex_account_pool", Context.MODE_PRIVATE)
        override fun untilMs(instanceId: String) = prefs.getLong("until_$instanceId", 0L)
        override fun set(instanceId: String, untilMs: Long) { prefs.edit().putLong("until_$instanceId", untilMs).apply() }

        companion object {
            @Volatile private var instance: PrefsCooldowns? = null
            fun get(context: Context): PrefsCooldowns =
                instance ?: synchronized(this) { instance ?: PrefsCooldowns(context).also { instance = it } }
        }
    }

    private fun now() = clock()

    fun isCoolingDown(instanceId: String): Boolean = cooldowns.untilMs(instanceId) > now()

    /** The account the next request would use, or null when every one is on hold. */
    fun active(): Member? = members.firstOrNull { !isCoolingDown(it.instanceId) }

    fun hasAvailable(): Boolean = active() != null

    /**
     * Pick the first account that is not on hold and get its token. An account whose token cannot
     * be produced (signed out, refresh rejected) is put on hold for a while and skipped.
     */
    suspend fun lease(): Lease {
        var rejected = 0
        var rateLimited = 0
        for (m in members) {
            if (isCoolingDown(m.instanceId)) { rateLimited++; continue }
            try {
                return Lease(m, m.token())
            } catch (e: LLMError.InvalidApiKey) {
                reportRejected(m.instanceId)
                rejected++
            }
        }
        if (rejected > 0 && rateLimited == 0) throw LLMError.InvalidApiKey()
        throw LLMError.RateLimited(retryAfterMs = soonestResetMs())
    }

    /** [retryAfterMs] null = the provider did not say; hold the account briefly and try it again later. */
    fun reportRateLimited(instanceId: String, retryAfterMs: Long?) {
        val hold = (retryAfterMs ?: DEFAULT_COOLDOWN_MS).coerceIn(MIN_COOLDOWN_MS, MAX_COOLDOWN_MS)
        cooldowns.set(instanceId, now() + hold)
    }

    /** The account was refused (401/403): keep it out of rotation for a while. */
    fun reportRejected(instanceId: String) {
        cooldowns.set(instanceId, now() + REJECTED_COOLDOWN_MS)
    }

    /** Milliseconds until the first held account is available again, or null when none is held. */
    fun soonestResetMs(): Long? =
        members.map { cooldowns.untilMs(it.instanceId) - now() }.filter { it > 0 }.minOrNull()

    fun status(): List<Status> = members.map { m ->
        val until = cooldowns.untilMs(m.instanceId)
        Status(m, until.takeIf { it > now() })
    }

    companion object {
        const val DEFAULT_COOLDOWN_MS = 60_000L
        const val MIN_COOLDOWN_MS = 5_000L
        const val REJECTED_COOLDOWN_MS = 10 * 60_000L
        const val MAX_COOLDOWN_MS = 7 * 24 * 3_600_000L

        /**
         * How long the limit lasts, from a 429 body. The ChatGPT/Codex usage-limit reply is
         * `{"error":{"type":"usage_limit_reached","resets_in_seconds":N,"resets_at":epochSeconds}}`.
         * Null when the body says nothing usable.
         */
        fun retryAfterMs(body: String, nowMs: Long): Long? {
            val error = try { JSONObject(body).optJSONObject("error") } catch (_: Exception) { null } ?: return null
            if (error.has("resets_in_seconds")) {
                val s = error.optDouble("resets_in_seconds", -1.0)
                if (s >= 0) return (s * 1000).toLong()
            }
            if (error.has("resets_at")) {
                val at = error.optDouble("resets_at", -1.0)
                if (at > 0) return (at * 1000).toLong() - nowMs
            }
            return null
        }
    }
}
