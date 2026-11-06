package com.minisx.app.provider

import com.minisx.app.data.model.LLMError
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * The delay a server asked for in its `Retry-After` (seconds or an HTTP date) or the millisecond
 * variant `retry-after-ms` some APIs send. Codex prefers this advice to its own backoff
 * (`CodexErr::retry_delay` -> `server_retry_delay`), and so does the retry loop here.
 */
object RetryAfterHeader {

    /** Milliseconds to wait, or null when neither header carries a usable value. */
    fun parseMs(retryAfter: String?, retryAfterMs: String?, nowMs: Long): Long? {
        retryAfterMs?.trim()?.toDoubleOrNull()?.takeIf { it >= 0 }?.let { return it.toLong() }
        val value = retryAfter?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        value.toDoubleOrNull()?.takeIf { it >= 0 }?.let { return (it * 1000).toLong() }
        return parseHttpDate(value)?.let { (it - nowMs).coerceAtLeast(0) }
    }

    private fun parseHttpDate(value: String): Long? = try {
        SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("GMT") }
            .parse(value)?.time
    } catch (_: Exception) {
        null
    }

    /** [error] carrying the server's advice, when it is a kind that can carry one and has none yet. */
    fun attach(error: LLMError, retryAfter: String?, retryAfterMs: String?, nowMs: Long = System.currentTimeMillis()): LLMError {
        val advice = parseMs(retryAfter, retryAfterMs, nowMs) ?: return error
        return when (error) {
            is LLMError.TransientError ->
                if (error.retryAfterMs != null) error else LLMError.TransientError(error.detail, error.httpStatus, advice)
            is LLMError.RateLimited ->
                if (error.retryAfterMs != null) error else LLMError.RateLimited(advice)
            else -> error
        }
    }
}
