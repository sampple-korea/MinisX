package com.minisx.app.live

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.util.Base64
import com.minisx.app.logging.AppLogger
import com.minisx.app.network.NetworkHttp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * One spoken conversation with the OpenAI Realtime API.
 *
 * Android counterpart of Mobile Codex's "Codex Live": you talk, it talks back,
 * you can interrupt it, and spoken requests for real work are handed to the
 * Minis agent (the `run_task` function) whose answer the voice then reads out.
 *
 * The transport is the Realtime WebSocket with PCM16 @ 24 kHz in both
 * directions: a microphone thread (voice-communication source, with the
 * platform echo canceller and noise suppressor when present) feeds
 * `input_audio_buffer.append`, and the model's audio deltas are played through a
 * voice-communication [AudioTrack]. When you start speaking over a reply the
 * playback is flushed at once and the server is told how much was actually heard.
 *
 * The session owns no foreground service or notification; [LiveCall] and
 * [LiveVoiceService] provide those.
 */
class LiveSession(
    private val config: Config,
    /** Runs one delegated request through the Minis agent and returns its final answer. */
    private val runTask: suspend (String) -> String,
) {
    data class Config(
        val apiKey: String,
        val baseUrl: String?,
        val model: String = RealtimeProtocol.DEFAULT_MODEL,
        val voice: String = RealtimeProtocol.DEFAULT_VOICE,
        /** Language name for the model's instructions ("Korean"), or null for auto. */
        val language: String? = null,
        val userAgent: String? = null,
    )

    enum class Phase { CONNECTING, CONNECTED, ENDED }

    data class State(
        val phase: Phase = Phase.CONNECTING,
        val muted: Boolean = false,
        /** 0..1, smoothed by the UI. */
        val inputLevel: Float = 0f,
        val outputLevel: Float = 0f,
        val userCaption: String = "",
        val assistantCaption: String = "",
        /** A delegated request is running in the agent. */
        val working: Boolean = false,
        val error: String? = null,
        val startedAt: Long = 0L,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var ws: WebSocket? = null
    @Volatile private var ended = false
    @Volatile private var muted = false
    @Volatile private var ready = false
    @Volatile private var inLevel = 0f
    @Volatile private var outLevel = 0f

    private var record: AudioRecord? = null
    private var track: AudioTrack? = null
    private var micThread: Thread? = null
    private var playThread: Thread? = null
    private val playQueue = LinkedBlockingQueue<ByteArray>()
    private var echo: AcousticEchoCanceler? = null
    private var noise: NoiseSuppressor? = null

    // The reply currently being played: for conversation.item.truncate on barge-in.
    private var playingItem: String? = null
    private var itemStartHead = 0
    private var itemBytes = 0L

    private val client: OkHttpClient by lazy {
        val base = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(20, TimeUnit.SECONDS).connectTimeout(15, TimeUnit.SECONDS).build()
        NetworkHttp.client(NetworkHttp.DEFAULT_TAG, base)
    }

    // ── Lifecycle ───────────────────────────────────────────────────────────

    /** Connects and starts the audio. Call once, with the microphone permission granted. */
    fun start() {
        _state.update { it.copy(startedAt = System.currentTimeMillis()) }
        val url = RealtimeProtocol.websocketUrl(config.baseUrl, config.model)
        val req = Request.Builder().url(url)
            .header("Authorization", "Bearer ${config.apiKey}")
            .apply { config.userAgent?.takeIf { it.isNotBlank() }?.let { header("User-Agent", it) } }
            .build()
        ws = client.newWebSocket(req, Listener())
        scope.launch {
            delay(CONNECT_TIMEOUT_MS)
            if (!ready && !ended) fail("The voice service did not answer. Check the connection and try again.")
        }
        scope.launch {
            // ~10 Hz level ticker for the UI orb.
            while (isActive && !ended) {
                _state.update { it.copy(inputLevel = if (muted) 0f else inLevel, outputLevel = outLevel) }
                outLevel *= 0.6f
                delay(100)
            }
        }
    }

    fun setMuted(value: Boolean) {
        muted = value
        if (value) inLevel = 0f
        _state.update { it.copy(muted = value) }
    }

    /** Ends the call and releases the audio. Idempotent. */
    fun end(error: String? = null) {
        if (ended) return
        ended = true
        ready = false
        runCatching { ws?.close(1000, "bye") }
        ws = null
        runCatching { micThread?.interrupt() }
        runCatching { playThread?.interrupt() }
        playQueue.clear()
        runCatching { record?.stop() }
        runCatching { record?.release() }
        runCatching { echo?.release() }
        runCatching { noise?.release() }
        runCatching { track?.pause(); track?.flush(); track?.release() }
        record = null; track = null
        _state.update { it.copy(phase = Phase.ENDED, error = error ?: it.error, working = false, inputLevel = 0f, outputLevel = 0f) }
        scope.cancel()
    }

    private fun fail(message: String) {
        AppLogger.warning(TAG, "live failed: $message")
        end(message)
    }

    // ── WebSocket ───────────────────────────────────────────────────────────

    private inner class Listener : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            if (ended) return
            when (val e = RealtimeProtocol.parse(text)) {
                RealtimeProtocol.Event.SessionReady -> if (!ready) onReady(webSocket)
                RealtimeProtocol.Event.SpeechStarted -> bargeIn(webSocket)
                is RealtimeProtocol.Event.AudioDelta -> onAudio(e)
                is RealtimeProtocol.Event.AssistantTranscriptDelta ->
                    _state.update { it.copy(assistantCaption = (it.assistantCaption + e.text).takeLast(CAPTION_MAX)) }
                is RealtimeProtocol.Event.AssistantTranscriptDone ->
                    if (e.text.isNotBlank()) _state.update { it.copy(assistantCaption = e.text.takeLast(CAPTION_MAX)) }
                is RealtimeProtocol.Event.UserTranscriptDone ->
                    _state.update { it.copy(userCaption = e.text.trim().takeLast(CAPTION_MAX), assistantCaption = "") }
                is RealtimeProtocol.Event.FunctionCall -> onFunctionCall(webSocket, e)
                is RealtimeProtocol.Event.Error -> {
                    AppLogger.warning(TAG, "server error: ${e.message}")
                    // Most server errors are about one event (e.g. truncating an item that is gone): keep going.
                    if (!ready) fail(e.message)
                }
                RealtimeProtocol.Event.SpeechStopped, RealtimeProtocol.Event.ResponseDone, is RealtimeProtocol.Event.Ignored -> Unit
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (ended) return
            val code = response?.code
            fail(
                when (code) {
                    401, 403 -> "The voice service rejected the API key (HTTP $code)."
                    404 -> "This provider has no Realtime voice endpoint (HTTP 404)."
                    null -> "Could not reach the voice service: ${t.message ?: t.javaClass.simpleName}"
                    else -> "The voice service refused the connection (HTTP $code)."
                },
            )
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (!ended) end(if (code == 1000) null else "The voice connection closed ($code ${reason.take(80)}).")
        }
    }

    private fun onReady(webSocket: WebSocket) {
        ready = true
        webSocket.send(RealtimeProtocol.sessionUpdate(RealtimeProtocol.defaultInstructions(config.language), config.voice, null))
        try {
            startAudio(webSocket)
        } catch (e: SecurityException) {
            fail("Microphone permission is missing.")
            return
        } catch (e: Throwable) {
            fail("Could not start the audio: ${e.message ?: e.javaClass.simpleName}")
            return
        }
        _state.update { it.copy(phase = Phase.CONNECTED) }
    }

    // ── Function calls (delegated work) ─────────────────────────────────────

    private fun onFunctionCall(webSocket: WebSocket, call: RealtimeProtocol.Event.FunctionCall) {
        if (call.name != RealtimeProtocol.TOOL_RUN_TASK) {
            RealtimeProtocol.functionOutput(call.callId, "Unknown function ${call.name}.").forEach { webSocket.send(it) }
            return
        }
        val request = RealtimeProtocol.taskRequest(call.arguments)
        if (request == null) {
            RealtimeProtocol.functionOutput(call.callId, "No request was given.").forEach { webSocket.send(it) }
            return
        }
        _state.update { it.copy(working = true) }
        scope.launch {
            val result = try {
                runTask(request)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                "The task failed: ${e.message ?: e.javaClass.simpleName}"
            }
            _state.update { it.copy(working = false) }
            if (!ended) {
                RealtimeProtocol.functionOutput(call.callId, result.take(MAX_TASK_OUTPUT)).forEach { ws?.send(it) }
            }
        }
    }

    // ── Audio in ────────────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private fun startAudio(webSocket: WebSocket) {
        val chunkBytes = SAMPLE_RATE * 2 * CHUNK_MS / 1000
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, max(minBuf, chunkBytes * 4),
        )
        check(rec.state == AudioRecord.STATE_INITIALIZED) { "microphone unavailable" }
        if (AcousticEchoCanceler.isAvailable()) echo = AcousticEchoCanceler.create(rec.audioSessionId)?.also { it.enabled = true }
        if (NoiseSuppressor.isAvailable()) noise = NoiseSuppressor.create(rec.audioSessionId)?.also { it.enabled = true }
        record = rec

        val outMin = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val trk = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
            )
            .setAudioFormat(
                AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(),
            )
            .setBufferSizeInBytes(max(outMin, SAMPLE_RATE * 2 / 2))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        trk.play()
        track = trk

        rec.startRecording()
        micThread = Thread({
            val buf = ByteArray(chunkBytes)
            while (!ended && !Thread.currentThread().isInterrupted) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) { if (n < 0) break else continue }
                inLevel = max(inLevel * 0.8f, level(buf, n))
                if (muted || webSocket.queueSize() > MAX_QUEUED_BYTES) continue
                webSocket.send(RealtimeProtocol.audioAppend(Base64.encodeToString(buf, 0, n, Base64.NO_WRAP)))
            }
        }, "live-mic").apply { isDaemon = true; start() }

        playThread = Thread({
            while (!ended && !Thread.currentThread().isInterrupted) {
                val chunk = try { playQueue.take() } catch (_: InterruptedException) { break }
                runCatching { trk.write(chunk, 0, chunk.size) }
            }
        }, "live-play").apply { isDaemon = true; start() }
    }

    // ── Audio out ───────────────────────────────────────────────────────────

    private fun onAudio(e: RealtimeProtocol.Event.AudioDelta) {
        val pcm = runCatching { Base64.decode(e.base64, Base64.DEFAULT) }.getOrNull() ?: return
        if (pcm.isEmpty()) return
        val trk = track ?: return
        synchronized(this) {
            if (e.itemId != null && e.itemId != playingItem) {
                playingItem = e.itemId
                itemStartHead = trk.playbackHeadPosition
                itemBytes = 0
            }
            itemBytes += pcm.size
        }
        outLevel = max(outLevel, level(pcm, pcm.size))
        playQueue.offer(pcm)
    }

    /** The user started talking: cut the reply off and tell the server how much was heard. */
    private fun bargeIn(webSocket: WebSocket) {
        val trk = track ?: return
        val item: String?
        val playedMs: Long
        synchronized(this) {
            item = playingItem
            val playedFrames = (trk.playbackHeadPosition - itemStartHead).coerceAtLeast(0)
            val sentFrames = itemBytes / 2
            playedMs = min(playedFrames.toLong(), sentFrames) * 1000L / SAMPLE_RATE
            playingItem = null
            itemBytes = 0
        }
        playQueue.clear()
        runCatching { trk.pause(); trk.flush(); trk.play() }
        outLevel = 0f
        if (item != null && playedMs > 0) webSocket.send(RealtimeProtocol.truncate(item, playedMs))
    }

    /** RMS of PCM16 little-endian samples, 0..1 (lightly boosted: speech sits low). */
    private fun level(buf: ByteArray, n: Int): Float {
        var sum = 0.0
        var count = 0
        var i = 0
        while (i + 1 < n) {
            val s = (buf[i].toInt() and 0xFF) or (buf[i + 1].toInt() shl 8)
            val v = s.toShort().toInt()
            sum += v.toDouble() * v
            count++
            i += 2
        }
        if (count == 0) return 0f
        return min(1f, (sqrt(sum / count) / 32768.0 * 4.0).toFloat())
    }

    companion object {
        private const val TAG = "LiveSession"
        private const val SAMPLE_RATE = RealtimeProtocol.SAMPLE_RATE
        private const val CHUNK_MS = 40
        private const val CONNECT_TIMEOUT_MS = 20_000L
        private const val CAPTION_MAX = 1_600
        private const val MAX_TASK_OUTPUT = 6_000
        private const val MAX_QUEUED_BYTES = 512 * 1024L
    }
}
