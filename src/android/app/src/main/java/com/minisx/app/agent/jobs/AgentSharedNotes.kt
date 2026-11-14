package com.minisx.app.agent.jobs

import com.minisx.app.data.model.AgentToolDefinition
import com.minisx.app.data.model.AgentToolParam

/**
 * [T-android-shared-notes] The scratchpad one conversation shares with the sub agents it delegates
 * to.
 *
 * A sub agent is isolated on purpose: its own context, its own tool loop, no view of the parent's
 * conversation. That isolation is what makes delegation cheap, and also what makes three sibling
 * sub agents pay three times for the same discovery — each one re-reads the same directory, re-opens
 * the same page, re-learns the same build command. The parent knows the findings; the children
 * cannot ask it, because a child runs to completion before the parent's next turn.
 *
 * This is the smallest thing that closes that gap: a keyed board per PARENT session, that the parent
 * and all of its children read and write. A child writes "the API lives in src/api/client.ts, the
 * retry wrapper is in retry.ts"; its sibling reads the board at its first turn instead of searching
 * for it. The parent can seed the board before it delegates, and read what the children learned
 * afterwards.
 *
 * Bounded on purpose. It is a scratchpad, not a database: [MAX_ENTRIES] keys, [MAX_VALUE_CHARS] per
 * value, and a full board REFUSES a new key instead of evicting one — silently dropping a note
 * another agent is about to rely on is worse than telling the model to reuse a key.
 *
 * In memory only, and per parent session: a child's whole life is inside its parent's turn (the
 * registry makes the same call for the same reason), and nothing here is worth surviving a restart.
 * The parent's transcript remains the durable record.
 *
 * Threading: the maps are concurrent and every mutation is taken under the board's own lock, because
 * children run on their own coroutines while the parent may be writing too.
 */
object AgentSharedNotes {

    const val TOOL_NAME = "shared_notes"

    /** Keys one board holds. A fan-out of three rarely needs more than a handful. */
    const val MAX_ENTRIES = 24

    /** Key length ceiling — a key is a short label ("api-paths"), not a sentence. */
    const val MAX_KEY_CHARS = 80

    /** Value ceiling. Long findings belong in a file the notes point at. */
    const val MAX_VALUE_CHARS = 4_000

    /** One note. [author] is "you" / "sub agent <title>" so a reader can weigh it. */
    data class Entry(
        val key: String,
        val value: String,
        val author: String,
        val updatedAtMs: Long,
    )

    private val boards = java.util.concurrent.ConcurrentHashMap<String, LinkedHashMap<String, Entry>>()

    private fun board(boardId: String): LinkedHashMap<String, Entry> =
        boards.getOrPut(boardId) { LinkedHashMap() }

    /**
     * Write or replace [key]. False when the board is full and [key] is new — the caller turns that
     * into a message telling the model to update an existing key instead.
     */
    fun write(boardId: String, key: String, value: String, author: String): Boolean {
        val k = key.trim().take(MAX_KEY_CHARS)
        if (boardId.isBlank() || k.isEmpty()) return false
        val v = value.take(MAX_VALUE_CHARS)
        val b = board(boardId)
        synchronized(b) {
            if (k !in b && b.size >= MAX_ENTRIES) return false
            b[k] = Entry(k, v, author, System.currentTimeMillis())
            return true
        }
    }

    fun read(boardId: String, key: String): Entry? {
        val b = boards[boardId] ?: return null
        synchronized(b) { return b[key.trim().take(MAX_KEY_CHARS)] }
    }

    /** Every note, oldest-written first. */
    fun list(boardId: String): List<Entry> {
        val b = boards[boardId] ?: return emptyList()
        synchronized(b) { return b.values.toList() }
    }

    fun hasEntries(boardId: String): Boolean {
        val b = boards[boardId] ?: return false
        synchronized(b) { return b.isNotEmpty() }
    }

    /**
     * Drop a conversation's board.
     *
     * Deliberately NOT called from the agent loop: a board outlives the turn that filled it, which is
     * the point — the parent's next turn reads what the last fan-out learned. It is for a caller that
     * knows the session itself is gone (deletion, a released view model) so the process does not keep
     * a dead conversation's notes forever.
     */
    fun clear(boardId: String) {
        if (boardId.isBlank()) return
        boards.remove(boardId)
    }

    /** The tool result for `action=list`: the notes, or a line saying the board is empty. */
    fun render(boardId: String): String {
        val entries = list(boardId)
        if (entries.isEmpty()) {
            return "The shared notes board for this conversation is empty. " +
                "Write a note with action=write, key=…, value=… so the sub agents you delegate to do not have to re-find it."
        }
        return buildString {
            append("Shared notes (${entries.size}/$MAX_ENTRIES):")
            for (e in entries) {
                append("\n\n[${e.key}] (${e.author})\n")
                append(e.value)
            }
        }
    }

    /** The schema. Present while delegation is (the board belongs to the agents feature). */
    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = TOOL_NAME,
        description = "A small scratchpad shared by this conversation and every sub agent it " +
            "delegates to. Sub agents cannot see this conversation and cannot talk to each other " +
            "while they run, so this is how a finding reaches them: write what you learned here " +
            "before delegating (paths, ports, commands, decisions), and read here first when a " +
            "sub agent returns. A sub agent writes what it discovers before it finishes, so its " +
            "siblings and you can reuse it instead of finding it again. Short keyed notes only; " +
            "put long output in a file and note the path. write replaces the value for an existing " +
            "key, so update rather than duplicating.",
        parameters = mapOf(
            "tool_title" to AgentToolParam(
                "string",
                "A concise 5-10 word summary of what this call does, shown to the user " +
                    "(e.g. 'Share the API paths with sub agents'). Use the same language as the user.",
            ),
            "action" to AgentToolParam(
                "string",
                "\"list\" (default): every note on the board. \"read\": the one note named by `key`. " +
                    "\"write\": store `value` under `key`, replacing an existing note with that key.",
                enumValues = listOf("list", "read", "write"),
            ),
            "key" to AgentToolParam(
                "string",
                "read/write. A short lowercase label, e.g. `api-paths`, `build-command`, `findings`. " +
                    "Reuse an existing key to correct it instead of adding a near-duplicate.",
            ),
            "value" to AgentToolParam(
                "string",
                "write, required. The note itself, a few lines at most. Name exact paths, commands " +
                    "and identifiers; say what you verified rather than what you assume.",
            ),
        ),
        required = listOf("tool_title"),
        propertyOrdering = listOf("tool_title", "action", "key", "value"),
    )
}
