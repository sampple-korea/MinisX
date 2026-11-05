package com.minisx.app.live

import org.json.JSONArray
import org.json.JSONObject

/**
 * The OpenAI Realtime API over WebSocket: the client events Live sends and a
 * parser for the server events it reacts to. No Android types, so JVM tests
 * drive it directly.
 *
 * Both event vocabularies are accepted: the GA names (`response.output_audio.delta`)
 * and the earlier beta ones (`response.audio.delta`), because OpenAI-compatible
 * relays still answer with either.
 */
object RealtimeProtocol {
    const val SAMPLE_RATE = 24_000
    const val DEFAULT_MODEL = "gpt-realtime"
    const val DEFAULT_VOICE = "marin"

    /** Voices offered in the Live panel; the first is the default. */
    val VOICES = listOf("marin", "cedar", "alloy", "ash", "ballad", "coral", "echo", "sage", "shimmer", "verse")

    /** The one function the voice model can call to have the Minis agent do real work. */
    const val TOOL_RUN_TASK = "run_task"

    /** `wss://…/v1/realtime?model=…` from an instance's base URL (null = api.openai.com). */
    fun websocketUrl(baseUrl: String?, model: String): String {
        var base = (baseUrl?.trim()?.takeIf { it.isNotEmpty() } ?: "https://api.openai.com").trimEnd('/')
        base = when {
            base.startsWith("https://") -> "wss://" + base.removePrefix("https://")
            base.startsWith("http://") -> "ws://" + base.removePrefix("http://")
            base.startsWith("wss://") || base.startsWith("ws://") -> base
            else -> "wss://$base"
        }
        if (!base.endsWith("/v1")) base += "/v1"
        return "$base/realtime?model=" + java.net.URLEncoder.encode(model, "UTF-8")
    }

    // ── Client events ───────────────────────────────────────────────────────

    fun sessionUpdate(instructions: String, voice: String, language: String?): String {
        val tool = JSONObject()
            .put("type", "function")
            .put("name", TOOL_RUN_TASK)
            .put(
                "description",
                "Have the MinisX agent do real work on this phone: run shell commands, read or edit files, browse the " +
                    "web, use device tools, check or change settings. Pass one clear, self-contained request. " +
                    "Returns the agent's final answer, which you then summarise out loud in a sentence or two.",
            )
            .put(
                "parameters",
                JSONObject().put("type", "object")
                    .put("properties", JSONObject().put("request", JSONObject().put("type", "string")
                        .put("description", "What to do, in the user's words, with any details it needs.")))
                    .put("required", JSONArray().put("request")),
            )
        val input = JSONObject()
            .put("format", JSONObject().put("type", "audio/pcm").put("rate", SAMPLE_RATE))
            .put(
                "turn_detection",
                JSONObject().put("type", "server_vad").put("threshold", 0.5).put("prefix_padding_ms", 300)
                    .put("silence_duration_ms", 600).put("create_response", true).put("interrupt_response", true),
            )
            .put("noise_reduction", JSONObject().put("type", "near_field"))
            .put(
                "transcription",
                JSONObject().put("model", "gpt-4o-mini-transcribe").apply { language?.let { put("language", it) } },
            )
        val output = JSONObject()
            .put("format", JSONObject().put("type", "audio/pcm").put("rate", SAMPLE_RATE))
            .put("voice", voice)
        val session = JSONObject()
            .put("type", "realtime")
            .put("output_modalities", JSONArray().put("audio"))
            .put("instructions", instructions)
            .put("audio", JSONObject().put("input", input).put("output", output))
            .put("tools", JSONArray().put(tool))
            .put("tool_choice", "auto")
        return JSONObject().put("type", "session.update").put("session", session).toString()
    }

    fun audioAppend(base64Pcm: String): String =
        JSONObject().put("type", "input_audio_buffer.append").put("audio", base64Pcm).toString()

    /** Hand a function's result back and ask the model to speak about it. */
    fun functionOutput(callId: String, output: String): List<String> = listOf(
        JSONObject().put("type", "conversation.item.create").put(
            "item",
            JSONObject().put("type", "function_call_output").put("call_id", callId).put("output", output),
        ).toString(),
        JSONObject().put("type", "response.create").toString(),
    )

    /** Tell the server how much of an interrupted reply was actually heard. */
    fun truncate(itemId: String, audioEndMs: Long): String =
        JSONObject().put("type", "conversation.item.truncate").put("item_id", itemId)
            .put("content_index", 0).put("audio_end_ms", audioEndMs.coerceAtLeast(0)).toString()

    // ── Server events ───────────────────────────────────────────────────────

    sealed class Event {
        data object SessionReady : Event()
        data object SpeechStarted : Event()
        data object SpeechStopped : Event()
        /** A chunk of the model's spoken reply: raw base64 PCM16 @ 24 kHz. */
        data class AudioDelta(val itemId: String?, val base64: String) : Event()
        data class AssistantTranscriptDelta(val text: String) : Event()
        data class AssistantTranscriptDone(val text: String) : Event()
        data class UserTranscriptDone(val text: String) : Event()
        data class FunctionCall(val callId: String, val name: String, val arguments: String) : Event()
        data object ResponseDone : Event()
        data class Error(val message: String) : Event()
        data class Ignored(val type: String) : Event()
    }

    fun parse(text: String): Event {
        val o = runCatching { JSONObject(text) }.getOrNull() ?: return Event.Ignored("malformed")
        return when (val type = o.optString("type")) {
            "session.created", "session.updated" -> Event.SessionReady
            "input_audio_buffer.speech_started" -> Event.SpeechStarted
            "input_audio_buffer.speech_stopped" -> Event.SpeechStopped
            "response.output_audio.delta", "response.audio.delta" ->
                Event.AudioDelta(o.optString("item_id").ifEmpty { null }, o.optString("delta"))
            "response.output_audio_transcript.delta", "response.audio_transcript.delta" ->
                Event.AssistantTranscriptDelta(o.optString("delta"))
            "response.output_audio_transcript.done", "response.audio_transcript.done" ->
                Event.AssistantTranscriptDone(o.optString("transcript"))
            "conversation.item.input_audio_transcription.completed" -> Event.UserTranscriptDone(o.optString("transcript"))
            "response.function_call_arguments.done" ->
                Event.FunctionCall(o.optString("call_id"), o.optString("name"), o.optString("arguments"))
            "response.done" -> Event.ResponseDone
            "error" -> Event.Error(o.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() } ?: o.optString("message", "error"))
            else -> Event.Ignored(type)
        }
    }

    /** `{"request":"…"}` → the request, or null when the arguments carry none. */
    fun taskRequest(arguments: String): String? =
        runCatching { JSONObject(arguments).optString("request") }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }

    fun defaultInstructions(language: String?): String = buildString {
        append("You are the voice of MinisX, an AI agent that lives on the user's Android phone and can really do things there. ")
        append("Speak briefly and naturally: one or two sentences, no lists, no markdown. ")
        append("Answer simple questions and chat yourself. Whenever the user wants something done on the phone or in the ")
        append("sandbox (files, shell, web, calendar, health data, settings, anything that needs a tool), call ")
        append(TOOL_RUN_TASK).append(" with a clear request, then tell the user the outcome in your own words. ")
        append("Say a short acknowledgement first (for example \"On it\") before a call that may take a while. ")
        append("Never read out keys, passwords or long output; offer to put it in the chat instead.")
        language?.let { append(" Reply in ").append(it).append(" unless the user switches language.") }
    }
}
