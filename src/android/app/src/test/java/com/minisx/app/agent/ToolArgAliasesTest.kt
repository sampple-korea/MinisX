package com.minisx.app.agent

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolArgAliasesTest {

    @Test fun `Claude Code style read arguments become ours`() {
        val a = JSONObject("""{"file_path":"/root/a.py","limit":40,"offset":10}""")
        val renamed = ToolArgAliases.apply("file_read", a)
        assertEquals("/root/a.py", a.getString("path"))
        assertEquals(40, a.getInt("lines"))
        assertEquals(10, a.getInt("offset"))
        assertFalse(a.has("file_path"))
        assertTrue(renamed.contains("file_path->path"))
    }

    @Test fun `edit arguments`() {
        val a = JSONObject("""{"file_path":"/x","old_str":"a","new_str":"b"}""")
        ToolArgAliases.apply("file_edit", a)
        assertEquals("/x", a.getString("path"))
        assertEquals("a", a.getString("old_string"))
        assertEquals("b", a.getString("new_string"))
    }

    @Test fun `the declared name wins when both are sent`() {
        val a = JSONObject("""{"path":"/right","file_path":"/wrong"}""")
        val renamed = ToolArgAliases.apply("file_read", a)
        assertEquals("/right", a.getString("path"))
        assertFalse(a.has("file_path"))
        assertTrue(renamed.isEmpty())
    }

    @Test fun `an empty new_string is kept as the delete form`() {
        val a = JSONObject("""{"path":"/x","old_string":"a","new":""}""")
        ToolArgAliases.apply("file_edit", a)
        assertEquals("", a.getString("new_string"))
    }

    @Test fun `other tools`() {
        val s = JSONObject("""{"cmd":"ls"}""").also { ToolArgAliases.apply("shell_execute", it) }
        assertEquals("ls", s.getString("command"))
        val e = JSONObject("""{"command":"ls"}""").also { ToolArgAliases.apply("exec_command", it) }
        assertEquals("ls", e.getString("cmd"))
        val w = JSONObject("""{"id":3,"input":"y\n"}""").also { ToolArgAliases.apply("write_stdin", it) }
        assertEquals(3, w.getInt("session_id"))
        assertEquals("y\n", w.getString("chars"))
        val p = JSONObject("""{"patch":"*** Begin Patch"}""").also { ToolArgAliases.apply("apply_patch", it) }
        assertTrue(p.has("input"))
        val m = JSONObject("""{"query":"python"}""").also { ToolArgAliases.apply("memory_get", it) }
        assertEquals("python", m.getString("keywords"))
    }

    @Test fun `tools without a table are left alone`() {
        val a = JSONObject("""{"file_path":"x","description":"d"}""")
        assertTrue(ToolArgAliases.apply("browser_use", a).isEmpty())
        assertTrue(a.has("file_path") && a.has("description"))
    }
}
