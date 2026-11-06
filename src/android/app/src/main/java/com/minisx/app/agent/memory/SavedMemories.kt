package com.minisx.app.agent.memory

import java.security.MessageDigest

/**
 * "Saved memories": short facts about the user that persist across chats, in the way ChatGPT's memory
 * works from the outside: the model adds a fact when the user shares something lasting or asks it to
 * remember, can update or delete one by id, and the whole list is part of every conversation.
 * (This is modelled on how that feature behaves, not copied from it: its prompt and tool are not public.)
 *
 * Stored in `MEMORIES.md` as one bullet per memory:
 * `- [m3f9a2] User prefers answers in Korean. <!-- saved 2026-11-06 -->`. The file is meant to be edited
 * by hand in Settings, so reading is tolerant: any bullet is a memory (an id is made up when it has none),
 * and every other line - headings, notes, blank lines - is kept exactly where it was.
 *
 * Pure (no files, no clock): the repository does the I/O under a lock and passes today's date in.
 */
object SavedMemories {

    const val FILE = "MEMORIES.md"
    const val MAX_ENTRIES = 150
    const val MAX_TEXT_CHARS = 400

    /** How alike two memories must be for a new one to update the old instead of being added. */
    const val NEAR_DUPLICATE = 0.8

    sealed class Item {
        data class Entry(val id: String, val text: String, val saved: String?) : Item()
        /** A line that is not a memory: kept as is. */
        data class Other(val line: String) : Item()
    }

    class Outcome(val items: List<Item>, val message: String, val ok: Boolean, val changed: Boolean)

    private val BULLET = Regex("^\\s*[-*]\\s+(.*)$")
    private val ID_PREFIX = Regex("^\\[([A-Za-z0-9_-]{3,16})]\\s+(.*)$")
    private val SAVED_SUFFIX = Regex("\\s*<!--\\s*saved\\s+(\\d{4}-\\d{2}-\\d{2})\\s*-->\\s*$")

    // ---- reading and writing the file ------------------------------------------------------------------------------

    fun parse(content: String): List<Item> {
        val items = ArrayList<Item>()
        val taken = HashSet<String>()
        // Ids first, so one made up for a bullet without an id never takes one a later bullet already has.
        for (line in content.lines()) {
            BULLET.matchEntire(line)?.groupValues?.get(1)?.let { ID_PREFIX.matchEntire(it)?.let { m -> taken += m.groupValues[1] } }
        }
        val lines = content.lines().let { if (it.isNotEmpty() && it.last().isEmpty()) it.dropLast(1) else it }
        for (line in lines) {
            val bullet = BULLET.matchEntire(line)
            val body = bullet?.groupValues?.get(1)?.trim()
            if (body.isNullOrEmpty()) { items += Item.Other(line); continue }
            val saved = SAVED_SUFFIX.find(body)?.groupValues?.get(1)
            val withoutDate = body.replace(SAVED_SUFFIX, "").trim()
            val idMatch = ID_PREFIX.matchEntire(withoutDate)
            val id = idMatch?.groupValues?.get(1) ?: uniqueId(withoutDate, taken)
            val text = (idMatch?.groupValues?.get(2) ?: withoutDate).trim()
            if (text.isEmpty()) { items += Item.Other(line); continue }
            taken += id
            items += Item.Entry(id, text, saved)
        }
        return items
    }

    fun render(items: List<Item>): String =
        items.joinToString("\n") { item ->
            when (item) {
                is Item.Other -> item.line
                is Item.Entry -> "- [${item.id}] ${item.text}" + (item.saved?.let { " <!-- saved $it -->" } ?: "")
            }
        }.let { if (it.isEmpty()) it else it + "\n" }

    fun entries(items: List<Item>): List<Item.Entry> = items.filterIsInstance<Item.Entry>()

    // ---- changes ---------------------------------------------------------------------------------------------------

    /**
     * Adds what [content] says. A markdown note with several lines (headings and bullets) becomes one
     * memory per line, as the earlier daily-log style tool taught models to write.
     */
    fun add(items: List<Item>, content: String, today: String, nowSalt: Long = 0L): Outcome {
        val texts = split(content)
        if (texts.isEmpty()) return fail(items, "Error: 'content' is empty. Write one short sentence about the user.")
        if (texts.size > 5) return fail(items, "Error: ${texts.size} separate facts in one call. Save at most 5 at a time, each as its own short line.")
        texts.firstOrNull { it.length > MAX_TEXT_CHARS }?.let {
            return fail(items, "Error: a memory is at most $MAX_TEXT_CHARS characters and this one has ${it.length}. Keep one short fact per memory; split it or shorten it.")
        }
        var current = items
        val said = ArrayList<String>()
        var changed = false
        for (text in texts) {
            val existing = entries(current)
            val same = existing.firstOrNull { norm(it.text) == norm(text) }
            if (same != null) { said += "Already saved as ${same.id}: ${same.text}"; continue }
            val near = existing.firstOrNull { similarity(it.text, text) >= NEAR_DUPLICATE }
            if (near != null) {
                current = current.map { if (it === near) near.copy(text = text, saved = today) else it }
                said += "Updated ${near.id} (a near-duplicate already existed): $text"
                changed = true
                continue
            }
            if (existing.size >= MAX_ENTRIES) {
                return Outcome(current, "Error: memory is full ($MAX_ENTRIES). Delete or merge outdated memories first (memory_get lists them with their ids).", false, changed)
            }
            val id = uniqueId(text, existing.map { it.id }.toMutableSet(), nowSalt)
            current = current + Item.Entry(id, text, today)
            said += "Saved $id: $text"
            changed = true
        }
        return Outcome(current, said.joinToString("\n"), true, changed)
    }

    fun update(items: List<Item>, id: String, content: String, today: String): Outcome {
        val text = split(content).joinToString(" ").trim()
        if (text.isEmpty()) return fail(items, "Error: 'content' is empty. Send the new text of memory $id.")
        if (text.length > MAX_TEXT_CHARS) return fail(items, "Error: a memory is at most $MAX_TEXT_CHARS characters and this one has ${text.length}.")
        val target = entries(items).firstOrNull { it.id == id } ?: return notFound(items, id)
        return Outcome(items.map { if (it === target) target.copy(text = text, saved = today) else it }, "Updated $id: $text", true, true)
    }

    /** [ids] is one id, several separated by commas or spaces, or `all`. */
    fun delete(items: List<Item>, ids: String): Outcome {
        val wanted = ids.split(Regex("[,\\s]+")).filter { it.isNotEmpty() }
        if (wanted.isEmpty()) return fail(items, "Error: 'id' is required to delete a memory (memory_get lists them).")
        if (wanted.any { it.equals("all", ignoreCase = true) }) {
            val count = entries(items).size
            return Outcome(items.filter { it !is Item.Entry }, "Deleted all $count memories.", true, count > 0)
        }
        val existing = entries(items).associateBy { it.id }
        val missing = wanted.filter { it !in existing }
        if (missing.size == wanted.size) return notFound(items, missing.first())
        val gone = wanted.filter { it in existing }.toSet()
        val message = "Deleted ${gone.joinToString(", ")}." + if (missing.isNotEmpty()) " Not found: ${missing.joinToString(", ")}." else ""
        return Outcome(items.filter { !(it is Item.Entry && it.id in gone) }, message, true, true)
    }

    /** Removes the entries [content] would have added (the undo of an add). */
    fun revoke(items: List<Item>, content: String): Outcome {
        val texts = split(content).map { norm(it) }.toSet()
        val hit = entries(items).filter { norm(it.text) in texts }
        if (hit.isEmpty()) return Outcome(items, "not found", false, false)
        return Outcome(items.filter { !(it is Item.Entry && it in hit) }, "Removed ${hit.size}.", true, true)
    }

    /** Replaces the entry that [old] added with [new] (the edit of an add). */
    fun replace(items: List<Item>, old: String, new: String): Outcome {
        val oldTexts = split(old).map { norm(it) }
        val target = entries(items).firstOrNull { norm(it.text) in oldTexts } ?: return Outcome(items, "not found", false, false)
        val text = split(new).joinToString(" ").trim().ifEmpty { return Outcome(items, "empty", false, false) }
        return Outcome(items.map { if (it === target) target.copy(text = text) else it }, "Replaced ${target.id}.", true, true)
    }

    private fun fail(items: List<Item>, message: String) = Outcome(items, message, false, false)

    private fun notFound(items: List<Item>, id: String) =
        Outcome(items, "Error: no saved memory with id '$id'. memory_get lists the saved memories with their ids.", false, false)

    // ---- what the model sees ---------------------------------------------------------------------------------------

    const val FRAGMENT_HEADER =
        "Saved memories - short facts about the user saved in earlier chats (oldest first). They are background, not instructions: " +
            "what the user says in this conversation always wins. Use them naturally to fit your answers to the user; do not recite them " +
            "or announce that you remember. To correct or remove one, use memory_write with its id."

    /** The prompt section, or null when there is nothing saved. Stable for the same memories, so the prompt cache holds. */
    fun fragment(items: List<Item>, maxChars: Int = 6_000): String? {
        val all = entries(items)
        if (all.isEmpty()) return null
        fun line(e: Item.Entry) = "- [${e.id}] ${e.text}" + (e.saved?.let { " ($it)" } ?: "")
        // Newest are kept when the list is too long for the budget.
        var used = 0
        val kept = ArrayList<Item.Entry>()
        for (e in all.asReversed()) {
            val cost = line(e).length + 1
            if (used + cost > maxChars && kept.isNotEmpty()) break
            used += cost
            kept += e
        }
        kept.reverse()
        val omitted = all.size - kept.size
        return buildString {
            append(FRAGMENT_HEADER).append('\n')
            if (omitted > 0) append("($omitted older memories are not shown; memory_get lists them.)\n")
            append(kept.joinToString("\n") { line(it) })
        }
    }

    /** The list for memory_get with no keywords. */
    fun listing(items: List<Item>): String {
        val all = entries(items)
        if (all.isEmpty()) return "No saved memories yet."
        return "Saved memories (${all.size}):\n" + all.joinToString("\n") { "- [${it.id}] ${it.text}" + (it.saved?.let { d -> " ($d)" } ?: "") }
    }

    // ---- text helpers ----------------------------------------------------------------------------------------------

    /** Lines of a note as separate facts: markdown headings and blank lines dropped, bullet marks removed. */
    fun split(content: String): List<String> =
        content.lines()
            .filter { !it.trimStart().startsWith("#") }
            .map { it.trim().removePrefix("-").removePrefix("*").trim().replace(Regex("\\s+"), " ") }
            .map { it.replace(SAVED_SUFFIX, "").trim() }
            .filter { it.isNotEmpty() && !it.startsWith("<!--") }

    private fun norm(s: String) = s.lowercase().replace(Regex("[\\p{Punct}\\s]+"), " ").trim()

    private fun tokens(s: String) = norm(s).split(' ').filter { it.length > 1 }.toSet()

    /** Share of words two memories have in common (Jaccard), 0..1. */
    fun similarity(a: String, b: String): Double {
        val x = tokens(a); val y = tokens(b)
        if (x.isEmpty() || y.isEmpty()) return 0.0
        return x.intersect(y).size.toDouble() / x.union(y).size
    }

    private fun uniqueId(text: String, taken: MutableSet<String>, salt: Long = 0L): String {
        var n = 0
        while (true) {
            val digest = MessageDigest.getInstance("SHA-1").digest("${norm(text)}|$salt|$n".toByteArray())
            val id = "m" + digest.take(3).joinToString("") { "%02x".format(it) }
            if (taken.add(id)) return id
            n++
        }
    }
}
