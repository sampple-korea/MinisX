package com.openminis.app.ui.screensaver

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.service.SessionActivityTracker
import com.openminis.app.ui.settings.getAppearancePrefs
import com.openminis.app.ui.settings.keepScreenAwakeEnabled
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Screen Saver ("Always On"): while a task runs and Keep Screen Awake is on, a
 * black screen takes over after a chosen time without a touch and shows only
 * the current step; a tap brings the chat back.
 *
 * Android port of iOS `AlwaysOnController` / `AlwaysOnOverlay`. The screen is
 * already held awake by `FLAG_KEEP_SCREEN_ON` (MainActivity); this only adds
 * the dark cover, which on an OLED panel is what saves the power.
 *
 * Differences: it is a global setting only — iOS also has a per-chat override in
 * the chat's "…" menu — and "idle" is measured from the last touch anywhere in
 * the app.
 */
object ScreenSaverController {
    /** Same key as iOS `alwaysOnIdleSeconds` / `minis-config chat.alwaysOnIdleSeconds`. */
    const val KEY_IDLE_SECONDS = "alwaysOnIdleSeconds"

    /** Seconds offered in Settings; 0 = Off (the default: a black screen is surprising unless asked for). */
    val CHOICES = listOf(0, 30, 60, 120, 300)

    private val main = Handler(Looper.getMainLooper())
    private val _showing = MutableStateFlow(false)
    val showing: StateFlow<Boolean> = _showing.asStateFlow()

    private lateinit var appContext: Context
    private lateinit var prefs: SharedPreferences
    private var resumed = false
    private var lastTouch = SystemClock.elapsedRealtime()
    private val tick = Runnable { evaluate() }
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == KEY_IDLE_SECONDS || key == com.openminis.app.ui.settings.KEY_KEEP_SCREEN_AWAKE) evaluate()
    }

    fun init(context: Context) {
        if (this::appContext.isInitialized) return
        appContext = context.applicationContext
        prefs = getAppearancePrefs(appContext)
        prefs.registerOnSharedPreferenceChangeListener(prefListener)
    }

    private fun idleMs(): Long? = prefs.getInt(KEY_IDLE_SECONDS, 0).takeIf { it > 0 }?.let { it * 1000L }

    private fun armed(): Boolean =
        resumed && idleMs() != null && keepScreenAwakeEnabled(appContext) &&
            SessionActivityTracker.activeSessions.value.isNotEmpty()

    /** Re-decide: show the cover now, or schedule the moment it should appear, or hide it. */
    fun evaluate() {
        if (!this::appContext.isInitialized) return
        main.removeCallbacks(tick)
        if (!armed()) { _showing.value = false; return }
        val remaining = lastTouch + idleMs()!! - SystemClock.elapsedRealtime()
        if (remaining <= 0) {
            _showing.value = true
        } else {
            _showing.value = false
            main.postDelayed(tick, remaining)
        }
    }

    /** A touch anywhere (MainActivity.dispatchTouchEvent): restart the idle clock and drop the cover. */
    fun userActivity() {
        if (!this::appContext.isInitialized) return
        lastTouch = SystemClock.elapsedRealtime()
        if (_showing.value) _showing.value = false
        evaluate()
    }

    fun onResume() { resumed = true; lastTouch = SystemClock.elapsedRealtime(); evaluate() }
    fun onPause() { resumed = false; evaluate() }
}

/** The black cover. Mount it last in the root; it draws nothing unless the Screen Saver is showing. */
@Composable
fun ScreenSaverOverlay() {
    val showing by ScreenSaverController.showing.collectAsState()
    // A task ending ends the cover (iOS: "a task ending … hides the overlay").
    val active by SessionActivityTracker.activeSessions.collectAsState()
    LaunchedEffect(active) { ScreenSaverController.evaluate() }
    if (!showing) return

    val title by SessionActivityTracker.currentToolTitle.collectAsState()
    val toolName by SessionActivityTracker.currentToolName.collectAsState()
    val startedAt by SessionActivityTracker.currentRunStartedAtMs.collectAsState()
    var seconds by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) { delay(1000); seconds++ }
    }
    // Drift a few pixels every 15 s so a static label cannot burn into the panel.
    val drift = (seconds / 15) % 4
    val dx = intArrayOf(-24, 24, 24, -24)[drift]
    val dy = intArrayOf(-32, -32, 32, 32)[drift]
    val elapsed = startedAt?.let { ((System.currentTimeMillis() - it) / 1000).coerceAtLeast(0) } ?: 0L

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) { detectTapGestures { ScreenSaverController.userActivity() } },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.offset(dx.dp, dy.dp).padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = title?.takeIf { it.isNotBlank() } ?: toolName?.takeIf { it.isNotBlank() } ?: "…",
                color = Color(0xFFB0B0B0), fontSize = 18.sp, textAlign = TextAlign.Center,
                maxLines = 3, overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "%d:%02d".format(elapsed / 60, elapsed % 60),
                color = Color(0xFF606060), fontSize = 14.sp,
            )
        }
    }
}
