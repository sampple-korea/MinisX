package com.minisx.app.live

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.minisx.app.logging.AppLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The one Live conversation the app can have at a time, and what the UI needs
 * to follow it. The audio work lives in [LiveSession]; [LiveVoiceService] keeps
 * the process, microphone and notification alive while it runs.
 */
object LiveCall {
    private const val TAG = "LiveCall"

    private val _current = MutableStateFlow<LiveSession?>(null)
    val current: StateFlow<LiveSession?> = _current.asStateFlow()

    /** The chat that started the call (its agent does the delegated work). */
    @Volatile var ownerSessionId: String? = null
        private set

    /** The panel was minimized: the call continues, the notification brings it back. */
    private val _minimized = MutableStateFlow(false)
    val minimized: StateFlow<Boolean> = _minimized.asStateFlow()

    /** Why the last call ended, if it ended badly; the UI shows it once and clears it. */
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    val isActive: Boolean get() = _current.value?.state?.value?.phase.let { it != null && it != LiveSession.Phase.ENDED }

    sealed class StartResult {
        data object Started : StartResult()
        data class Refused(val reason: String) : StartResult()
    }

    fun start(context: Context, config: LiveSession.Config, owner: String, runTask: suspend (String) -> String): StartResult {
        if (isActive) return StartResult.Refused("A voice call is already running.")
        val session = LiveSession(config, runTask)
        _current.value = session
        ownerSessionId = owner
        _minimized.value = false
        _lastError.value = null
        return try {
            ContextCompat.startForegroundService(
                context.applicationContext,
                Intent(context.applicationContext, LiveVoiceService::class.java).setAction(LiveVoiceService.ACTION_START),
            )
            StartResult.Started
        } catch (e: Throwable) {
            AppLogger.warning(TAG, "could not start the call service: ${e.message}")
            _current.value = null
            ownerSessionId = null
            StartResult.Refused(e.message ?: "The call service could not start.")
        }
    }

    fun end() { _current.value?.end() }

    fun setMinimized(value: Boolean) { _minimized.value = value }

    fun consumeError(): String? = _lastError.value.also { _lastError.value = null }

    /** The service calls this when the session is over and everything is released. */
    internal fun finished(session: LiveSession, error: String?) {
        if (_current.value === session) {
            _current.value = null
            ownerSessionId = null
            _minimized.value = false
            if (error != null) _lastError.value = error
        }
    }
}
