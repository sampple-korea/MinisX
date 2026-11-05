package com.minisx.app.sandbox.offload

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.provider.MediaStore
import com.minisx.app.offload.MinisNotificationListenerService
import com.minisx.app.offload.OffloadPermissionManager
import com.minisx.app.sandbox.NativeOffloadHandler
import com.minisx.app.sandbox.NativeOffloadRequest
import com.minisx.app.sandbox.NativeOffloadResult
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/**
 * android-media — control whatever music/video app is playing, and set volume.
 *
 * Android counterpart of iOS `apple-media` (MediaOffload.m): `now-playing`,
 * `play`, `pause`, `toggle`, `next`, `prev`, `volume`, `search`, `play-search`.
 *
 * iOS drives the Music library. Android has no shared library, so this talks to
 * the active media *sessions* of other apps (Spotify, YouTube Music, a podcast
 * player …). Listing those sessions needs Notification Access, which the
 * `android-notification list` command already asks for; the same prompt is used
 * here. `search` / `play-search` hand the query to the user's default music app
 * (the system `PLAY_FROM_SEARCH` intent), because there is no catalogue to
 * search ourselves.
 */
class MediaOffloadHandler(private val context: Context) : NativeOffloadHandler {

    override fun handle(request: NativeOffloadRequest): NativeOffloadResult {
        val args = OffloadArgs(request.argv.drop(1))
        if (args.hasFlag("h", "help")) return NativeOffloadResult(0, HELP)
        val sub = args.positional.firstOrNull()
            ?: return OffloadEnvelope.error(TOOL, "help", "invalid_arguments", "No command specified. Use --help for usage.", args, 2)
        OffloadGate.enforce("media", "Media", args, request)?.let { return it }
        return try {
            when (sub) {
                "volume" -> volume(args)
                "search", "play-search" -> playFromSearch(sub, args)
                "now-playing", "play", "pause", "toggle", "next", "prev", "previous" -> session(sub, args)
                else -> OffloadEnvelope.error(
                    TOOL, sub, "unknown_command",
                    "Unknown command '$sub'. Valid commands: now-playing, play, pause, toggle, next, prev, volume, search, play-search.", args, 2,
                )
            }
        } catch (e: SecurityException) {
            OffloadEnvelope.error(TOOL, sub, "permission_denied", e.message ?: "permission denied", args, 126)
        } catch (e: Throwable) {
            OffloadEnvelope.error(TOOL, sub, "internal", e.message ?: e.javaClass.simpleName, args)
        }
    }

    // ── Sessions ────────────────────────────────────────────────────────────

    private fun session(sub: String, args: OffloadArgs): NativeOffloadResult {
        ensureAccess(sub, args)?.let { return it }
        val mgr = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        val controllers = mgr.getActiveSessions(ComponentName(context, MinisNotificationListenerService::class.java))
        if (controllers.isEmpty()) {
            return OffloadEnvelope.error(
                TOOL, sub, "no_data",
                "Nothing is playing: no app has an active media session. Start playback in a music app, or use `play-search`.", args,
            )
        }
        // Prefer the one that is actually playing, else the most recent.
        val c = controllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: controllers.first()
        val tc = c.transportControls
        when (sub) {
            "play" -> tc.play()
            "pause" -> tc.pause()
            "toggle" -> if (c.playbackState?.state == PlaybackState.STATE_PLAYING) tc.pause() else tc.play()
            "next" -> tc.skipToNext()
            "prev", "previous" -> tc.skipToPrevious()
        }
        if (sub != "now-playing") Thread.sleep(250) // let the app publish its new state
        val data = describe(c)
        if (sub != "now-playing") data.put("command", sub)
        data.put("sessions", JSONArray(controllers.map { it.packageName }))
        return OffloadEnvelope.ok(TOOL, sub, data, args)
    }

    private fun describe(c: MediaController): JSONObject {
        val m = c.metadata
        val st = c.playbackState
        val o = JSONObject()
            .put("app", c.packageName)
            .put("state", when (st?.state) {
                PlaybackState.STATE_PLAYING -> "playing"
                PlaybackState.STATE_PAUSED -> "paused"
                PlaybackState.STATE_STOPPED -> "stopped"
                PlaybackState.STATE_BUFFERING -> "buffering"
                PlaybackState.STATE_NONE, null -> "none"
                else -> "other"
            })
        if (m != null) {
            fun put(key: String, mk: String) = m.getString(mk)?.let { o.put(key, it) }
            put("title", MediaMetadata.METADATA_KEY_TITLE)
            put("artist", MediaMetadata.METADATA_KEY_ARTIST)
            put("album", MediaMetadata.METADATA_KEY_ALBUM)
            val dur = m.getLong(MediaMetadata.METADATA_KEY_DURATION)
            if (dur > 0) o.put("duration_s", dur / 1000.0)
        }
        st?.let { if (it.position >= 0) o.put("position_s", it.position / 1000.0) }
        return o
    }

    private fun ensureAccess(action: String, args: OffloadArgs): NativeOffloadResult? {
        if (MinisNotificationListenerService.isEnabled(context)) return null
        // Same prompt as `android-notification list`: access is granted on a settings page.
        val r = runBlocking {
            OffloadPermissionManager.requestSettingsGate(
                OffloadPermissionManager.SettingsGateRequest(
                    id = "notification_access",
                    title = "Notification access needed",
                    message = "Minis needs Notification access to see and control what is playing. Open Settings and enable \"Minis\" under Notification access.",
                    settingsAction = MinisNotificationListenerService.SETTINGS_ACTION,
                    requiresPackageUri = false,
                    positiveLabel = "Open Settings",
                ),
                check = { MinisNotificationListenerService.isEnabled(context) },
            )
        }
        return when (r) {
            OffloadPermissionManager.AndroidPermissionResult.GRANTED -> null
            OffloadPermissionManager.AndroidPermissionResult.DENIED ->
                OffloadEnvelope.error(TOOL, action, "permission_denied", "The user declined to grant Notification access.", args, 126)
            OffloadPermissionManager.AndroidPermissionResult.TIMEOUT ->
                OffloadEnvelope.error(TOOL, action, "timeout", "Timed out waiting for Notification access to be granted.", args)
        }
    }

    // ── Volume / search ─────────────────────────────────────────────────────

    private fun volume(args: OffloadArgs): NativeOffloadResult {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val raw = args.positional.getOrNull(1) ?: args.get("level")
        if (raw != null) {
            // 0.0–1.0 (as on iOS) or 0–100 / "+10" / "-10" percent.
            val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC).toDouble() / max
            val target = when {
                raw.startsWith("+") || raw.startsWith("-") -> raw.toDoubleOrNull()?.let { cur + it / 100.0 }
                else -> raw.toDoubleOrNull()?.let { if (it <= 1.0) it else it / 100.0 }
            } ?: return OffloadEnvelope.error(TOOL, "volume", "invalid_arguments", "Volume must be 0.0–1.0, 0–100, or +N / -N.", args, 2)
            am.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(target.coerceIn(0.0, 1.0) * max).toInt(), 0)
        }
        val now = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        return OffloadEnvelope.ok(
            TOOL, "volume",
            JSONObject().put("volume", Math.round(now.toDouble() / max * 100) / 100.0).put("level", now).put("max", max)
                .put("muted", now == 0), args,
        )
    }

    private fun playFromSearch(sub: String, args: OffloadArgs): NativeOffloadResult {
        val query = args.get("query") ?: args.positional.drop(1).joinToString(" ").ifBlank { null }
            ?: return OffloadEnvelope.error(TOOL, sub, "invalid_arguments", "Required: --query <text>", args, 2)
        val intent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
            putExtra(android.app.SearchManager.QUERY, query)
            putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (intent.resolveActivity(context.packageManager) == null) {
            return OffloadEnvelope.error(TOOL, sub, "not_available", "No installed app handles music search. Install a music app.", args, 3)
        }
        context.startActivity(intent)
        return OffloadEnvelope.ok(
            TOOL, sub,
            JSONObject().put("query", query).put("handed_to", "default music app")
                .put("note", "Android has no shared music library: the query was passed to the default music app, which decides what plays."),
            args,
        )
    }

    companion object {
        private const val TOOL = "android-media"

        private val HELP = """android-media — control the active media session and the volume (JSON)

USAGE:
  android-media <command> [options]

COMMANDS:
  now-playing     What is playing (app, title, artist, album, position)
  play | pause | toggle   Control the active media session
  next | prev     Skip track
  volume [level]  Show or set the media volume: 0.0–1.0, 0–100, or +N / -N percent
  search <text>   Ask the default music app to search/play (also: play-search)

NOTES:
  Session control needs Notification Access (the same prompt as `android-notification list`).
  Android has no shared music library, so `search` / `play-search` hand the query to the user's
  default music app.
  --compact, -q   Single-line JSON / data field only

EXAMPLES:
  android-media now-playing
  android-media toggle
  android-media volume 0.4
  android-media volume +10
  android-media play-search --query "jazz piano"
"""
    }
}
