package com.openminis.app.live

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RealtimeProtocolTest {

    @Test fun `websocket url from the default and from custom bases`() {
        assertEquals("wss://api.openai.com/v1/realtime?model=gpt-realtime", RealtimeProtocol.websocketUrl(null, "gpt-realtime"))
        assertEquals("wss://api.openai.com/v1/realtime?model=gpt-realtime", RealtimeProtocol.websocketUrl("  ", "gpt-realtime"))
        assertEquals("wss://relay.example.com/v1/realtime?model=m", RealtimeProtocol.websocketUrl("https://relay.example.com/", "m"))
        assertEquals("wss://relay.example.com/v1/realtime?model=m", RealtimeProtocol.websocketUrl("https://relay.example.com/v1", "m"))
        assertEquals("ws://10.0.0.2:8080/v1/realtime?model=m", RealtimeProtocol.websocketUrl("http://10.0.0.2:8080", "m"))
        assertEquals("wss://h/v1/realtime?model=a%2Fb", RealtimeProtocol.websocketUrl("wss://h", "a/b"))
    }

    @Test fun `session update carries the voice, pcm rate, vad and the run_task tool`() {
        val o = JSONObject(RealtimeProtocol.sessionUpdate("be brief", "cedar", "ko"))
        assertEquals("session.update", o.getString("type"))
        val s = o.getJSONObject("session")
        assertEquals("realtime", s.getString("type"))
        assertEquals("be brief", s.getString("instructions"))
        val audio = s.getJSONObject("audio")
        assertEquals("cedar", audio.getJSONObject("output").getString("voice"))
        assertEquals(24_000, audio.getJSONObject("input").getJSONObject("format").getInt("rate"))
        assertEquals("audio/pcm", audio.getJSONObject("output").getJSONObject("format").getString("type"))
        val vad = audio.getJSONObject("input").getJSONObject("turn_detection")
        assertEquals("server_vad", vad.getString("type"))
        assertTrue(vad.getBoolean("interrupt_response"))
        assertEquals("ko", audio.getJSONObject("input").getJSONObject("transcription").getString("language"))
        val tool = s.getJSONArray("tools").getJSONObject(0)
        assertEquals("function", tool.getString("type"))
        assertEquals(RealtimeProtocol.TOOL_RUN_TASK, tool.getString("name"))
        assertEquals("request", tool.getJSONObject("parameters").getJSONArray("required").getString(0))
    }

    @Test fun `no transcription language when none is given`() {
        val input = JSONObject(RealtimeProtocol.sessionUpdate("x", "marin", null))
            .getJSONObject("session").getJSONObject("audio").getJSONObject("input")
        assertTrue(!input.getJSONObject("transcription").has("language"))
    }

    @Test fun `audio append and truncate shapes`() {
        assertEquals("input_audio_buffer.append", JSONObject(RealtimeProtocol.audioAppend("AAA=")).getString("type"))
        val t = JSONObject(RealtimeProtocol.truncate("item_1", 1234))
        assertEquals("conversation.item.truncate", t.getString("type"))
        assertEquals("item_1", t.getString("item_id"))
        assertEquals(1234, t.getInt("audio_end_ms"))
        assertEquals(0, JSONObject(RealtimeProtocol.truncate("i", -5)).getInt("audio_end_ms"))
    }

    @Test fun `function output creates the item then asks for a response`() {
        val msgs = RealtimeProtocol.functionOutput("call_9", "done").map { JSONObject(it) }
        assertEquals(2, msgs.size)
        assertEquals("conversation.item.create", msgs[0].getString("type"))
        val item = msgs[0].getJSONObject("item")
        assertEquals("function_call_output", item.getString("type"))
        assertEquals("call_9", item.getString("call_id"))
        assertEquals("done", item.getString("output"))
        assertEquals("response.create", msgs[1].getString("type"))
    }

    @Test fun `server events parse in the GA and the beta vocabulary`() {
        assertEquals(RealtimeProtocol.Event.SessionReady, RealtimeProtocol.parse("""{"type":"session.created"}"""))
        assertEquals(RealtimeProtocol.Event.SessionReady, RealtimeProtocol.parse("""{"type":"session.updated"}"""))
        assertEquals(RealtimeProtocol.Event.SpeechStarted, RealtimeProtocol.parse("""{"type":"input_audio_buffer.speech_started"}"""))
        val ga = RealtimeProtocol.parse("""{"type":"response.output_audio.delta","item_id":"it","delta":"QUJD"}""")
        val beta = RealtimeProtocol.parse("""{"type":"response.audio.delta","item_id":"it","delta":"QUJD"}""")
        assertEquals(RealtimeProtocol.Event.AudioDelta("it", "QUJD"), ga)
        assertEquals(ga, beta)
        assertEquals(
            RealtimeProtocol.Event.AssistantTranscriptDelta("hi"),
            RealtimeProtocol.parse("""{"type":"response.audio_transcript.delta","delta":"hi"}"""),
        )
        assertEquals(
            RealtimeProtocol.Event.AssistantTranscriptDone("hello there"),
            RealtimeProtocol.parse("""{"type":"response.output_audio_transcript.done","transcript":"hello there"}"""),
        )
        assertEquals(
            RealtimeProtocol.Event.UserTranscriptDone("안녕"),
            RealtimeProtocol.parse("""{"type":"conversation.item.input_audio_transcription.completed","transcript":"안녕"}"""),
        )
        assertEquals(RealtimeProtocol.Event.ResponseDone, RealtimeProtocol.parse("""{"type":"response.done"}"""))
    }

    @Test fun `a function call carries its id, name and arguments`() {
        val e = RealtimeProtocol.parse(
            """{"type":"response.function_call_arguments.done","call_id":"c1","name":"run_task","arguments":"{\"request\":\"list files\"}"}""",
        ) as RealtimeProtocol.Event.FunctionCall
        assertEquals("c1", e.callId)
        assertEquals("run_task", e.name)
        assertEquals("list files", RealtimeProtocol.taskRequest(e.arguments))
    }

    @Test fun `task request is null when empty or malformed`() {
        assertNull(RealtimeProtocol.taskRequest("{}"))
        assertNull(RealtimeProtocol.taskRequest("""{"request":"   "}"""))
        assertNull(RealtimeProtocol.taskRequest("not json"))
    }

    @Test fun `errors expose the message and unknown or broken events are ignored`() {
        assertEquals(
            RealtimeProtocol.Event.Error("bad key"),
            RealtimeProtocol.parse("""{"type":"error","error":{"message":"bad key","code":"x"}}"""),
        )
        assertTrue(RealtimeProtocol.parse("""{"type":"rate_limits.updated"}""") is RealtimeProtocol.Event.Ignored)
        assertTrue(RealtimeProtocol.parse("not json") is RealtimeProtocol.Event.Ignored)
    }

    @Test fun `instructions name the tool and the language`() {
        val text = RealtimeProtocol.defaultInstructions("Korean")
        assertTrue(text.contains(RealtimeProtocol.TOOL_RUN_TASK))
        assertTrue(text.contains("Korean"))
        assertTrue(!RealtimeProtocol.defaultInstructions(null).contains("Reply in"))
    }
}
