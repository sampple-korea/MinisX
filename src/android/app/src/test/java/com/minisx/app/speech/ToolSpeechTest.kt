package com.minisx.app.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spoken tool announcements.
 *
 * The strings here are the iOS ones (`makeToolSpeech`), verbatim — a user who
 * moves between platforms should hear the same sentence, so a change that
 * fails these is a divergence, not a style choice.
 */
class ToolSpeechTest {

    @Test
    fun `shell uses the model's title when it supplied one`() {
        assertEquals(
            "Executing script: install deps",
            ToolSpeech.announcement("shell_execute", """{"command":"apk add py3-pip"}""", "install deps"),
        )
    }

    /** No title → fall back to the tool's own most meaningful argument. */
    @Test
    fun `shell falls back to the command`() {
        assertEquals(
            "Executing script: apk add py3-pip",
            ToolSpeech.announcement("shell_execute", """{"command":"apk add py3-pip"}""", null),
        )
    }

    /**
     * Language follows the model's title, not the device locale: the tool
     * schema asks the model to write the title in the user's language, so it is
     * the better signal — and mixing "Executing script:" with a Korean detail
     * is exactly what this avoids.
     */
    @Test
    fun `a korean title switches the whole phrase to korean`() {
        assertEquals(
            "스크립트 실행 중: 의존성 설치",
            ToolSpeech.announcement("shell_execute", """{"command":"apk add py3-pip"}""", "의존성 설치"),
        )
        assertEquals(
            "파일 읽는 중: 설정 파일",
            ToolSpeech.announcement("file_read", """{"path":"/etc/conf"}""", "설정 파일"),
        )
    }

    /** A full path spoken aloud is noise; only the last component is useful. */
    @Test
    fun `file tools speak the file name, not the whole path`() {
        assertEquals(
            "Reading file: notes.md",
            ToolSpeech.announcement("file_read", """{"path":"/var/minis/shared/notes.md"}""", null),
        )
        assertEquals(
            "Writing file: out.txt",
            ToolSpeech.announcement("file_write", """{"path":"/root/out.txt"}""", null),
        )
        assertEquals(
            "Editing file: Main.kt",
            ToolSpeech.announcement("file_edit", """{"path":"/src/Main.kt"}""", null),
        )
    }

    /**
     * The generic noun is the LAST resort — it applies only when there is
     * neither a title nor a path. A title, even a short one, is the detail.
     */
    @Test
    fun `a missing path degrades to a generic noun`() {
        assertEquals("Reading file: a file", ToolSpeech.announcement("file_read", "{}", null))
        // With a title present the title wins; the generic noun never appears.
        assertEquals("파일 읽는 중: 읽기", ToolSpeech.announcement("file_read", "{}", "읽기"))
    }

    /**
     * Any non-blank title becomes the detail, including punctuation-only ones —
     * and Korean phrasing is selected by that same title. The two conditions
     * are therefore coupled: a title cannot be Hangul-bearing (selecting Korean)
     * and blank (falling through to the generic noun) at once.
     *
     * Consequence worth stating: the Korean generic noun is unreachable, here
     * and in the iOS original this was ported from. It is kept only so the two
     * implementations stay literally comparable.
     */
    @Test
    fun `any non-blank title becomes the detail and drives the language`() {
        // CJK punctuation is not a Hangul character, so phrasing stays English —
        // but the title is still non-blank, so it is still the detail.
        assertEquals("Reading file: 。", ToolSpeech.announcement("file_read", "{}", "。"))
        // A single Hangul character selects Korean and doubles as the detail.
        assertEquals("파일 읽는 중: 읽", ToolSpeech.announcement("file_read", "{}", "읽"))
    }

    @Test
    fun `browser and image tools use their own phrasing`() {
        assertEquals(
            "Using browser: click login",
            ToolSpeech.announcement("browser_use", """{"action":"click login"}""", null),
        )
        assertEquals(
            "Reading image: shot.png",
            ToolSpeech.announcement("read_image", """{"path":"/tmp/shot.png"}""", null),
        )
    }

    /** An unknown tool still announces something rather than going silent. */
    @Test
    fun `an unrecognised tool falls back to its name`() {
        assertEquals("Running: some_new_tool", ToolSpeech.announcement("some_new_tool", "{}", null))
        assertEquals("Running: fetch the weather",
            ToolSpeech.announcement("some_new_tool", "{}", "fetch the weather"))
    }

    /** 40 chars, matching iOS — a long command must not become a monologue. */
    @Test
    fun `an over-long detail is clipped with an ellipsis`() {
        val long = "a".repeat(100)
        val out = ToolSpeech.announcement("shell_execute", """{"command":"$long"}""", null)
        assertTrue("should end with an ellipsis: $out", out.endsWith("…"))
        assertEquals("Executing script: " + "a".repeat(40) + "…", out)
    }

    /**
     * Args are the raw JSON the model streamed and can still be partial when a
     * call is announced. That must degrade to "no fallback available", never
     * throw — a crash here would take down the whole streaming collector.
     */
    @Test
    fun `malformed or partial json does not throw`() {
        assertEquals(
            "Executing script: ",
            ToolSpeech.announcement("shell_execute", """{"command":"apk add py""", null),
        )
        assertEquals("Reading file: a file", ToolSpeech.announcement("file_read", "not json", null))
        assertEquals("Executing script: ", ToolSpeech.announcement("shell_execute", null, null))
    }

    /** A blank title must not be treated as a usable detail. */
    @Test
    fun `blank and null-literal titles fall through to the argument`() {
        assertEquals(
            "Executing script: ls -la",
            ToolSpeech.announcement("shell_execute", """{"command":"ls -la"}""", "   "),
        )
        // org.json turns an absent key into the string "null"; that must not be spoken.
        assertEquals("Reading file: a file", ToolSpeech.announcement("file_read", """{"other":1}""", null))
    }

    @Test
    fun `a trailing slash does not produce an empty file name`() {
        assertEquals(
            "Reading file: dir",
            ToolSpeech.announcement("file_read", """{"path":"/var/dir/"}""", null),
        )
    }
}
