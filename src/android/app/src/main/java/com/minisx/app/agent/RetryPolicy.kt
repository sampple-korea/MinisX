package com.minisx.app.agent

import com.minisx.app.data.model.LLMError
import kotlin.random.Random

/**
 * How long to wait before asking the model again after a failed request, and when to stop. A port
 * of what Codex does (`core/src/responses_retry.rs`, `protocol/src/error.rs` `retry_delay`,
 * `codex-client/src/retry.rs` `backoff`), limited to its default behaviour:
 *
 *  - a retryable failure is retried up to [MAX_RETRIES] times (`DEFAULT_STREAM_MAX_RETRIES`);
 *  - the wait is what the server asked for (`Retry-After`) when it said, otherwise exponential from
 *    [BASE_DELAY_MS] with +-10 % jitter;
 *  - when the connection itself fails (no response at all) Codex does not count it against that
 *    budget: it keeps reconnecting, waiting 5 s, then doubling up to 60 s
 *    (`Feature::UnboundedConnectionRetries`, stable and on by default; not used for internal
 *    sessions - here: sub agents and scheduled runs, which pass `unboundedConnection = false`).
 *
 * What is NOT here: rate limits (429). They are handled outside the retry loop, where an account
 * pool rotates to the next account and a model group falls back, and a wait would only hold those up.
 *
 * Pure (the jitter source is a parameter) so the schedule is unit-tested.
 */
object RetryPolicy {

    const val MAX_RETRIES = 5
    const val BASE_DELAY_MS = 200L
    const val CONNECTION_INITIAL_DELAY_MS = 5_000L
    const val CONNECTION_MAX_DELAY_MS = 60_000L

    /** A server asking for longer than this is not waited for here: the caller falls back instead. */
    const val MAX_ADVISED_DELAY_MS = 120_000L

    enum class Kind {
        /** Ordinary backoff. */
        BACKOFF,
        /** The server said how long. */
        SERVER_ADVISED,
        /** The connection failed; keeps going until it works or the user stops. */
        CONNECTION,
    }

    sealed class Decision {
        data class Wait(val delayMs: Long, val kind: Kind, val attempt: Int) : Decision()
        data object GiveUp : Decision()
    }

    /** Counters for one request that keeps failing; a fresh one per request (turn). */
    class State {
        var retries = 0
            internal set
        var connectionRetries = 0
            internal set
        internal var connectionDelayMs = CONNECTION_INITIAL_DELAY_MS
    }

    /** Codex `backoff(base, attempt)`: base * 2^(attempt-1), jittered by 0.9..1.1. */
    fun backoffMs(attempt: Int, jitter: Double = Random.nextDouble(0.9, 1.1)): Long {
        if (attempt <= 0) return BASE_DELAY_MS
        val exp = 1L shl (attempt - 1).coerceAtMost(30)
        return (BASE_DELAY_MS * exp * jitter).toLong()
    }

    fun decide(
        state: State,
        error: LLMError,
        unboundedConnection: Boolean,
        jitter: Double = Random.nextDouble(0.9, 1.1),
    ): Decision {
        if (error is LLMError.NetworkError && unboundedConnection) {
            val delay = state.connectionDelayMs
            state.connectionRetries += 1
            state.connectionDelayMs = (delay * 2).coerceAtMost(CONNECTION_MAX_DELAY_MS)
            return Decision.Wait(delay, Kind.CONNECTION, state.connectionRetries)
        }
        val retryable = error is LLMError.NetworkError || error is LLMError.TransientError
        if (!retryable || state.retries >= MAX_RETRIES) return Decision.GiveUp
        val advised = (error as? LLMError.TransientError)?.retryAfterMs
        if (advised != null && advised > MAX_ADVISED_DELAY_MS) return Decision.GiveUp
        state.retries += 1
        return if (advised != null) {
            Decision.Wait(advised, Kind.SERVER_ADVISED, state.retries)
        } else {
            Decision.Wait(backoffMs(state.retries, jitter), Kind.BACKOFF, state.retries)
        }
    }
}
