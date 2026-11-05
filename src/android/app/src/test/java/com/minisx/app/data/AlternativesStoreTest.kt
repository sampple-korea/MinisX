package com.minisx.app.data

import com.minisx.app.data.db.MessageEntity
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AlternativesStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun row(id: String, order: Int, text: String) = MessageEntity(
        id = id, sessionId = "s", role = "assistant", partsJson = """[{"type":"text","text":"$text"}]""",
        createdAt = 1_000L + order, sortOrder = order, modelId = "m", tokenUsage = null,
    )

    private fun store() = AlternativesStore(File(tmp.root, "alt"))

    @Test fun `nothing to flip between before the first retry`() {
        assertNull(store().info("s", "u1"))
    }

    @Test fun `a retry keeps the old answer and makes a new slot live`() {
        val st = store()
        st.pushLive("s", "u1", listOf(row("a1", 1, "first")))
        assertEquals(2 to 2, st.info("s", "u1"))
        st.pushLive("s", "u1", listOf(row("a2", 1, "second")))
        assertEquals(3 to 3, st.info("s", "u1"))
    }

    @Test fun `swapping brings back an old answer and stores the live one`() {
        val st = store()
        st.pushLive("s", "u1", listOf(row("a1", 1, "first")))
        // The live answer (now "second") is in the database; flip back to the first.
        val back = st.swap("s", "u1", 0, listOf(row("a2", 1, "second")))!!
        assertEquals("a1", back.single().id)
        assertEquals(1 to 2, st.info("s", "u1"))
        // And forward again: the second answer comes back intact.
        val fwd = st.swap("s", "u1", 1, back)!!
        assertEquals("a2", fwd.single().id)
        assertEquals(2 to 2, st.info("s", "u1"))
    }

    @Test fun `the live slot and unknown slots cannot be swapped to`() {
        val st = store()
        st.pushLive("s", "u1", listOf(row("a1", 1, "first")))
        assertNull(st.swap("s", "u1", 1, emptyList()))
        assertNull(st.swap("s", "u1", 5, emptyList()))
        assertNull(st.swap("s", "nope", 0, emptyList()))
    }

    @Test fun `only the newest answers are kept and the live one is never dropped`() {
        val st = store()
        for (i in 1..10) st.pushLive("s", "u1", listOf(row("a$i", 1, "t$i")))
        val (pos, count) = st.info("s", "u1")!!
        assertEquals(AlternativesStore.MAX_SLOTS, count)
        assertEquals(count, pos)
    }

    @Test fun `rows survive the file round trip with every column`() {
        val full = MessageEntity(
            id = "x", sessionId = "s", role = "assistant", partsJson = "[]", createdAt = 5L, tokenUsage = "{\"i\":1}",
            sortOrder = 3, reasoningContent = "think", streamInterruptCount = 2, updatedAt = 9L, errorInfo = "e",
            modelId = "m", modelDisplayName = "M", providerType = "openAI", providerInstanceId = "p",
        )
        val st = store()
        st.pushLive("s", "u1", listOf(full))
        val back = st.load("s")["u1"]!!.slots[0]!!.single()
        assertEquals(full, back)
        assertTrue(File(tmp.root, "alt/s.json").isFile)
    }
}
