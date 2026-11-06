package com.minisx.app.agent.memory

import com.minisx.app.agent.memory.SavedMemories.Item
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SavedMemoriesTest {

    private val today = "2026-11-06"
    private fun add(items: List<Item>, text: String) = SavedMemories.add(items, text, today)
    private fun texts(items: List<Item>) = SavedMemories.entries(items).map { it.text }

    @Test fun `a fact is added with an id and today's date`() {
        val o = add(emptyList(), "User prefers answers in Korean.")
        assertTrue(o.ok && o.changed)
        val e = SavedMemories.entries(o.items).single()
        assertEquals("User prefers answers in Korean.", e.text)
        assertEquals(today, e.saved)
        assertTrue(e.id.startsWith("m") && e.id.length == 7)
        assertTrue(o.message, o.message.startsWith("Saved ${e.id}"))
    }

    @Test fun `the same fact is not saved twice`() {
        val first = add(emptyList(), "User lives in Seoul.").items
        val again = add(first, "user lives in seoul")
        assertFalse(again.changed)
        assertTrue(again.message.startsWith("Already saved as"))
        assertEquals(1, SavedMemories.entries(again.items).size)
    }

    @Test fun `a near-duplicate updates the old memory instead of adding`() {
        val first = add(emptyList(), "User works as a backend developer using Kotlin and Spring").items
        val id = SavedMemories.entries(first).single().id
        val o = add(first, "User works as a backend developer using Kotlin and Spring Boot")
        assertTrue(o.changed)
        assertEquals(1, SavedMemories.entries(o.items).size)
        assertTrue(o.message.startsWith("Updated $id"))
        assertEquals("User works as a backend developer using Kotlin and Spring Boot", SavedMemories.entries(o.items).single().text)
    }

    @Test fun `a markdown note becomes one memory per line`() {
        val o = add(emptyList(), "## Preferences\n- Likes dark mode\n- Uses Python 3.12\n")
        assertEquals(listOf("Likes dark mode", "Uses Python 3.12"), texts(o.items))
    }

    @Test fun `too long, empty and too many are refused with a way forward`() {
        assertTrue(add(emptyList(), "x".repeat(SavedMemories.MAX_TEXT_CHARS + 1)).message.contains("at most"))
        assertTrue(add(emptyList(), "  \n ").message.startsWith("Error"))
        assertTrue(add(emptyList(), (1..6).joinToString("\n") { "fact number $it" }).message.contains("at most 5"))
    }

    @Test fun `a full list says to delete or merge`() {
        var items: List<Item> = emptyList()
        repeat(SavedMemories.MAX_ENTRIES) { items = add(items, "unique fact $it alpha$it beta$it gamma$it").items }
        val o = add(items, "one more entirely different statement about cooking")
        assertFalse(o.ok)
        assertTrue(o.message, o.message.contains("memory is full"))
    }

    @Test fun `update and delete work by id and say when the id is wrong`() {
        val items = add(add(emptyList(), "User drinks coffee.").items, "User has a cat named Nabi.").items
        val (a, b) = SavedMemories.entries(items)
        val u = SavedMemories.update(items, a.id, "User drinks tea.", today)
        assertEquals(listOf("User drinks tea.", "User has a cat named Nabi."), texts(u.items))
        assertTrue(SavedMemories.update(items, "mzzzzzz", "x", today).message.contains("no saved memory with id"))
        val d = SavedMemories.delete(items, b.id)
        assertEquals(listOf("User drinks coffee."), texts(d.items))
        assertTrue(SavedMemories.delete(items, "nope").message.startsWith("Error"))
        assertEquals(emptyList<String>(), texts(SavedMemories.delete(items, "all").items))
        assertEquals(emptyList<String>(), texts(SavedMemories.delete(items, "${a.id}, ${b.id}").items))
    }

    @Test fun `the file round-trips and keeps lines it does not understand`() {
        val file = "# My memories\n\nNote to self: keep it short\n- [mabc123] User likes tea. <!-- saved 2026-01-02 -->\n- a bullet the user wrote by hand\n"
        val items = SavedMemories.parse(file)
        assertEquals(2, SavedMemories.entries(items).size)
        assertEquals("mabc123", SavedMemories.entries(items)[0].id)
        assertEquals("2026-01-02", SavedMemories.entries(items)[0].saved)
        val out = SavedMemories.render(items)
        assertTrue(out.startsWith("# My memories\n\nNote to self: keep it short\n- [mabc123] User likes tea. <!-- saved 2026-01-02 -->\n- ["))
        assertTrue(out.contains("] a bullet the user wrote by hand"))
        // Reading what was written gives the same memories and the same ids.
        assertEquals(SavedMemories.entries(items), SavedMemories.entries(SavedMemories.parse(out)))
    }

    @Test fun `ids made up for bullets without one are stable and distinct`() {
        val items = SavedMemories.parse("- same\n- same\n- other\n")
        val ids = SavedMemories.entries(items).map { it.id }
        assertEquals(3, ids.toSet().size)
        assertEquals(ids, SavedMemories.entries(SavedMemories.parse("- same\n- same\n- other\n")).map { it.id })
    }

    @Test fun `an empty file is empty`() {
        assertEquals(emptyList<Item>(), SavedMemories.parse(""))
        assertEquals("", SavedMemories.render(emptyList()))
        assertNull(SavedMemories.fragment(emptyList()))
    }

    @Test fun `the prompt fragment is deterministic and lists ids with dates`() {
        val items = SavedMemories.parse("- [maaa111] User prefers Korean. <!-- saved 2026-11-01 -->\n- [mbbb222] User uses Kotlin. <!-- saved 2026-11-05 -->\n")
        val f = SavedMemories.fragment(items)!!
        assertEquals(f, SavedMemories.fragment(items))
        assertTrue(f.startsWith("Saved memories"))
        assertTrue(f.contains("- [maaa111] User prefers Korean. (2026-11-01)"))
        assertTrue(f.indexOf("maaa111") < f.indexOf("mbbb222"))
    }

    @Test fun `a long list keeps the newest within the budget and says so`() {
        val items = (1..50).map { Item.Entry("m%06d".format(it), "memory number $it with some words to take space", "2026-11-06") }
        val f = SavedMemories.fragment(items, maxChars = 600)!!
        assertTrue(f.contains("memory number 50"))
        assertFalse(f.contains("memory number 1 "))
        assertTrue(f.contains("older memories are not shown"))
    }

    @Test fun `undo and edit of an add find the entry by its text`() {
        val items = add(emptyList(), "User likes jazz.").items
        val r = SavedMemories.revoke(items, "User likes jazz.")
        assertTrue(r.ok)
        assertEquals(emptyList<String>(), texts(r.items))
        assertFalse(SavedMemories.revoke(items, "something else").ok)
        val e = SavedMemories.replace(items, "User likes jazz.", "User likes blues.")
        assertEquals(listOf("User likes blues."), texts(e.items))
    }

    @Test fun `ids differ between texts`() {
        val a = SavedMemories.entries(add(emptyList(), "fact about apples and pears").items).single().id
        val b = SavedMemories.entries(add(emptyList(), "fact about bananas and plums").items).single().id
        assertNotEquals(a, b)
    }
}
