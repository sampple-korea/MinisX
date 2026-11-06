package com.minisx.app.speech

import org.json.JSONObject

/**
 * Spoken announcement for a tool call — "스크립트 실행 중: 의존성 설치" /
 * "Executing script: install deps".
 *
 * Port of iOS `AIChatViewModel.makeToolSpeech`
 * (Agent/Chat/AIChatViewModel+SSEStream.swift), including its phrasing, its
 * 40-character clip and its per-tool argument fallbacks — a listener who
 * switches platforms should hear the same sentence.
 *
 * Deliberately NOT the on-screen preview text ("Executing…", "Reading /x…").
 * Those are terse because the screen shows the arguments next to them; spoken
 * aloud with no visual context they say almost nothing, so this builds a
 * natural cue instead.
 *
 * Pure and framework-free so it is unit-testable — the announcement is the part
 * that has to stay aligned across platforms, and it should not need a device to
 * verify.
 */
object ToolSpeech {

    /** Longest detail we will speak; beyond this the tail becomes "…". */
    private const val DETAIL_MAX = 40

    private val HANGUL = Regex("\\p{IsHangul}")

    /**
     * Build the announcement for a tool call.
     *
     * @param name tool name as the model called it (`shell_execute`, `file_read`…)
     * @param argsJson raw JSON arguments; malformed or partial JSON is tolerated
     *   and simply yields no argument fallback.
     * @param title the model's own `tool_title` summary, when it supplied one.
     */
    fun announcement(name: String, argsJson: String?, title: String?): String {
        val args = parseArgs(argsJson)
        // Language follows the model's own summary, matching iOS: the title is
        // written in the user's language (the tool schema asks for it), so it
        // is a better signal than the device locale — a Korean user talking to
        // an English-answering model should hear one language, not a mix.
        val ko = title?.let { HANGUL.containsMatchIn(it) } ?: false
        val detail = clip(title)

        return when (name) {
            "shell_execute" -> {
                val d = detail ?: clip(args?.optString("command")) ?: ""
                if (ko) "스크립트 실행 중: $d" else "Executing script: $d"
            }
            "file_read" -> {
                val d = detail ?: fileName(args?.optString("path"), ko)
                if (ko) "파일 읽는 중: $d" else "Reading file: $d"
            }
            "file_write" -> {
                val d = detail ?: fileName(args?.optString("path"), ko)
                if (ko) "파일 쓰는 중: $d" else "Writing file: $d"
            }
            "file_edit" -> {
                val d = detail ?: fileName(args?.optString("path"), ko)
                if (ko) "파일 편집 중: $d" else "Editing file: $d"
            }
            "apply_patch" -> {
                val d = detail ?: fileName(args?.optString("input")?.let { com.minisx.app.tools.ApplyPatchTool.firstPath(it) }, ko)
                if (ko) "패치 적용 중: $d" else "Applying patch: $d"
            }
            "browser_use" -> {
                val d = detail ?: clip(args?.optString("action")) ?: ""
                if (ko) "브라우저 사용 중: $d" else "Using browser: $d"
            }
            "read_image" -> {
                val d = detail ?: fileName(args?.optString("path"), ko)
                if (ko) "이미지 읽는 중: $d" else "Reading image: $d"
            }
            else -> {
                val d = detail ?: name
                if (ko) "실행 중: $d" else "Running: $d"
            }
        }
    }

    /**
     * Tool args arrive as the raw JSON the model streamed, which can be
     * incomplete when a call is announced mid-stream. A parse failure is not an
     * error here — it just means no argument fallback is available and the
     * tool name carries the announcement.
     */
    private fun parseArgs(argsJson: String?): JSONObject? {
        if (argsJson.isNullOrBlank()) return null
        return runCatching { JSONObject(argsJson) }.getOrNull()
    }

    private fun clip(s: String?): String? {
        val t = s?.trim()?.takeIf { it.isNotEmpty() && it != "null" } ?: return null
        return if (t.length > DETAIL_MAX) t.take(DETAIL_MAX) + "…" else t
    }

    /** Last path component — the full path is noise when spoken. */
    private fun fileName(path: String?, ko: Boolean): String {
        val p = path?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
            ?: return if (ko) "파일" else "a file"
        return p.trimEnd('/').substringAfterLast('/').ifEmpty { p }
    }
}
