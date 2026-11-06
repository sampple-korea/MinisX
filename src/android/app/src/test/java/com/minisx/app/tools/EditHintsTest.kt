package com.minisx.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EditHintsTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test fun `file_read line numbers are right-aligned and tab separated`() {
        assertEquals("     1\ta\n     2\tb\n", FileReadTool.numbered("a\nb\n", 1))
        assertEquals("    10\tx", FileReadTool.numbered("x", 10))
        assertEquals("   100\tx\n   101\t\n   102\ty", FileReadTool.numbered("x\n\ny", 100))
    }

    @Test fun `a read shows numbers, the tail counts from its real line, plain text stays available`() {
        val f = tmp.newFile("f.txt").apply { writeText((1..50).joinToString("\n") { "line$it" } + "\n") }
        val head = FileReadTool.read(f, "/f.txt", 1, 3, 15000, "head", "t")
        assertTrue(head.output, head.output.contains("     1\tline1\n     2\tline2\n     3\tline3"))
        val tail = FileReadTool.read(f, "/f.txt", 1, 2, 15000, "tail", "t")
        assertTrue(tail.output, tail.output.contains("    49\tline49\n    50\tline50"))
        val plain = FileReadTool.read(f, "/f.txt", 1, 2, 15000, "head", "t", lineNumbers = false)
        assertTrue(plain.output.endsWith("\nline1\nline2"))
    }

    @Test fun `pasted line numbers are stripped only when every line has one`() {
        assertEquals("a\n  b", EditHints.stripLineNumbers("     1\ta\n     2\t  b"))
        assertNull(EditHints.stripLineNumbers("     1\ta\nplain"))
        assertNull(EditHints.stripLineNumbers("no numbers"))
    }

    @Test fun `an edit that quotes file_read's numbers still lands, and says so`() {
        val f = tmp.newFile("e.txt").apply { writeText("one\ntwo\nthree\n") }
        val r = FileEditTool.edit(f, "/e.txt", "     2\ttwo", "     2\tTWO", false, "t")
        assertTrue(r.output, r.success)
        assertEquals("one\nTWO\nthree\n", f.readText())
        assertTrue(r.output.contains("line-number prefixes"))
    }

    @Test fun `an LF old_string matches a CRLF file and keeps its endings`() {
        val f = tmp.newFile("c.txt").apply { writeText("a\r\nb\r\nc\r\n") }
        val r = FileEditTool.edit(f, "/c.txt", "a\nb", "A\nB", false, "t")
        assertTrue(r.output, r.success)
        assertEquals("A\r\nB\r\nc\r\n", f.readText())
    }

    @Test fun `not found names the line where blanks are the only difference`() {
        val f = tmp.newFile("i.py").apply { writeText("def f():\n    return 1\n") }
        val r = FileEditTool.edit(f, "/i.py", "def f():\n  return 1", "x", false, "t")
        assertTrue(!r.success)
        assertTrue(r.output, r.output.contains("matches at line 1 when indentation"))
    }

    @Test fun `not found points at the closest line`() {
        val f = tmp.newFile("g.txt").apply { writeText("alpha\nthe quick brown fox\nomega\n") }
        val r = FileEditTool.edit(f, "/g.txt", "the quick brown dog", "x", false, "t")
        assertTrue(r.output, r.output.contains("closest line is line 2"))
    }

    @Test fun `several matches are listed by line`() {
        val f = tmp.newFile("m.txt").apply { writeText("x\ny\nx\nz\nx\n") }
        val r = FileEditTool.edit(f, "/m.txt", "x", "w", false, "t")
        assertTrue(!r.success)
        assertTrue(r.output, r.output.contains("found 3 times") && r.output.contains("lines 1, 3, 5"))
    }

    @Test fun `identical old and new are refused`() {
        val f = tmp.newFile("s.txt").apply { writeText("a\n") }
        assertTrue(FileEditTool.edit(f, "/s.txt", "a", "a", false, "t").output.contains("identical"))
    }

    @Test fun `a successful edit shows the changed lines with numbers`() {
        val f = tmp.newFile("o.txt").apply { writeText("1\n2\n3\n4\n5\n6\n") }
        val r = FileEditTool.edit(f, "/o.txt", "3", "THREE", false, "t")
        assertTrue(r.output, r.output.contains("     3\tTHREE"))
        assertTrue(r.output.contains("     2\t2") && r.output.contains("     5\t5"))
    }
}
