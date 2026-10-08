package dev.strix.app.core

import org.junit.Assert.*
import org.junit.Test

class DiffWorkspaceTest {
    @Test fun diffAddsAndRemoves() {
        val d = Diff.unified("a\nb\nc\nd", "a\nB\nc\nd\ne")
        assertEquals(2, d.added)
        assertEquals(1, d.removed)
        assertTrue(d.lines.any { it.kind == DiffKind.DEL && it.text == "b" })
        assertTrue(d.lines.any { it.kind == DiffKind.ADD && it.text == "B" })
        assertTrue(d.lines.first().kind == DiffKind.HUNK)
    }

    @Test fun diffNewAndDeletedFiles() {
        assertEquals(3, Diff.unified("", "x\ny\nz").added)
        assertEquals(2, Diff.unified("x\ny", "").removed)
        assertTrue(Diff.unified("same", "same").lines.isEmpty())
    }

    @Test fun diffLineNumbers() {
        val old = (1..20).joinToString("\n") { "l$it" }
        val new = old.replace("l10", "TEN")
        val d = Diff.unified(old, new)
        val del = d.lines.first { it.kind == DiffKind.DEL }
        val add = d.lines.first { it.kind == DiffKind.ADD }
        assertEquals(10, del.oldNo)
        assertEquals(10, add.newNo)
        assertEquals("@@ -7 +7 @@", d.lines.first().text)
    }

    @Test fun diffHugeMiddleDoesNotBlowUp() {
        val a = (1..4000).joinToString("\n") { "a$it" }
        val b = (1..4000).joinToString("\n") { "b$it" }
        val d = Diff.unified(a, b)
        assertEquals(4000, d.added)
        assertEquals(4000, d.removed)
    }

    @Test fun stagingTracksKinds() {
        val w = ws("a.txt" to "one", "dir/b.txt" to "two")
        w.write("a.txt", "ONE")
        w.write("new.txt", "n")
        w.delete("dir/b.txt")
        val kinds = w.changes().associate { it.path to it.kind }
        assertEquals(ChangeKind.MODIFIED, kinds["a.txt"])
        assertEquals(ChangeKind.ADDED, kinds["new.txt"])
        assertEquals(ChangeKind.DELETED, kinds["dir/b.txt"])
        assertFalse(w.exists("dir/b.txt"))
        assertEquals(listOf("a.txt", "new.txt"), w.allPaths())
    }

    @Test fun writingOriginalTextClearsTheChange() {
        val w = ws("a.txt" to "one")
        w.write("a.txt", "two")
        assertTrue(w.hasChanges())
        w.write("a.txt", "one")
        assertFalse(w.hasChanges())
        w.write("tmp.txt", "x")
        w.delete("tmp.txt")
        assertFalse(w.hasChanges())
    }

    @Test fun pathSafety() {
        val w = ws("a.txt" to "x")
        assertThrows(WorkspaceException::class.java) { w.write("../evil", "x") }
        assertThrows(WorkspaceException::class.java) { w.write(".git/config", "x") }
        w.write("/./sub/ok.txt", "x")
        assertTrue(w.exists("sub/ok.txt"))
    }

    @Test fun binaryFilesAreVisibleButNotEditable() {
        val w = Workspace("me/app", "main", "s", snap("a.txt" to "x", others = setOf("logo.png")))
        assertTrue(w.allPaths().contains("logo.png"))
        assertThrows(WorkspaceException::class.java) { w.write("logo.png", "x") }
        assertEquals(listOf("a.txt"), w.textPaths())
    }

    @Test fun listDirAndGlobAndGrep() {
        val w = ws("src/A.kt" to "class A\nfun hello()", "src/sub/B.kt" to "class B", "README.md" to "hi")
        assertEquals(listOf("sub/", "A.kt"), w.list("src"))
        assertEquals(listOf("README.md", "src/", ), w.list("").sortedBy { it.length }.let { listOf("README.md", "src/") }.also { assertTrue(w.list("").containsAll(it)) })
        assertEquals(listOf("src/A.kt", "src/sub/B.kt"), w.glob("**/*.kt"))
        assertEquals(listOf("src/A.kt"), w.glob("src/*.kt"))
        val hits = w.grep(Regex("hello"), null, null)
        assertEquals(listOf("src/A.kt:2: fun hello()"), hits)
        assertTrue(w.grep(Regex("class"), "src/sub", null).single().startsWith("src/sub/B.kt"))
    }

    @Test fun commitRebasesStagedEdits() {
        val w = ws("a.txt" to "one")
        w.write("a.txt", "two"); w.write("b.txt", "b")
        w.markCommitted("sha1", "strix/x")
        assertFalse(w.hasChanges())
        assertEquals("two", w.read("a.txt"))
        assertEquals("sha1", w.baseSha)
        assertEquals("strix/x", w.workBranch)
        w.write("a.txt", "one")
        assertEquals(ChangeKind.MODIFIED, w.changes().single().kind)
        assertEquals("two", w.changes().single().old)
    }

    @Test fun overlayRoundTrip() {
        val w = ws("a.txt" to "one", "gone.txt" to "g")
        w.write("a.txt", "two"); w.delete("gone.txt"); w.write("n.txt", "n")
        val w2 = ws("a.txt" to "one", "gone.txt" to "g")
        w2.importOverlay(w.exportOverlay())
        assertEquals(w.changes().map { it.path to it.kind }, w2.changes().map { it.path to it.kind })
    }

    @Test fun repoMapCollapsesBigRepos() {
        val files = (1..400).map { "pkg${it % 10}/mod${it % 7}/f$it.kt" to "x" }.toTypedArray()
        val m = ws(*files).repoMap(40)
        assertTrue(m.lines().size < 80)
        assertTrue(m.contains("files)"))
    }

    @Test fun globConversion() {
        assertTrue(Workspace.globToRegex("**/*.{kt,java}").matches("a/b/C.java"))
        assertTrue(Workspace.globToRegex("**/*.{kt,java}").matches("C.kt"))
        assertFalse(Workspace.globToRegex("*.kt").matches("a/C.kt"))
        assertTrue(Workspace.globToRegex("src/**").matches("src/a/b"))
    }
}
