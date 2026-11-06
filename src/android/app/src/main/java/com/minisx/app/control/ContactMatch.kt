package com.minisx.app.control

/**
 * Picks the person behind "call Mom" from the address book, without guessing between two people.
 * Pure, so the ranking is tested on the JVM.
 */
object ContactMatch {

    data class Contact(val name: String, val number: String)

    sealed class Pick {
        data class One(val contact: Contact) : Pick()
        /** More than one candidate: the caller shows them and asks which (or passes `--pick N`). */
        data class Many(val candidates: List<Contact>) : Pick()
        object None : Pick()
    }

    /** Digits and a leading plus only, so `010-1234-5678` and `01012345678` are the same number. */
    fun normalizeNumber(raw: String): String {
        val t = raw.trim()
        val plus = t.startsWith("+")
        val digits = t.filter { it.isDigit() }
        return if (plus) "+$digits" else digits
    }

    /** The same line written locally or with a country code (010… / +82 10…) shares its last nine digits. */
    internal fun numberKey(raw: String): String {
        val digits = raw.filter { it.isDigit() }
        return if (digits.length > 9) digits.takeLast(9) else digits
    }

    private fun norm(s: String) = s.lowercase().replace(Regex("\\s+"), "")

    /** 0 exact, 1 starts with, 2 contains, 3 no match. */
    private fun tier(query: String, name: String): Int {
        val q = norm(query)
        val n = norm(name)
        return when {
            q.isEmpty() -> 3
            n == q -> 0
            n.startsWith(q) -> 1
            n.contains(q) -> 2
            else -> 3
        }
    }

    /** The best-matching tier of [all], each number once; [pick] (1-based) chooses among several. */
    fun choose(query: String, all: List<Contact>, pick: Int? = null): Pick {
        val ranked = all.map { it to tier(query, it.name) }.filter { it.second < 3 }
        if (ranked.isEmpty()) return Pick.None
        val best = ranked.minOf { it.second }
        val seen = HashSet<String>()
        val candidates = ranked.filter { it.second == best }.map { it.first }
            .filter { seen.add(numberKey(it.number)) }
        if (pick != null) return candidates.getOrNull(pick - 1)?.let { Pick.One(it) } ?: Pick.Many(candidates)
        return if (candidates.size == 1) Pick.One(candidates[0]) else Pick.Many(candidates)
    }
}
