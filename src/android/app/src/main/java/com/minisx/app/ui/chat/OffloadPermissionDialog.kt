package com.minisx.app.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.minisx.app.R
import com.minisx.app.offload.OffloadPermissionManager
import com.minisx.app.ui.components.MinisButton
import com.minisx.app.ui.components.MinisOutlinedButton
import com.minisx.app.ui.components.MinisTextButton

/**
 * Dialog shown when an ASK_ONCE tool requests permission.
 *
 * T338: three session-scoped options. None of them ever upgrade the
 * stored PermissionLevel — to make a permanent change the user goes
 * to Settings → Permissions and picks Bypass / Not Allowed there.
 *
 *   • Allow in this session — grant for the remaining session, no
 *     more prompts for this tool until the session ends.
 *   • Allow once            — grant only this call; next call re-prompts.
 *   • Deny in this session  — refuse + remember, so the agent can't
 *     spam the same dialog after the user already said no.
 *
 * Dismissing via tap-outside or system back maps to DENY_SESSION too;
 * a silent dismiss should not invite the agent to keep retrying.
 */
@Composable
fun OffloadPermissionDialog() {
    val request by OffloadPermissionManager.pendingRequest.collectAsState()
    val req = request ?: return

    AlertDialog(
        onDismissRequest = {
            OffloadPermissionManager.respondToRequest(OffloadPermissionManager.Response.DENY_SESSION)
        },
        title = {
            Text(
                stringResource(if (req.detail != null) R.string.root_approval_title else R.string.offload_perm_dialog_title),
                fontWeight = FontWeight.Bold,
            )
        },
        text = if (req.detail != null) {
            { RootApprovalBody(req) }
        } else {
            {
            Column {
                Text(
                    stringResource(R.string.offload_perm_dialog_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    req.toolTitle,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.offload_perm_dialog_tool_label, req.toolName),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    req.description,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            }
        },
        confirmButton = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // A root command (detail set) leads with "Allow once": the safe choice is the easy one.
                val onceFirst = req.detail != null
                val allowSession = OffloadPermissionManager.Response.ALLOW_SESSION
                val allowOnce = OffloadPermissionManager.Response.ALLOW_ONCE
                MinisButton(
                    onClick = { OffloadPermissionManager.respondToRequest(if (onceFirst) allowOnce else allowSession) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(if (onceFirst) R.string.root_approval_allow_once else R.string.offload_perm_allow_session))
                }
                MinisOutlinedButton(
                    onClick = { OffloadPermissionManager.respondToRequest(if (onceFirst) allowSession else allowOnce) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(if (onceFirst) R.string.root_approval_allow_same else R.string.offload_perm_allow_once))
                }
                // Deny in this session — refuse + remember.
                MinisTextButton(
                    onClick = { OffloadPermissionManager.respondToRequest(OffloadPermissionManager.Response.DENY_SESSION) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text(stringResource(if (onceFirst) R.string.root_approval_deny else R.string.offload_perm_deny_session))
                }
            }
        },
        dismissButton = null,
    )
}

/** The body of a root command's approval: the command, what the agent says it does, and what the app found risky. */
@Composable
private fun RootApprovalBody(req: OffloadPermissionManager.PermissionRequest) {
    androidx.compose.foundation.layout.Column(
        modifier = Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()),
    ) {
        Text(
            stringResource(R.string.root_approval_command_label),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        androidx.compose.material3.Surface(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Text(
                req.detail.orEmpty(),
                modifier = Modifier.fillMaxWidth().padding(10.dp),
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.root_approval_explanation_label),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            req.explanation ?: stringResource(R.string.root_approval_no_explanation),
            style = MaterialTheme.typography.bodyMedium,
        )
        if (req.risks.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.root_approval_risk_label),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.height(4.dp))
            for (risk in req.risks) {
                Text(
                    "• " + stringResource(riskText(risk)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (risk.high) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.root_approval_footer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun riskText(risk: com.minisx.app.offload.RootCommandRisk.Risk): Int = when (risk) {
    com.minisx.app.offload.RootCommandRisk.Risk.WIPES_DATA -> R.string.root_risk_wipes_data
    com.minisx.app.offload.RootCommandRisk.Risk.DELETES_FILES -> R.string.root_risk_deletes_files
    com.minisx.app.offload.RootCommandRisk.Risk.REBOOT_OR_SHUTDOWN -> R.string.root_risk_reboot
    com.minisx.app.offload.RootCommandRisk.Risk.DOWNLOADS_AND_RUNS -> R.string.root_risk_downloads_and_runs
    com.minisx.app.offload.RootCommandRisk.Risk.REMOUNTS_SYSTEM -> R.string.root_risk_remounts_system
    com.minisx.app.offload.RootCommandRisk.Risk.EDITS_SECURITY -> R.string.root_risk_edits_security
    com.minisx.app.offload.RootCommandRisk.Risk.RECURSIVE_PERMISSIONS -> R.string.root_risk_recursive_permissions
    com.minisx.app.offload.RootCommandRisk.Risk.REMOVES_APPS -> R.string.root_risk_removes_apps
    com.minisx.app.offload.RootCommandRisk.Risk.CHANGES_SETTINGS -> R.string.root_risk_changes_settings
    com.minisx.app.offload.RootCommandRisk.Risk.STOPS_PROCESSES -> R.string.root_risk_stops_processes
    com.minisx.app.offload.RootCommandRisk.Risk.WRITES_SYSTEM_FILES -> R.string.root_risk_writes_system_files
    com.minisx.app.offload.RootCommandRisk.Risk.CHANGES_NETWORK -> R.string.root_risk_changes_network
}
