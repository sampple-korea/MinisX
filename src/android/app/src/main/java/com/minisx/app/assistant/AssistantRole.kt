package com.minisx.app.assistant

import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings

/** Whether MinisX is the phone's digital assistant, and where the user changes that. */
object AssistantRole {

    fun isDefault(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 29) {
            runCatching {
                val rm = context.getSystemService(RoleManager::class.java)
                if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_ASSISTANT)) return rm.isRoleHeld(RoleManager.ROLE_ASSISTANT)
            }
        }
        val current = runCatching { Settings.Secure.getString(context.contentResolver, "assistant") }.getOrNull()
        return current != null && ComponentName.unflattenFromString(current)?.packageName == context.packageName
    }

    /**
     * Android does not let an app request the assistant role with a dialog, so the button opens the
     * system page where it is chosen (Digital assistant app), or the default-apps list.
     */
    fun settingsIntents(): List<Intent> = listOf(
        Intent(Settings.ACTION_VOICE_INPUT_SETTINGS),
        Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
        Intent(Settings.ACTION_SETTINGS),
    )

    fun openSettings(context: Context): Boolean {
        for (i in settingsIntents()) {
            val ok = runCatching { context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrDefault(false)
            if (ok) return true
        }
        return false
    }
}
