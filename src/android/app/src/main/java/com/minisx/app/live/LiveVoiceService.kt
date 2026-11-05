package com.minisx.app.live

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.minisx.app.MainActivity
import com.minisx.app.R
import com.minisx.app.logging.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Keeps a Live call alive off-screen: foreground service with the microphone
 * and media-playback types, an ongoing-call notification (a real call style on
 * Android 12+, with an End action), audio focus, the communication audio route
 * and a wake lock. Ending the call from anywhere releases all of it.
 */
class LiveVoiceService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var session: LiveSession? = null
    private var focus: AudioFocusRequest? = null
    private var route: LiveAudioRoute? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { LiveCall.end(); if (session == null) stopSelf(startId) }
            ACTION_START -> begin(startId)
            else -> if (session == null) stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    private fun begin(startId: Int) {
        val s = LiveCall.current.value
        if (s == null || session != null) { if (session == null) stopSelf(startId); return }
        session = s
        try {
            val types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            startForeground(NOTIFICATION_ID, notification(), types)
        } catch (e: Throwable) {
            // Typically: the app is not visible, so Android refuses a microphone service.
            AppLogger.warning(TAG, "startForeground refused: ${e.message}")
            s.end("Android did not allow the voice call to start in the background.")
            release(s)
            return
        }
        val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
            )
            .setOnAudioFocusChangeListener { change -> if (change == AudioManager.AUDIOFOCUS_LOSS) LiveCall.end() }
            .build()
        if (audio.requestAudioFocus(focus!!) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            s.end("Another app is using the audio right now.")
            release(s)
            return
        }
        route = LiveAudioRoute(this).also { it.begin() }
        @Suppress("WakelockTimeout") // Released when the call ends or the service is destroyed.
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Minis:LiveVoice").also { it.acquire() }

        s.start()
        scope.launch {
            // Wait for the call to end, from any cause, then tear everything down.
            val ended = s.state.first { it.phase == LiveSession.Phase.ENDED }
            release(s, ended.error)
        }
    }

    private fun release(s: LiveSession, error: String? = null) {
        route?.close(); route = null
        focus?.let { (getSystemService(Context.AUDIO_SERVICE) as AudioManager).abandonAudioFocusRequest(it) }; focus = null
        wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null
        session = null
        LiveCall.finished(s, error)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.live_channel_name), NotificationManager.IMPORTANCE_LOW),
        )
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, LiveVoiceService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification_live)
            .setContentTitle(getString(R.string.live_notification_title))
            .setContentText(getString(R.string.live_notification_text))
            .setContentIntent(open)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_CALL)
            .setOnlyAlertOnce(true)
        if (Build.VERSION.SDK_INT >= 31) {
            builder.setStyle(
                Notification.CallStyle.forOngoingCall(
                    Person.Builder().setName(getString(R.string.live_notification_title)).setImportant(true).build(), stop,
                ),
            ).setUsesChronometer(true)
        } else {
            builder.addAction(Notification.Action.Builder(null, getString(R.string.live_end), stop).build())
        }
        return builder.build()
    }

    override fun onTaskRemoved(rootIntent: Intent?) { LiveCall.end(); super.onTaskRemoved(rootIntent) }

    override fun onDestroy() {
        LiveCall.end()
        session?.let { release(it) }
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "LiveVoiceService"
        const val ACTION_START = "com.minisx.app.live.START"
        const val ACTION_STOP = "com.minisx.app.live.STOP"
        private const val CHANNEL = "live_voice"
        private const val NOTIFICATION_ID = 7421
    }
}
