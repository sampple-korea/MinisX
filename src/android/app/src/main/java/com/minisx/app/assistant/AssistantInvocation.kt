package com.minisx.app.assistant

import java.io.File
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * What the system hands MinisX when the user invokes it as their assistant: the screen as it was
 * just before (a screenshot and the text on it), and which app it belonged to.
 */
data class AssistantInvocation(
    /** A JPEG of the screen before MinisX came up, or null when no way to capture it worked. */
    val screenshot: File?,
    /** Visible text on that screen (capped), or null. */
    val screenText: String?,
    val packageName: String?,
    /** A page address the foreground app reported (browsers do), or null. */
    val webUri: String?,
    /** `session`, `assist-intent` or `tile`: how the user got here. */
    val source: String,
    val capturedAtMs: Long = System.currentTimeMillis(),
)

/**
 * Hand-over of an [AssistantInvocation] from the assistant session to [com.minisx.app.MainActivity].
 *
 * An invocation sends the screen to the model and may act on the phone, so it must be impossible for
 * another app to fake one. It therefore never travels in a URL (every `minis://` link is public and
 * prefill-only); the session registers it here, in this process, and passes only the random token in
 * an explicit intent. A token is single-use and short-lived.
 */
object AssistantInvocations {
    private const val TTL_MS = 60_000L
    private val random = SecureRandom()
    private val pending = ConcurrentHashMap<String, AssistantInvocation>()

    const val EXTRA_TOKEN = "com.minisx.app.extra.ASSISTANT_TOKEN"

    fun register(invocation: AssistantInvocation): String {
        sweep()
        val bytes = ByteArray(16).also { random.nextBytes(it) }
        val token = bytes.joinToString("") { "%02x".format(it) }
        pending[token] = invocation
        return token
    }

    /** The invocation behind [token], once; null for an unknown, used or expired token. */
    fun redeem(token: String?, now: Long = System.currentTimeMillis()): AssistantInvocation? {
        if (token.isNullOrEmpty()) return null
        val inv = pending.remove(token) ?: return null
        return if (now - inv.capturedAtMs <= TTL_MS * 2) inv else null
    }

    private fun sweep(now: Long = System.currentTimeMillis()) {
        pending.entries.removeIf { now - it.value.capturedAtMs > TTL_MS * 2 }
    }

    internal fun clearForTest() = pending.clear()
}
