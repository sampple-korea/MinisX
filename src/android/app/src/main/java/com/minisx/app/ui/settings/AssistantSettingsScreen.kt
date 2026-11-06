package com.minisx.app.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.minisx.app.R
import com.minisx.app.accessibility.MinisAccessibilityService
import com.minisx.app.assistant.AssistantRole
import com.minisx.app.assistant.AssistantSettings

/**
 * Settings > Assistant: make MinisX the phone's digital assistant (power-button long press, home-corner
 * swipe) and choose what it sees when it is called.
 */
@Composable
fun AssistantSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var isDefault by remember { mutableStateOf(AssistantRole.isDefault(context)) }
    var screenshot by remember { mutableStateOf(AssistantSettings.screenshot) }
    var screenText by remember { mutableStateOf(AssistantSettings.screenText) }
    var fastPath by remember { mutableStateOf(AssistantSettings.fastPath) }
    var fullScreen by remember { mutableStateOf(AssistantSettings.fullScreen) }
    // The user changes the default on a system page and comes back.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { isDefault = AssistantRole.isDefault(context) }

    SettingsScaffold(title = stringResource(R.string.assistant_title), onBack = onBack) {
        SettingsSection(
            header = stringResource(R.string.assistant_section_default),
            footer = stringResource(R.string.assistant_default_footer),
        ) {
            SettingsRow(
                title = stringResource(R.string.assistant_default_row),
                subtitle = stringResource(if (isDefault) R.string.assistant_default_yes else R.string.assistant_default_no),
                icon = Icons.Outlined.AutoAwesome,
                onClick = { AssistantRole.openSettings(context) },
                showDivider = true,
            )
            SettingsSwitchRow(
                title = stringResource(R.string.assistant_fullscreen),
                subtitle = stringResource(R.string.assistant_fullscreen_sub),
                checked = fullScreen,
                onCheckedChange = { fullScreen = it; AssistantSettings.fullScreen = it },
                showDivider = false,
            )
        }

        SettingsSection(
            header = stringResource(R.string.assistant_section_screen),
            footer = stringResource(R.string.assistant_screen_footer),
        ) {
            SettingsSwitchRow(
                title = stringResource(R.string.assistant_screenshot),
                subtitle = stringResource(R.string.assistant_screenshot_sub),
                checked = screenshot,
                onCheckedChange = { screenshot = it; AssistantSettings.screenshot = it },
                icon = Icons.Outlined.PhotoCamera,
            )
            SettingsSwitchRow(
                title = stringResource(R.string.assistant_screen_text),
                subtitle = stringResource(R.string.assistant_screen_text_sub),
                checked = screenText,
                onCheckedChange = { screenText = it; AssistantSettings.screenText = it },
                icon = Icons.Outlined.TextFields,
            )
            SettingsRow(
                title = stringResource(R.string.assistant_capture_methods),
                subtitle = stringResource(
                    if (MinisAccessibilityService.getInstance() != null) R.string.assistant_capture_a11y_on else R.string.assistant_capture_a11y_off,
                ),
                showDivider = false,
            )
        }

        SettingsSection(
            header = stringResource(R.string.assistant_section_fast),
            footer = stringResource(R.string.assistant_fast_footer),
        ) {
            SettingsSwitchRow(
                title = stringResource(R.string.assistant_fast_path),
                subtitle = stringResource(R.string.assistant_fast_path_sub),
                checked = fastPath,
                onCheckedChange = { fastPath = it; AssistantSettings.fastPath = it },
                icon = Icons.Outlined.Bolt,
                showDivider = false,
            )
        }
    }
}
