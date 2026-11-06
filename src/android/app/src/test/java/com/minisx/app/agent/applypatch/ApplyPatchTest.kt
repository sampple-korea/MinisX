package com.minisx.app.agent.applypatch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ApplyPatchTest {

    private fun patch(body: String) = "*** Begin Patch\n$body\n*** End Patch"

    private fun apply(patchText: String, vararg files: Pair<String, String>): PatchPlan {
        val fs = files.toMap()
        return PatchApplier.plan(PatchParser.parse(patchText)) { fs[it] }
    }

    private fun fails(block: () -> Unit): String {
        try {
            block()
        } catch (e: PatchException) {
            return e.message!!
        }
        fail("expected a PatchException")
        return ""
    }

    // ---- parser -------------------------------------------------------------------------------

    @Test fun `an empty patch has no hunks`() {
        assertEquals(emptyList<Hunk>(), PatchParser.parse("*** Begin Patch\n*** End Patch"))
    }

    @Test fun `the boundary lines are required`() {
        assertTrue(fails { PatchParser.parse("bad") }.contains("first line of the patch must be '*** Begin Patch'"))
        assertTrue(fails { PatchParser.parse("*** Begin Patch\nbad") }.contains("last line of the patch must be '*** End Patch'"))
    }

    @Test fun `a heredoc around the patch is accepted`() {
        val hunks = PatchParser.parse("<<'EOF'\n*** Begin Patch\n*** Add File: a.txt\n+hi\n*** End Patch\nEOF")
        assertEquals(listOf(Hunk.AddFile("a.txt", "hi\n")), hunks)
    }

    @Test fun `add, delete and update hunks`() {
        val hunks = PatchParser.parse(
            patch("*** Add File: one.txt\n+one\n+two\n*** Delete File: two.txt\n*** Update File: three.txt\n@@\n-a\n+b"),
        )
        assertEquals(3, hunks.size)
        assertEquals(Hunk.AddFile("one.txt", "one\ntwo\n"), hunks[0])
        assertEquals(Hunk.DeleteFile("two.txt"), hunks[1])
        val update = hunks[2] as Hunk.UpdateFile
        assertEquals("three.txt", update.path)
        assertNull(update.movePath)
        assertEquals(listOf("a"), update.chunks.single().oldLines)
        assertEquals(listOf("b"), update.chunks.single().newLines)
    }

    @Test fun `a move and a context line`() {
        val u = PatchParser.parse(patch("*** Update File: old.py\n*** Move to: new.py\n@@ def f():\n-    pass\n+    return 1")).single() as Hunk.UpdateFile
        assertEquals("new.py", u.movePath)
        assertEquals(listOf("def f():"), u.chunks.single().changeContexts)
    }

    @Test fun `an empty update hunk is rejected`() {
        assertTrue(fails { PatchParser.parse(patch("*** Update File: test.py")) }.contains("Update file hunk for path 'test.py' is empty"))
    }

    @Test fun `two @@ lines in a row name a place inside a place`() {
        val u = PatchParser.parse(patch("*** Update File: a.py\n@@ class B:\n@@     def f(self):\n-x\n+y")).single() as Hunk.UpdateFile
        assertEquals(listOf("class B:", "    def f(self):"), u.chunks.single().changeContexts)
    }

    @Test fun `a line that is not a change is rejected with the rule`() {
        val m = fails { PatchParser.parse(patch("*** Update File: a.py\n@@\nnot a change")) }
        assertTrue(m.contains("Every line should start with ' ' (context line), '+' (added line), or '-' (removed line)"))
    }

    @Test fun `text between hunks that is not a header is rejected`() {
        assertTrue(fails { PatchParser.parse(patch("hello")) }.contains("is not a valid hunk header"))
    }

    // ---- applying -----------------------------------------------------------------------------

    @Test fun `add creates a file and reports it`() {
        val plan = apply(patch("*** Add File: a.txt\n+ab\n+cd"))
        assertEquals("ab\ncd\n", plan.files["a.txt"])
        assertEquals("Success. Updated the following files:\nA a.txt", plan.summary.text())
    }

    @Test fun `delete removes a file and needs it to exist`() {
        val plan = apply(patch("*** Delete File: gone.txt"), "gone.txt" to "x\n")
        assertTrue(plan.files.containsKey("gone.txt") && plan.files["gone.txt"] == null)
        assertEquals("Success. Updated the following files:\nD gone.txt", plan.summary.text())
        assertTrue(fails { apply(patch("*** Delete File: nope.txt")) }.contains("does not exist"))
    }

    @Test fun `update replaces the changed lines and keeps the rest`() {
        val plan = apply(
            patch("*** Update File: f.py\n@@\n foo\n-bar\n+BAR\n baz"),
            "f.py" to "foo\nbar\nbaz\n",
        )
        assertEquals("foo\nBAR\nbaz\n", plan.files["f.py"])
        assertEquals("Success. Updated the following files:\nM f.py", plan.summary.text())
    }

    @Test fun `several chunks are applied in order`() {
        val plan = apply(
            patch("*** Update File: m.txt\n@@\n foo\n-bar\n+BAR\n@@\n baz\n-qux\n+QUX"),
            "m.txt" to "foo\nbar\nbaz\nqux\n",
        )
        assertEquals("foo\nBAR\nbaz\nQUX\n", plan.files["m.txt"])
    }

    @Test fun `the context line finds the place`() {
        val original = "class A:\n    def f(self):\n        pass\nclass B:\n    def f(self):\n        pass\n"
        val plan = apply(
            patch("*** Update File: c.py\n@@ class B:\n@@     def f(self):\n-        pass\n+        return 2"),
            "c.py" to original,
        )
        assertEquals("class A:\n    def f(self):\n        pass\nclass B:\n    def f(self):\n        return 2\n", plan.files["c.py"])
    }

    @Test fun `a move writes the new path and removes the old`() {
        val plan = apply(
            patch("*** Update File: old.txt\n*** Move to: new.txt\n@@\n-a\n+b"),
            "old.txt" to "a\n",
        )
        assertEquals("b\n", plan.files["new.txt"])
        assertNull(plan.files["old.txt"])
        assertTrue(plan.files.containsKey("old.txt"))
        assertEquals("Success. Updated the following files:\nM new.txt", plan.summary.text())
    }

    @Test fun `end of file pins the change to the last lines`() {
        val plan = apply(
            patch("*** Update File: e.txt\n@@\n x\n-end\n+END\n*** End of File"),
            "e.txt" to "x\nend\nx\nend\n",
        )
        assertEquals("x\nend\nx\nEND\n", plan.files["e.txt"])
    }

    @Test fun `a chunk with only added lines is appended`() {
        val plan = apply(patch("*** Update File: a.txt\n@@\n+tail"), "a.txt" to "head\n")
        assertEquals("head\ntail\n", plan.files["a.txt"])
    }

    @Test fun `CRLF files stay CRLF`() {
        val plan = apply(patch("*** Update File: w.txt\n@@\n-b\n+B"), "w.txt" to "a\r\nb\r\nc\r\n")
        assertEquals("a\r\nB\r\nc\r\n", plan.files["w.txt"])
    }

    @Test fun `a file without a final newline gets one`() {
        val plan = apply(patch("*** Update File: n.txt\n@@\n-b\n+B"), "n.txt" to "a\nb")
        assertEquals("a\nB\n", plan.files["n.txt"])
    }

    @Test fun `lines match despite trailing spaces, indentation and typographic punctuation`() {
        assertEquals("X\n", apply(patch("*** Update File: t.txt\n@@\n-a\n+X"), "t.txt" to "a   \n").files["t.txt"])
        assertEquals("X\n", apply(patch("*** Update File: t.txt\n@@\n-a\n+X"), "t.txt" to "   a\n").files["t.txt"])
        // An en dash and curly quotes in the file, plain ones in the patch.
        val plan = apply(
            patch("*** Update File: p.txt\n@@\n-import a - b \"c\"\n+done"),
            "p.txt" to "import a \u2013 b \u201Cc\u201D\n",
        )
        assertEquals("done\n", plan.files["p.txt"])
    }

    @Test fun `lines that are not in the file fail the whole patch and say which`() {
        val m = fails {
            apply(
                patch("*** Add File: new.txt\n+x\n*** Update File: f.txt\n@@\n-missing\n+y"),
                "f.txt" to "present\n",
            )
        }
        assertTrue(m.contains("Failed to find expected lines in f.txt"))
        assertTrue(m.contains("missing"))
    }

    @Test fun `a missing context line is reported`() {
        assertTrue(fails { apply(patch("*** Update File: f.txt\n@@ nowhere\n-a\n+b"), "f.txt" to "a\n") }.contains("Failed to find context 'nowhere'"))
    }

    @Test fun `updating a file that does not exist fails`() {
        assertTrue(fails { apply(patch("*** Update File: no.txt\n@@\n-a\n+b")) }.contains("Failed to read file to update no.txt"))
    }

    @Test fun `later hunks see the earlier ones`() {
        val plan = apply(patch("*** Add File: n.txt\n+one\n*** Update File: n.txt\n@@\n-one\n+two"))
        assertEquals("two\n", plan.files["n.txt"])
    }
}
