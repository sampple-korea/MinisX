package com.minisx.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.minisx.app.R
import com.minisx.app.tools.AgentToolSwitch

/**
 * [T-tools-granular-switches] Settings › Agent Runtime › Tools — port of iOS
 * `ToolsSettingsView`.
 *
 * One switch per optional capability. The switches gate the tool SCHEMA, so a
 * disabled tool is not merely refused at call time — the model never sees it
 * and cannot plan around it. The remaining tools (shell, files, memory) are
 * core to the agent and deliberately have no switch.
 *
 * The title stays "Tools" in English to sit alongside Skills and Memory in the
 * Agent Runtime section; locales whose bare "tools" reads as hardware render
 * it as "available tools" instead.
 */
@Composable
fun AgentToolsSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var browserEnabled by remember {
        mutableStateOf(AgentToolSwitch.BROWSER.isEnabled(context))
    }
    var userAskEnabled by remember {
        mutableStateOf(com.minisx.app.agent.userask.UserAskSettings.isEnabled(context))
    }
    var userAskTimeout by remember {
        mutableStateOf(com.minisx.app.agent.userask.UserAskSettings.timeoutMinutes(context))
    }
    var userAskNotify by remember {
        mutableStateOf(com.minisx.app.agent.userask.UserAskSettings.notifyInBackground(context))
    }
    var showWaitPicker by remember { mutableStateOf(false) }
    // The advisor: a stronger model the agent can consult (Claude Code's /advisor).
    var advisorEntryId by remember { mutableStateOf(com.minisx.app.agent.advisor.AdvisorSettings.entryId(context)) }
    var showAdvisorPicker by remember { mutableStateOf(false) }
    val providerRepository = (context.applicationContext as com.minisx.app.MinisApp).providerRepositoryOrNull
    val advisorChoices = remember(showAdvisorPicker) {
        providerRepository?.config?.value?.let { cfg ->
            cfg.modelEntries.filter { e -> !e.isHidden && cfg.instances.any { it.id == e.providerInstanceId && it.isEnabled } }
                .map { e ->
                    val inst = cfg.instances.find { it.id == e.providerInstanceId }
                    e.id to ((inst?.label?.ifEmpty { null } ?: e.model.provider) + " · " + e.model.displayName)
                }
        }.orEmpty()
    }
    val advisorName = advisorChoices.firstOrNull { it.first == advisorEntryId }?.second
        ?: providerRepository?.config?.value?.modelEntries?.find { it.id == advisorEntryId }?.model?.displayName

    SettingsScaffold(title = stringResource(R.string.settings_agent_tools), onBack = onBack) {
        SettingsSection(footer = stringResource(R.string.agent_tools_browser_footer)) {
            SettingsSwitchRow(
                title = stringResource(R.string.agent_tools_browser),
                checked = browserEnabled,
                onCheckedChange = {
                    browserEnabled = it
                    AgentToolSwitch.BROWSER.setEnabled(context, it)
                },
                showDivider = false,
            )
        }
        // The advisor: pick a (stronger) model; the agent may then consult it before big steps and
        // before declaring work done. Off until a model is chosen.
        SettingsSection(footer = stringResource(R.string.advisor_setting_footer)) {
            SettingsValueRow(
                title = stringResource(R.string.advisor_setting_title),
                value = advisorName ?: stringResource(R.string.advisor_setting_off),
                onClick = { showAdvisorPicker = true },
                showDivider = false,
            )
        }
        if (showAdvisorPicker) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { showAdvisorPicker = false },
                title = { androidx.compose.material3.Text(stringResource(R.string.advisor_setting_title)) },
                text = {
                    androidx.compose.foundation.layout.Column(
                        modifier = androidx.compose.ui.Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()),
                    ) {
                        SettingsChoiceRow(
                            title = stringResource(R.string.advisor_setting_off),
                            selected = advisorEntryId == null,
                            onSelect = {
                                advisorEntryId = null
                                com.minisx.app.agent.advisor.AdvisorSettings.setEntryId(context, null)
                                showAdvisorPicker = false
                            },
                            showDivider = false,
                        )
                        for ((id, label) in advisorChoices) {
                            SettingsChoiceRow(
                                title = label,
                                selected = id == advisorEntryId,
                                onSelect = {
                                    advisorEntryId = id
                                    com.minisx.app.agent.advisor.AdvisorSettings.setEntryId(context, id)
                                    showAdvisorPicker = false
                                },
                                showDivider = false,
                            )
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = { showAdvisorPicker = false }) {
                        androidx.compose.material3.Text(stringResource(R.string.user_ask_cancel))
                    }
                },
            )
        }
        // [T-port-user-ask] user_ask: on/off plus how long a question waits
        // (iOS ToolsSettingsView.userAskSections), and whether a question asked
        // while the app is away is posted as a notification.
        SettingsSection(footer = stringResource(R.string.user_ask_setting_footer)) {
            SettingsSwitchRow(
                title = stringResource(R.string.user_ask_setting_title),
                checked = userAskEnabled,
                onCheckedChange = {
                    userAskEnabled = it
                    com.minisx.app.agent.userask.UserAskSettings.setEnabled(context, it)
                },
                showDivider = userAskEnabled,
            )
            if (userAskEnabled) {
                SettingsValueRow(
                    title = stringResource(R.string.user_ask_setting_wait),
                    value = userAskWaitLabel(userAskTimeout),
                    onClick = { showWaitPicker = true },
                    showDivider = true,
                )
                SettingsSwitchRow(
                    title = stringResource(R.string.user_ask_setting_notify),
                    checked = userAskNotify,
                    onCheckedChange = {
                        userAskNotify = it
                        com.minisx.app.agent.userask.UserAskSettings.setNotifyInBackground(context, it)
                    },
                    showDivider = false,
                )
            }
        }
        if (showWaitPicker) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { showWaitPicker = false },
                title = { androidx.compose.material3.Text(stringResource(R.string.user_ask_setting_wait)) },
                text = {
                    androidx.compose.foundation.layout.Column {
                        for (m in com.minisx.app.agent.userask.UserAskSettingsSpec.TIMEOUT_CHOICES) {
                            SettingsChoiceRow(
                                title = userAskWaitLabel(m),
                                selected = m == userAskTimeout,
                                onSelect = {
                                    userAskTimeout = m
                                    com.minisx.app.agent.userask.UserAskSettings.setTimeoutMinutes(context, m)
                                    showWaitPicker = false
                                },
                                showDivider = false,
                            )
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = { showWaitPicker = false }) {
                        androidx.compose.material3.Text(stringResource(R.string.user_ask_cancel))
                    }
                },
            )
        }
        // [T-android-subagent-settings-parity] The agents switch is NOT here.
        //
        // It lives on Settings > Agents > Sub Agents, which is also where the
        // roster is configured — one place to look, and no second copy of the
        // switch to drift out of sync with it. Mirrors iOS, whose
        // ToolsSettingsView likewise only migrates the legacy key and shows no
        // agents row.
    }
}

/** "5 min", or "Indefinitely" for 0. */
@Composable
private fun userAskWaitLabel(minutes: Int): String =
    if (minutes == 0) stringResource(R.string.user_ask_setting_indefinitely)
    else stringResource(R.string.user_ask_setting_minutes, minutes)
