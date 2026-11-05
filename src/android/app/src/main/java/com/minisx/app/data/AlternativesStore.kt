package com.minisx.app.data

import com.minisx.app.data.db.MessageEntity
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * The earlier answers to the LAST user message of a chat, kept when the user retries it, so they can
 * flip between them (‹ 1/3 ›) like rikkahub's message branches. Only the last user turn is branched: an
 * older turn has later turns hanging off it.
 *
 * A group is a list of slots, one per answer. The answer that is live sits in the database, not in
 * its slot ([Group.live] names it); every other slot holds that answer's message rows (everything
 * after the user message). Kept in a small JSON file per chat, so it needs no schema change.
 */
class AlternativesStore(private val root: File) {

    class Group(var live: Int, val slots: MutableList<List<MessageEntity>?>) {
        val count: Int get() = slots.size
    }

    private fun file(sessionId: String) = File(root, "$sessionId.json")

    /** (position of the live answer, number of answers), or null when there is nothing to flip between. */
    fun info(sessionId: String, anchorId: String): Pair<Int, Int>? {
        val g = load(sessionId)[anchorId] ?: return null
        return if (g.count < 2) null else (g.live + 1) to g.count
    }

    /**
     * The user retried [anchorId]: [liveRows] (the answer being thrown away) go into its slot and a new
     * empty slot becomes the live one. At most [MAX_SLOTS] answers are kept; the oldest non-live go first.
     */
    @Synchronized
    fun pushLive(sessionId: String, anchorId: String, liveRows: List<MessageEntity>) {
        if (liveRows.isEmpty()) return
        val all = load(sessionId).toMutableMap()
        val g = all[anchorId] ?: Group(0, mutableListOf<List<MessageEntity>?>(null))
        g.slots[g.live] = liveRows
        g.slots.add(null)
        g.live = g.slots.size - 1
        while (g.slots.size > MAX_SLOTS) {
            val drop = g.slots.indices.first { it != g.live }
            g.slots.removeAt(drop)
            if (drop < g.live) g.live--
        }
        all[anchorId] = g
        save(sessionId, all)
    }

    /**
     * Switch to answer [target]: [liveRows] are stored in the slot being left, the rows of [target] are
     * returned for the caller to put back in the database. Null when [target] is not a stored answer.
     */
    @Synchronized
    fun swap(sessionId: String, anchorId: String, target: Int, liveRows: List<MessageEntity>): List<MessageEntity>? {
        val all = load(sessionId).toMutableMap()
        val g = all[anchorId] ?: return null
        if (target !in g.slots.indices || target == g.live) return null
        val rows = g.slots[target] ?: return null
        g.slots[g.live] = liveRows
        g.slots[target] = null
        g.live = target
        all[anchorId] = g
        save(sessionId, all)
        return rows
    }

    @Synchronized
    fun discard(sessionId: String) {
        file(sessionId).delete()
    }

    fun load(sessionId: String): Map<String, Group> {
        val f = file(sessionId)
        if (!f.isFile) return emptyMap()
        return runCatching {
            val obj = JSONObject(f.readText())
            obj.keys().asSequence().associateWith { key ->
                val g = obj.getJSONObject(key)
                val slots = g.getJSONArray("slots")
                Group(
                    g.getInt("live"),
                    MutableList(slots.length()) { i ->
                        if (slots.isNull(i)) null else slots.getJSONArray(i).let { rows -> List(rows.length()) { r -> fromJson(rows.getJSONObject(r)) } }
                    },
                )
            }
        }.getOrDefault(emptyMap())
    }

    private fun save(sessionId: String, groups: Map<String, Group>) {
        root.mkdirs()
        val obj = JSONObject()
        for ((anchor, g) in groups) {
            val slots = JSONArray()
            for (s in g.slots) slots.put(s?.let { rows -> JSONArray().also { a -> rows.forEach { a.put(toJson(it)) } } } ?: JSONObject.NULL)
            obj.put(anchor, JSONObject().put("live", g.live).put("slots", slots))
        }
        val tmp = File(root, "$sessionId.json.tmp")
        tmp.writeText(obj.toString())
        tmp.renameTo(file(sessionId))
    }

    companion object {
        const val MAX_SLOTS = 6

        internal fun toJson(m: MessageEntity): JSONObject = JSONObject()
            .put("id", m.id).put("sessionId", m.sessionId).put("role", m.role).put("partsJson", m.partsJson)
            .put("createdAt", m.createdAt).put("tokenUsage", m.tokenUsage ?: JSONObject.NULL).put("sortOrder", m.sortOrder)
            .put("reasoningContent", m.reasoningContent ?: JSONObject.NULL)
            .put("streamInterruptCount", m.streamInterruptCount)
            .put("updatedAt", m.updatedAt ?: JSONObject.NULL).put("errorInfo", m.errorInfo ?: JSONObject.NULL)
            .put("modelId", m.modelId ?: JSONObject.NULL).put("modelDisplayName", m.modelDisplayName ?: JSONObject.NULL)
            .put("providerType", m.providerType ?: JSONObject.NULL)
            .put("providerInstanceId", m.providerInstanceId ?: JSONObject.NULL)

        internal fun fromJson(o: JSONObject): MessageEntity {
            fun str(k: String) = if (o.isNull(k)) null else o.getString(k)
            return MessageEntity(
                id = o.getString("id"), sessionId = o.getString("sessionId"), role = o.getString("role"),
                partsJson = o.getString("partsJson"), createdAt = o.getLong("createdAt"),
                tokenUsage = str("tokenUsage"), sortOrder = o.getInt("sortOrder"),
                reasoningContent = str("reasoningContent"), streamInterruptCount = o.optInt("streamInterruptCount", 0),
                updatedAt = if (o.isNull("updatedAt")) null else o.getLong("updatedAt"), errorInfo = str("errorInfo"),
                modelId = str("modelId"), modelDisplayName = str("modelDisplayName"),
                providerType = str("providerType"), providerInstanceId = str("providerInstanceId"),
            )
        }
    }
}
