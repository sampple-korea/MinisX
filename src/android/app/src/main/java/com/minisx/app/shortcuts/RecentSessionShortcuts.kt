package com.minisx.app.shortcuts

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.minisx.app.R
import com.minisx.app.data.repository.ChatRepository
import com.minisx.app.lock.SessionLockStore
import com.minisx.app.logging.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Publishes the most recent chats as launcher shortcuts (long-press the app
 * icon, or the system share/assistant surfaces), next to the static New /
 * Voice / Camera shortcuts.
 *
 * This is the Android counterpart of the iOS `ListSessionsIntent` /
 * `OpenSessionIntent` pair: the system can offer "open this chat" without the
 * app being opened first. A shortcut is just a `minis://session/<id>` link, so
 * it goes through the normal deep-link path.
 *
 * Locked chats (biometric lock) are left out, and a lock being turned on or off
 * republishes — a shortcut label is visible on the launcher, outside the lock.
 */
object RecentSessionShortcuts {
    private const val TAG = "RecentShortcuts"
    private const val MAX = 3
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @OptIn(FlowPreview::class)
    fun start(context: Context, chatRepository: ChatRepository) {
        val app = context.applicationContext
        scope.launch {
            // Re-filter when a lock changes too: the session list itself did not move.
            combine(chatRepository.dao.observeSessionsSorted(), SessionLockStore.version) { sessions, _ ->
                sessions.asSequence()
                    .filter { !it.isChild && !SessionLockStore.isLocked(it.id) }
                    .take(MAX)
                    .map { it.id to (it.title?.takeIf { t -> t.isNotBlank() } ?: app.getString(R.string.shortcut_recent_untitled)) }
                    .toList()
            }
                .distinctUntilChanged()
                .debounce(2_000) // a streaming reply updates the row constantly
                .collect { recents -> publish(app, recents) }
        }
    }

    private fun publish(context: Context, recents: List<Pair<String, String>>) {
        runCatching {
            val shortcuts = recents.mapIndexed { index, (id, title) ->
                ShortcutInfoCompat.Builder(context, "recent_$id")
                    .setShortLabel(title.take(24))
                    .setLongLabel(title.take(60))
                    .setIcon(IconCompat.createWithResource(context, R.mipmap.ic_shortcut_new_chat))
                    .setRank(index)
                    .setIntent(
                        Intent(Intent.ACTION_VIEW, Uri.parse("minis://session/$id")).setClassName(context.packageName, "com.minisx.app.MainActivity"),
                    )
                    .build()
            }
            ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts)
        }.onFailure { AppLogger.warning(TAG, "publish failed: ${it.message}") }
    }
}
