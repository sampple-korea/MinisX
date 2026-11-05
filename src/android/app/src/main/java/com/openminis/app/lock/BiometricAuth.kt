package com.openminis.app.lock

import android.content.Context
import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Thin BiometricPrompt wrapper: one `suspend` entry point, like iOS
 * `BiometricAuth.authenticate`. Accepts a fingerprint / face / iris, and the
 * screen lock (PIN, pattern, password) as the fallback — iOS's
 * `deviceOwnerAuthentication` policy. Before Android 11 the system cannot
 * combine the two in one prompt, so there it is biometrics only.
 */
object BiometricAuth {

    private val authenticators: Int =
        if (Build.VERSION.SDK_INT >= 30) Authenticators.BIOMETRIC_WEAK or Authenticators.DEVICE_CREDENTIAL
        else Authenticators.BIOMETRIC_WEAK

    /** Whether the device can authenticate the owner right now (enrolled biometrics or a screen lock). */
    fun isAvailable(context: Context): Boolean =
        runCatching { BiometricManager.from(context).canAuthenticate(authenticators) == BiometricManager.BIOMETRIC_SUCCESS }
            .getOrDefault(false)

    /**
     * Shows the system prompt. True on success; false on cancel, lock-out or
     * error. Must be called from the main thread with a resumed activity.
     */
    suspend fun authenticate(
        activity: FragmentActivity,
        title: String,
        subtitle: String? = null,
        cancelLabel: String,
    ): Boolean = suspendCancellableCoroutine { cont ->
        val executor = ContextCompat.getMainExecutor(activity)
        val prompt = BiometricPrompt(
            activity, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    if (cont.isActive) cont.resume(true)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (cont.isActive) cont.resume(false)
                }
                // onAuthenticationFailed = a rejected attempt; the prompt stays up for a retry.
            },
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .apply { subtitle?.let { setSubtitle(it) } }
            .setAllowedAuthenticators(authenticators)
            .apply {
                // The negative button is only allowed (and required) without DEVICE_CREDENTIAL.
                if (authenticators and Authenticators.DEVICE_CREDENTIAL == 0) setNegativeButtonText(cancelLabel)
            }
            .build()
        prompt.authenticate(info)
        cont.invokeOnCancellation { runCatching { prompt.cancelAuthentication() } }
    }
}
