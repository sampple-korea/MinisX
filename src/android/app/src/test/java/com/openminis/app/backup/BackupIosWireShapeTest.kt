package com.openminis.app.backup

import com.openminis.app.ProductionSources
import com.openminis.app.data.db.ChatSessionEntity
import com.openminis.app.data.db.FolderEntity
import java.io.File
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An Android backup restored on an iPhone must decode there.
 *
 * iOS reads backup records with synthesized `Codable`, which rejects a record
 * missing a required key and silently drops that line (`try?`). Android used
 * to write sessions flat while iOS requires `{"session": {…}, …}`, so every
 * session — and with it every message and all group membership — vanished
 * from an Android -> iOS restore that still reported success.
 *
 * These tests check the REAL exporter output against the Swift declarations
 * (pinned below), so an exporter change that breaks iOS-restorability fails here.
 */
class BackupIosWireShapeTest {

    // -- Swift declarations -------------------------------------------------

    private data class SwiftProp(val name: String, val optional: Boolean)

    /**
     * The stored properties of the iOS declarations this wire shape must satisfy,
     * pinned from the iOS sources (`ChatSession`, `ChatFolder`, and the importer's
     * `SessionRecord`, src/ios at the point the iOS tree was removed). A package
     * the Android exporter writes has to decode with these, field for field.
     */
    private val iosSession = listOf(
        SwiftProp("id", false), SwiftProp("title", true), SwiftProp("category", true), SwiftProp("modelId", false),
        SwiftProp("createdAt", false), SwiftProp("updatedAt", false), SwiftProp("lastMessage", true),
        SwiftProp("source", true), SwiftProp("lastSyncedAt", true), SwiftProp("remoteDeviceId", true),
        SwiftProp("remoteDeviceName", true), SwiftProp("pinnedAt", true), SwiftProp("folderId", true),
        SwiftProp("parentSessionId", true), SwiftProp("parentToolUseId", true),
    )
    private val iosFolder = listOf(
        SwiftProp("id", false), SwiftProp("name", false), SwiftProp("icon", true), SwiftProp("color", true),
        SwiftProp("origin", false), SwiftProp("sortIndex", false), SwiftProp("pinnedAt", true),
        SwiftProp("desc", true), SwiftProp("createdAt", false), SwiftProp("updatedAt", false),
    )
    private val iosSessionRecord = listOf(
        SwiftProp("session", false), SwiftProp("memoryEnabled", false), SwiftProp("modelBinding", true),
    )

    // -- Fixtures -------------------------------------------------------------

    private val session = ChatSessionEntity(
        id = "S-1",
        title = "Trip plan",
        modelId = "claude-sonnet-5",
        createdAt = 1_758_000_000_000,
        updatedAt = 1_758_000_060_000,
        category = "travel",
        lastMessage = "done",
        modelBinding = "group:default",
        source = "share",
        memoryEnabled = 0,
        pinnedAt = 1_758_000_030_000,
        editCount = 3,
        thinkingOverride = "high",
        folderId = "F-1",
        parentSessionId = null,
        parentToolUseId = null,
    )

    private val folder = FolderEntity(
        id = "F-1",
        name = "Work",
        icon = "briefcase",
        color = "blue",
        origin = FolderEntity.ORIGIN_AI,
        sortIndex = 2,
        pinnedAt = 1_758_000_010_000,
        description = "Things for the office",
        createdAt = 1_757_000_000_000,
        updatedAt = 1_757_000_500_000,
    )

    private val record: JsonObject get() = BackupExporter.sessionRecord(session).jsonObject
    private val folderRec: JsonObject get() = BackupExporter.folderRecord(folder).jsonObject

    // -- Session: iOS shape -------------------------------------------------

    @Test
    fun `the Swift declarations were found`() {
        assertTrue(iosSession.any { it.name == "folderId" })
        assertEquals(listOf("session", "memoryEnabled", "modelBinding"), iosSessionRecord.map { it.name })
        assertTrue(iosFolder.any { it.name == "desc" })
    }

    @Test
    fun `a session is nested under session, as iOS's SessionRecord requires`() {
        assertNotNull("iOS decodes rec.session — the key is required", record["session"] as? JsonObject)
        assertNull("nothing of the ChatSession proper may sit at the top", record["id"])
        for (p in iosSessionRecord.filter { !it.optional }) {
            assertTrue("iOS SessionRecord requires '${p.name}' at the top level", record.containsKey(p.name))
        }
        assertEquals(false, record["memoryEnabled"]!!.jsonPrimitive.booleanOrNull)
        assertEquals("group:default", record["modelBinding"]!!.jsonPrimitive.content)
    }

    @Test
    fun `the inner object carries every required ChatSession field, non-null`() {
        val inner = record["session"]!!.jsonObject
        for (p in iosSession.filter { !it.optional }) {
            val v = inner[p.name]
            assertTrue("iOS ChatSession requires '${p.name}'", v != null && v !is JsonNull)
        }
    }

    @Test
    fun `the inner object only uses ChatSession's own names`() {
        val known = iosSession.map { it.name }.toSet()
        val strays = record["session"]!!.jsonObject.keys - known
        assertTrue("keys iOS's ChatSession does not declare: $strays", strays.isEmpty())
    }

    @Test
    fun `group membership travels inside the session`() {
        assertEquals("F-1", record["session"]!!.jsonObject["folderId"]!!.jsonPrimitive.content)
    }

    @Test
    fun `dates are whole-second ISO-8601, which Swift's iso8601 strategy parses`() {
        val iso = Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z""")
        val inner = record["session"]!!.jsonObject
        for (k in listOf("createdAt", "updatedAt", "pinnedAt")) {
            assertTrue(k, iso.matches(inner[k]!!.jsonPrimitive.content))
        }
        for (k in listOf("createdAt", "updatedAt", "pinnedAt")) {
            assertTrue("folder $k", iso.matches(folderRec[k]!!.jsonPrimitive.content))
        }
    }

    @Test
    fun `Android-only columns stay in the package, outside ChatSession`() {
        assertEquals(3, record["editCount"]!!.jsonPrimitive.content.toInt())
        assertEquals("high", record["thinkingOverride"]!!.jsonPrimitive.content)
    }

    // -- Folder -----------------------------------------------------------------

    @Test
    fun `a folder carries every required ChatFolder field, and desc for iOS`() {
        for (p in iosFolder.filter { !it.optional }) {
            val v = folderRec[p.name]
            assertTrue("iOS ChatFolder requires '${p.name}'", v != null && v !is JsonNull)
        }
        assertEquals("Things for the office", folderRec["desc"]!!.jsonPrimitive.content)
        assertEquals(
            "older Android importers read 'description'",
            "Things for the office", folderRec["description"]!!.jsonPrimitive.content,
        )
    }

    // -- Android -> Android: new AND old packages ---------------------------

    /** The pre-change flat shape, as packages made by 1.13 and earlier hold it. */
    private fun legacyFlat(): JsonObject {
        val r = record
        return JsonObject(r["session"]!!.jsonObject + r.filterKeys { it != "session" })
    }

    @Test
    fun `the Android importer restores the same session from the new and the old shape`() {
        fun decode(rec: JsonObject) =
            (BackupRecordMapper.session(rec, existing = null) as BackupRecordMapper.Decoded.Apply).entity
        assertEquals(session, decode(record))
        assertEquals(session, decode(legacyFlat()))
    }

    @Test
    fun `a folder written by the new exporter reads back with its description on Android`() {
        val back = (BackupRecordMapper.folder(folderRec, existing = null) as BackupRecordMapper.Decoded.Apply).entity
        assertEquals(folder, back)
    }
}
