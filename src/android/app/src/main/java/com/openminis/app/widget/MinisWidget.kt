package com.openminis.app.widget

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.openminis.app.MinisApp
import com.openminis.app.R
import com.openminis.app.lock.SessionLockStore
import com.openminis.app.service.SessionActivityTracker

/**
 * Home-screen widget: New chat / Voice / Camera, and the latest chat with a
 * "working" marker while it runs a task.
 *
 * The Android stand-in for the iOS Live Activity widget (AgentLiveActivityWidget):
 * Android's lock-screen equivalent of a Live Activity is the foreground-service
 * notification the app already shows, so what a home-screen widget adds here is
 * the quick entry points that the iOS Shortcuts / App Intents provided. Every
 * button is a `minis://` deep link, handled by the normal launch path.
 *
 * A locked chat (biometric lock) is shown as "Locked chat", never by title.
 */
class MinisWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val latest = runCatching {
            (context.applicationContext as MinisApp).chatRepository.dao.listSessions()
                .firstOrNull { !it.isChild }
        }.getOrNull()
        val title = when {
            latest == null -> context.getString(R.string.widget_no_chats)
            SessionLockStore.isLocked(latest.id) -> context.getString(R.string.biolock_row_title)
            else -> latest.title?.takeIf { it.isNotBlank() } ?: context.getString(R.string.shortcut_recent_untitled)
        }
        val working = latest != null && latest.id in SessionActivityTracker.processingSessions.value
        val latestLink = latest?.let { "minis://session/${it.id}" }

        provideContent {
            GlanceTheme {
                Column(
                    GlanceModifier.fillMaxSize().background(GlanceTheme.colors.widgetBackground).padding(12.dp),
                ) {
                    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            context.getString(R.string.app_name),
                            style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 15.sp, fontWeight = FontWeight.Bold),
                        )
                    }
                    Spacer(GlanceModifier.height(8.dp))
                    Row(GlanceModifier.fillMaxWidth()) {
                        ActionButton(context, context.getString(R.string.widget_new_chat), "minis://action/new_chat", GlanceModifier.defaultWeight())
                        Spacer(GlanceModifier.width(6.dp))
                        ActionButton(context, context.getString(R.string.widget_voice), "minis://action/voice_chat", GlanceModifier.defaultWeight())
                        Spacer(GlanceModifier.width(6.dp))
                        ActionButton(context, context.getString(R.string.widget_camera), "minis://action/camera_chat", GlanceModifier.defaultWeight())
                    }
                    Spacer(GlanceModifier.height(8.dp))
                    Text(
                        (if (working) "● " else "") + title,
                        maxLines = 1,
                        style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 13.sp),
                        modifier = if (latestLink != null) GlanceModifier.fillMaxWidth().clickable(actionStartActivity(deepLink(context, latestLink)))
                        else GlanceModifier.fillMaxWidth(),
                    )
                }
            }
        }
    }

    @Composable
    private fun ActionButton(context: Context, label: String, link: String, modifier: GlanceModifier) {
        Row(
            modifier.cornerRadius(12.dp).background(GlanceTheme.colors.primaryContainer).padding(vertical = 10.dp)
                .clickable(actionStartActivity(deepLink(context, link))),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                label, maxLines = 1,
                style = TextStyle(color = GlanceTheme.colors.onPrimaryContainer, fontSize = 13.sp, fontWeight = FontWeight.Medium),
            )
        }
    }

    private fun deepLink(context: Context, link: String): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse(link)).setClassName(context.packageName, "com.openminis.app.MainActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}

class MinisWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = MinisWidget()
}
