package com.minisx.app.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.minisx.app.R
import com.minisx.app.lock.BiometricAuth
import com.minisx.app.lock.SessionLockStore
import com.minisx.app.lock.findFragmentActivity
import kotlinx.coroutines.launch

/**
 * Settings → Biometric Lock. Port of iOS `FaceIDProtectionSettingsView`:
 * the app lock, the per-chat lock switch and the two re-lock timeouts.
 * Turning either switch on asks for one successful prompt first, so it cannot
 * be enabled by someone who only holds the unlocked phone's open app.
 */
@Composable
fun BiometricLockScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val version by SessionLockStore.version.collectAsState()
    val available = remember { BiometricAuth.isAvailable(context) }
    var showAppIdle by remember { mutableStateOf(false) }
    var showChatIdle by remember { mutableStateOf(false) }

    fun idleLabel(seconds: Int): String = when (seconds) {
        -1 -> context.getString(R.string.biolock_idle_exit)
        30 -> context.getString(R.string.biolock_idle_30s)
        60 -> context.getString(R.string.biolock_idle_1m)
        300 -> context.getString(R.string.biolock_idle_5m)
        600 -> context.getString(R.string.biolock_idle_10m)
        900 -> context.getString(R.string.biolock_idle_15m)
        3600 -> context.getString(R.string.biolock_idle_1h)
        0 -> context.getString(R.string.biolock_idle_never)
        else -> "$seconds s"
    }

    /** Requires one successful prompt, then runs [apply]. */
    fun confirmThen(apply: () -> Unit) {
        val activity = context.findFragmentActivity() ?: return
        scope.launch {
            val ok = BiometricAuth.authenticate(
                activity,
                title = context.getString(R.string.biolock_prompt_enable),
                cancelLabel = context.getString(R.string.biolock_cancel),
            )
            if (ok) apply()
        }
    }

    SettingsScaffold(title = stringResource(R.string.biolock_settings_title), onBack = onBack) {
        // Re-read on every store change (version is the dependency).
        @Suppress("UNUSED_EXPRESSION") version

        if (!available) {
            SettingsSection(footer = stringResource(R.string.biolock_unavailable)) {
                SettingsRow(
                    title = stringResource(R.string.biolock_settings_title),
                    icon = Icons.Outlined.Fingerprint,
                    showDivider = false,
                )
            }
        }

        SettingsSection(
            header = stringResource(R.string.biolock_section_app),
            footer = stringResource(R.string.biolock_section_app_footer),
        ) {
            SettingsSwitchRow(
                title = stringResource(R.string.biolock_app_lock),
                checked = SessionLockStore.appLockEnabled,
                enabled = available,
                showDivider = SessionLockStore.appLockEnabled,
                onCheckedChange = { on ->
                    if (on) confirmThen {
                        SessionLockStore.appLockEnabled = true
                        // Counts as unlocked now: the user just passed the prompt.
                        SessionLockStore.noteAppUnlock()
                    } else confirmThen { SessionLockStore.appLockEnabled = false }
                },
            )
            if (SessionLockStore.appLockEnabled) {
                SettingsValueRow(
                    title = stringResource(R.string.biolock_app_relock),
                    value = idleLabel(SessionLockStore.appLockIdleSeconds),
                    onClick = { showAppIdle = !showAppIdle },
                    showDivider = showAppIdle,
                )
                if (showAppIdle) IdleChoices(SessionLockStore.appLockIdleSeconds, ::idleLabel) {
                    SessionLockStore.appLockIdleSeconds = it; showAppIdle = false
                }
            }
        }

        SettingsSection(
            header = stringResource(R.string.biolock_section_chats),
            footer = stringResource(R.string.biolock_section_chats_footer),
        ) {
            SettingsSwitchRow(
                title = stringResource(R.string.biolock_chats_enable),
                checked = SessionLockStore.globalEnabled,
                enabled = available,
                showDivider = SessionLockStore.globalEnabled,
                onCheckedChange = { on ->
                    if (on) confirmThen { SessionLockStore.globalEnabled = true }
                    else confirmThen { SessionLockStore.globalEnabled = false }
                },
            )
            if (SessionLockStore.globalEnabled) {
                SettingsValueRow(
                    title = stringResource(R.string.biolock_chat_relock),
                    value = idleLabel(SessionLockStore.idleTimeoutSeconds),
                    onClick = { showChatIdle = !showChatIdle },
                    showDivider = showChatIdle,
                )
                if (showChatIdle) IdleChoices(SessionLockStore.idleTimeoutSeconds, ::idleLabel) {
                    SessionLockStore.idleTimeoutSeconds = it; showChatIdle = false
                }
            }
        }
    }
}

@Composable
private fun IdleChoices(current: Int, label: (Int) -> String, onPick: (Int) -> Unit) {
    val options = SessionLockStore.IDLE_OPTIONS
    options.forEachIndexed { i, seconds ->
        SettingsChoiceRow(
            title = label(seconds),
            selected = seconds == current,
            onSelect = { onPick(seconds) },
            showDivider = i < options.size - 1,
        )
    }
}
