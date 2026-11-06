package com.minisx.app.tools

import com.minisx.app.data.repository.MemoryRepository
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

class MemoryStoreTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun repo() = MemoryRepository(File(tmp.root, "memory"))
    private fun write(r: MemoryRepository, vararg kv: Pair<String, Any>) =
        MemoryTools.executeMemoryWrite(JSONObject(mapOf("tool_title" to "t") + kv).toString(), r)

    @Test fun `add, list, update and delete through the tool`() {
        val r = repo()
        val added = write(r, "content" to "User prefers answers in Korean.")
        assertTrue(added.output, added.success && added.output.startsWith("Saved m"))
        val id = r.savedMemories().single().id
        assertTrue(MemoryTools.executeMemoryGet("""{"tool_title":"t"}""", r).output.contains("[$id] User prefers answers in Korean."))
        assertTrue(write(r, "action" to "update", "id" to id, "content" to "User prefers English.").success)
        assertEquals("User prefers English.", r.savedMemories().single().text)
        assertTrue(write(r, "action" to "delete", "id" to id).success)
        assertTrue(r.savedMemories().isEmpty())
    }

    @Test fun `bad calls say what to send`() {
        val r = repo()
        assertTrue(write(r, "content" to " ").output.contains("'content' is required"))
        assertTrue(write(r, "action" to "update", "content" to "x").output.contains("'id' is required"))
        assertTrue(write(r, "action" to "delete", "id" to "mnope00").output.contains("no saved memory with id"))
        assertTrue(write(r, "action" to "explode").output.contains("add, update or delete"))
    }

    @Test fun `the saved memories reach the prompt, and nothing is injected when there are none`() {
        val r = repo()
        assertEquals(null, r.loadSavedMemoriesFragment())
        write(r, "content" to "User is learning Rust.")
        val f = r.loadSavedMemoriesFragment()!!
        assertTrue(f.startsWith("Saved memories") && f.contains("User is learning Rust."))
    }

    @Test fun `search finds a saved memory by keyword, daily scope leaves them out`() {
        val r = repo()
        write(r, "content" to "User is learning Rust.")
        assertTrue(r.getMemory("rust", "all").contains("User is learning Rust."))
        assertFalse(r.getMemory("rust", "daily").contains("User is learning Rust."))
    }

    @Test fun `undo and edit of an add reach the saved list`() {
        val r = repo()
        write(r, "content" to "User likes jazz.")
        assertTrue(r.replaceEntryBody("User likes jazz.", "User likes blues.") is MemoryRepository.EntryMutationResult.Success)
        assertEquals("User likes blues.", r.savedMemories().single().text)
        assertTrue(r.revokeEntry("User likes blues.") is MemoryRepository.EntryMutationResult.Success)
        assertTrue(r.savedMemories().isEmpty())
        assertTrue(r.revokeEntry("nothing like it") is MemoryRepository.EntryMutationResult.NotFound)
    }

    @Test fun `the user's own edits to the file survive a save`() {
        val dir = File(tmp.root, "memory").apply { mkdirs() }
        File(dir, "MEMORIES.md").writeText("# Mine\n\nA note\n- [mabc123] User likes tea. <!-- saved 2026-01-01 -->\n- written by hand\n")
        val r = MemoryRepository(dir)
        write(r, "content" to "User has a cat.")
        val text = File(dir, "MEMORIES.md").readText()
        assertTrue(text.startsWith("# Mine\n\nA note\n- [mabc123] User likes tea."))
        assertTrue(text.contains("] written by hand"))
        assertTrue(text.contains("] User has a cat."))
        assertFalse(File(dir, "MEMORIES.md.tmp").exists())
    }

    @Test fun `many writers at once lose nothing`() {
        val r = repo()
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val n = 40
        val done = CountDownLatch(n)
        repeat(n) { i ->
            pool.execute {
                start.await()
                write(r, "content" to "fact $i about topic${i}x and thing${i}y and other${i}z")
                done.countDown()
            }
        }
        start.countDown()
        done.await()
        pool.shutdown()
        assertEquals(n, r.savedMemories().size)
        assertEquals(n, r.savedMemories().map { it.id }.toSet().size)
    }
}
