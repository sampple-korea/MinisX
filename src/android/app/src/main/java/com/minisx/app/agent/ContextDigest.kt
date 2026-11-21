package com.minisx.app.agent

import com.minisx.app.data.model.AgentContentPart
import com.minisx.app.data.model.LLMMessage

/**
 * [T-android-compact-digest] What compaction falls back to when no model can write the summary.
 *
 * Compaction is the app's only way to make a long run fit again, and it is built on a model call:
 * the older turns are sent to a model that returns a few paragraphs. That call can fail in ways that
 * have nothing to do with the conversation — the model is rate-limited, the account is out of quota,
 * the provider is down, the summary stream stalls past its idle timer — and when it does, the turn
 * stops with "compaction failed" and the context stays full. The user's next message then hits the
 * same wall, so a transient provider problem strands the conversation.
 *
 * This is the deterministic answer to that: a digest built from the history itself, no model, no
 * network. It is worse than a summary — it cannot weigh what mattered — so it is only ever the
 * fallback, and it says so in its own first line, so the model reading it later knows it is looking
 * at a structured extract rather than someone's judgement of the task.
 *
 * What it keeps, chosen for what a long agent run actually needs afterwards:
 *  - the user's request(s), clipped but verbatim — the goal is the one thing a summary must never
 *    paraphrase away;
 *  - each tool call with its name and arguments (commands, paths, queries);
 *  - the lines of each result that look like an error, and the file paths it mentions;
 *  - the assistant's own text, clipped;
 *  - a consolidated "files touched" list.
 *
 * Bounded by [maxChars] from the NEWEST step backwards, like the advisor's transcript: the most
 * recent work is what the next turn builds on. The goal block is emitted first and kept whole.
 *
 * Pure (no Android types beyond the message model): the tests run on the JVM.
 */
object ContextDigest {

    const val MAX_CHARS = 8_000

    private const val GOAL_CLIP = 900
    private const val ASSISTANT_CLIP = 240
    private const val ARGS_CLIP = 220
    private const val RESULT_CLIP = 260
    private const val ERROR_CLIP = 200
    private const val MAX_ERRORS_PER_RESULT = 3
    private const val MAX_PATHS = 30
    private const val MAX_PATH_CHARS = 160

    const val HEADER: String =
        "[Automatic digest of the earlier conversation — no model summary was available, so this is " +
            "a structured extract. It keeps your request, the commands run, the files touched and " +
            "the errors seen; the newest steps are described most fully.]"

    /**
     * A digest of [messages]. [previousSummary] (an earlier compaction's summary) is carried at the
     * front when present, so compacting twice does not lose what the first pass distilled.
     */
    fun digest(
        messages: List<LLMMessage>,
        previousSummary: String? = null,
        maxChars: Int = MAX_CHARS,
    ): String {
        val goals = ArrayList<String>()
        val steps = ArrayList<String>()
        val paths = LinkedHashSet<String>()
        val errors = LinkedHashSet<String>()

        for (msg in messages) {
            val texts = ArrayList<String>()
            if (msg.content.isNotBlank()) texts.add(msg.content)
            for (part in msg.contentParts) {
                when (part) {
                    is AgentContentPart.Text -> if (part.text.isNotBlank()) texts.add(part.text)
                    is AgentContentPart.ToolUse -> {
                        val args = clip(oneLine(part.input.toString()), ARGS_CLIP)
                        steps.add("- call ${part.name}($args)")
                        paths.addAll(pathsIn(part.input.toString()))
                    }
                    is AgentContentPart.ToolResult -> {
                        val body = part.content
                        val errs = errorLines(body)
                        if (errs.isNotEmpty()) {
                            errors.addAll(errs.map { clip(it, ERROR_CLIP) })
                            steps.add("- result of ${part.name}: ERROR — ${clip(errs.first(), RESULT_CLIP)}")
                        } else {
                            val head = body.trim().lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
                            steps.add("- result of ${part.name}: ${body.trim().length} chars — ${clip(oneLine(head), RESULT_CLIP)}")
                        }
                        paths.addAll(pathsIn(body))
                    }
                    is AgentContentPart.ImageData -> steps.add("- [image ${part.mimeType}]")
                }
            }
            if (texts.isEmpty()) continue
            val text = texts.joinToString("\n").trim()
            if (text.isEmpty()) continue
            if (msg.role == LLMMessage.Role.USER) {
                // Runtime notes are not the user's words; they are already in the transcript's tail.
                if (isRuntimeNote(text)) continue
                goals.add(clip(oneLine(text), GOAL_CLIP))
            } else {
                steps.add("assistant: ${clip(oneLine(text), ASSISTANT_CLIP)}")
            }
        }

        val out = StringBuilder()
        out.append(HEADER).append('\n')
        if (!previousSummary.isNullOrBlank()) {
            out.append("\nEarlier summary:\n").append(clip(previousSummary.trim(), 2_000)).append('\n')
        }
        if (goals.isEmpty()) {
            out.append("\nThe user's request:\n- (not in this range; it was made earlier in the conversation)\n")
        } else {
            out.append("\nThe user's request:\n")
            // The first request is the task; the later ones are the follow-ups that shaped it.
            out.append("- ").append(goals.first()).append('\n')
            if (goals.size > 1) {
                out.append("- later: ").append(goals.last()).append('\n')
            }
        }

        // Steps, newest first, for as long as the remaining budget allows.
        out.append("\nWork done, oldest first:\n")
        val head = out.length
        val room = (maxChars - head - 400).coerceAtLeast(600)
        val kept = ArrayDeque<String>()
        var used = 0
        for (step in steps.asReversed()) {
            if (used + step.length + 1 > room && kept.isNotEmpty()) break
            kept.addFirst(step)
            used += step.length + 1
        }
        val dropped = steps.size - kept.size
        if (dropped > 0) out.append("(").append(dropped).append(" earlier steps left out)\n")
        kept.forEach { out.append(it).append('\n') }

        if (paths.isNotEmpty()) {
            out.append("\nFiles and paths touched:\n")
            paths.take(MAX_PATHS).forEach { out.append("- ").append(it).append('\n') }
        }
        if (errors.isNotEmpty()) {
            out.append("\nErrors seen:\n")
            errors.take(MAX_ERRORS_PER_RESULT * 4).forEach { out.append("- ").append(it).append('\n') }
        }
        return out.toString().trim().take(maxChars + 1_500)
    }

    /** A `<system-reminder>` / `<agent_callback` note, or an injected summary head — not the user. */
    private fun isRuntimeNote(text: String): Boolean {
        val t = text.trimStart()
        return t.startsWith("<system-reminder>") || t.startsWith("<agent_callback") ||
            t.startsWith("<context-summary>")
    }

    /** Lines that read as a failure: the one thing a later turn must not re-learn the hard way. */
    internal fun errorLines(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val hits = ArrayList<String>()
        for (line in text.lineSequence()) {
            val l = line.trim()
            if (l.length < 6) continue
            if (ERROR_WORDS.any { l.contains(it, ignoreCase = true) }) {
                hits.add(oneLine(l))
                if (hits.size >= MAX_ERRORS_PER_RESULT) break
            }
        }
        return hits
    }

    /**
     * Absolute / home / workspace paths in [text]. Deliberately narrow: two or more segments, no
     * spaces, no trailing punctuation — a path a later turn can actually open, not a URL fragment.
     */
    internal fun pathsIn(text: String): List<String> {
        if (text.isEmpty() || text.length > 200_000) return emptyList()
        val hits = LinkedHashSet<String>()
        for (m in PATH.findAll(text)) {
            val p = m.groupValues[1].trimEnd('.', ',', ';', ':', ')', '"', '\'', '`')
            if (p.length in 4..MAX_PATH_CHARS) hits.add(p)
            if (hits.size >= MAX_PATHS) break
        }
        return hits.toList()
    }

    private fun oneLine(text: String): String =
        text.replace(Regex("[\\r\\n\\t]+"), " ").replace(Regex(" {2,}"), " ").trim()

    private fun clip(text: String, max: Int): String =
        if (text.length <= max) text else text.take(max) + "…"

    private val ERROR_WORDS = listOf(
        "error", "failed", "failure", "exception", "traceback", "fatal",
        "not found", "no such file", "permission denied", "denied", "timed out",
        "refused", "cannot", "can't", "unable to",
    )

    private val PATH = Regex("""(?:^|[\s"'`(=:,\[])((?:~|\.{1,2})?(?:/[A-Za-z0-9_.\-]+)+)""")
}
