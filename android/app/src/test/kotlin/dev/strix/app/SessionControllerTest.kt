package dev.strix.app

import dev.strix.app.core.*
import dev.strix.app.data.SessionData
import dev.strix.app.data.SessionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FakeGitHub(var files: Map<String, String> = mapOf("src/a.kt" to "fun a() = 1\n")) : GitHubSource {
    val log = mutableListOf<String>()
    var failCommit: Exception? = null
    var failPr: Exception? = null
    var existingPr: String? = null
    override suspend fun branchSha(repo: String, branch: String): String { log += "sha:$branch"; return "sha-$branch" }
    override suspend fun snapshot(repo: String, sha: String) = Snapshot(files, emptySet(), false)
    override suspend fun createBranch(repo: String, name: String, fromSha: String) { log += "branch:$name@$fromSha" }
    override suspend fun commit(repo: String, branch: String, parentSha: String, changes: List<FileChange>, message: String): CommitResult {
        failCommit?.let { throw it }
        log += "commit:$branch:${changes.joinToString { it.path + (if (it.content == null) "(del)" else "") }}:$message"
        return CommitResult("new-sha", branch, "https://github.com/$repo/commit/new-sha")
    }
    override suspend fun openPullRequest(repo: String, title: String, body: String, head: String, base: String): String {
        failPr?.let { throw it }
        log += "pr:$head->$base:$title"; return "https://github.com/$repo/pull/1"
    }
    override suspend fun existingPullRequest(repo: String, head: String, base: String) = existingPr
}

class SessionControllerTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var store: SessionStore
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @Before fun setUp() { store = SessionStore(tmp.newFolder("s")) }

    private fun controller(llm: LlmClient, gh: FakeGitHub, data: SessionData = SessionData("s1", "me/app", "main"), running: MutableList<Boolean> = mutableListOf()) =
        SessionController(
            data, gh, testRouter(llm), { AgentSettings(planning = false) }, store, scope,
            ui = Dispatchers.Unconfined, work = Dispatchers.Unconfined, io = Dispatchers.Unconfined,
            clock = { 1_700_000_000_000L }, onRunning = { running += it },
        )

    @Test fun fullFlowEditCommitAndPr() = runBlocking {
        val gh = FakeGitHub(); val flags = mutableListOf<Boolean>()
        val llm = ScriptedLlm(mutableListOf(
            call("edit_file", """{"path":"src/a.kt","old":"= 1","new":"= 2"}""", "e1"),
            call("write_file", """{"path":"src/b.kt","content":"fun b() = 3\n"}""", "e2"),
            say("Done: a returns 2 and b added."),
            say("Add return value change\n\nChanges a() and adds b()."),   // commit message from fast model
        ))
        val c = controller(llm, gh, running = flags)
        c.load()
        assertEquals(Phase.Ready, c.phase)
        c.send("change a to return two and add b")
        assertFalse(c.running)
        assertEquals(listOf(true, false), flags)
        assertEquals(listOf("src/a.kt", "src/b.kt"), c.changes.map { it.path })
        assertEquals("change a to return two and add b", c.title)
        assertEquals(listOf("user", "tool", "tool", "say"), c.items.map { it.kind })
        assertEquals("src/a.kt", c.items[1].text); assertTrue(c.items[1].ok); assertEquals("edited src/a.kt", c.items[1].summary)

        val msg = c.suggestCommitMessage()
        assertTrue(msg.startsWith("Add return value change"))
        val branch = c.defaultBranchName()
        assertTrue(branch.startsWith("strix/change-a-to-return-two"))
        val out = c.commit(msg, branch, openPr = true).getOrThrow()
        assertEquals("https://github.com/me/app/pull/1", out.prUrl)
        assertEquals(listOf("sha:main", "branch:$branch@sha-main", "commit:$branch:src/a.kt, src/b.kt:$msg", "pr:$branch->main:Add return value change"), gh.log)
        assertTrue(c.changes.isEmpty()); assertEquals(branch, c.workBranch)
        assertEquals(listOf("link", "link"), c.items.takeLast(2).map { it.kind })

        // a second round continues on the same branch without creating another
        gh.log.clear()
        gh.files = mapOf("src/a.kt" to "fun a() = 2\n", "src/b.kt" to "fun b() = 3\n")   // what GitHub now holds on that branch
        val llm2 = ScriptedLlm(mutableListOf(call("delete_file", """{"path":"src/b.kt"}"""), say("removed b")))
        val c2 = controller(llm2, gh, store.load("s1")!!)
        c2.load()
        assertEquals(branch, c2.workBranch)
        assertTrue(gh.log.first().startsWith("sha:strix/"))
        c2.send("remove b")
        assertEquals(ChangeKind.DELETED, c2.changes.single().kind)
        c2.commit("Remove b", "ignored", true).getOrThrow()
        assertTrue(gh.log.any { it.startsWith("commit:$branch:src/b.kt(del)") })
        assertFalse(gh.log.any { it.startsWith("branch:") })
    }

    @Test fun persistsAndRestoresStagedEditsAndHistory() = runBlocking {
        val gh = FakeGitHub()
        val c = controller(ScriptedLlm(mutableListOf(call("edit_file", """{"path":"src/a.kt","old":"= 1","new":"= 5"}"""), say("ok"))), gh)
        c.load(); c.send("edit a"); 
        val saved = store.load("s1")!!
        assertEquals(1, saved.overlay.size)
        assertTrue(saved.messages.any { it.role == "tool" })
        assertEquals(1, store.list().size)

        val llm = ScriptedLlm(mutableListOf(say("I remember")))
        val c2 = controller(llm, gh, saved)
        c2.load()
        assertEquals("src/a.kt", c2.changes.single().path)
        c2.send("what changed?")
        val sent = llm.seen.single().second
        assertTrue(sent.any { it.role == "tool" })               // history restored
        assertTrue(sent.count { it.role == "user" } >= 2)
    }

    @Test fun commitErrorsAreReportedAndKeepEdits() = runBlocking {
        val gh = FakeGitHub()
        val c = controller(ScriptedLlm(mutableListOf(call("write_file", """{"path":"x.txt","content":"x"}"""), say("ok"))), gh)
        c.load(); c.send("add x file please")
        gh.failCommit = GitHubError("GitHub refused (403)", 403)
        val r = c.commit("msg", "strix/x", true)
        assertTrue(r.isFailure); assertTrue(r.exceptionOrNull()!!.message!!.contains("403"))
        assertEquals(1, c.changes.size)       // nothing lost
        assertFalse(c.committing)
        gh.failCommit = null
        assertTrue(c.commit("msg", "strix/x2", false).isSuccess)
    }

    @Test fun prFailureStillCountsAsCommitted() = runBlocking {
        val gh = FakeGitHub().also { it.failPr = GitHubError("PR boom", 422) }
        val c = controller(ScriptedLlm(mutableListOf(call("write_file", """{"path":"x.txt","content":"x"}"""), say("ok"))), gh)
        c.load(); c.send("add x file please")
        val out = c.commit("m", "strix/y", true).getOrThrow()
        assertNull(out.prUrl); assertEquals("PR boom", out.prError)
        assertTrue(c.items.any { it.kind == "error" && it.text.contains("PR boom") })
        assertTrue(c.changes.isEmpty())
    }

    @Test fun existingPrIsReused() = runBlocking {
        val gh = FakeGitHub().also { it.existingPr = "https://github.com/me/app/pull/9" }
        val c = controller(ScriptedLlm(mutableListOf(call("write_file", """{"path":"x.txt","content":"x"}"""), say("ok"))), gh)
        c.load(); c.send("add x file please")
        assertEquals("https://github.com/me/app/pull/9", c.commit("m", "strix/y", true).getOrThrow().prUrl)
        assertFalse(gh.log.any { it.startsWith("pr:") })
    }

    @Test fun directCommitToBaseBranchSkipsBranchAndPr() = runBlocking {
        val gh = FakeGitHub()
        val c = controller(ScriptedLlm(mutableListOf(call("write_file", """{"path":"x.txt","content":"x"}"""), say("ok"))), gh)
        c.load(); c.send("add x file please")
        val out = c.commit("m", "main", true).getOrThrow()
        assertNull(out.prUrl)
        assertEquals(listOf("sha:main", "commit:main:x.txt:m"), gh.log)
    }

    @Test fun revertDropsEdits() = runBlocking {
        val c = controller(ScriptedLlm(mutableListOf(call("edit_file", """{"path":"src/a.kt","old":"= 1","new":"= 2"}"""), say("ok"))), FakeGitHub())
        c.load(); c.send("edit a")
        assertEquals(1, c.changes.size)
        c.revert("src/a.kt")
        assertTrue(c.changes.isEmpty())
        assertTrue(c.commit("m", "b", false).isFailure)    // nothing to commit
    }

    @Test fun loadFailureIsShownNotThrown() = runBlocking {
        val gh = object : GitHubSource by FakeGitHub() {
            override suspend fun branchSha(repo: String, branch: String): String = throw GitHubError("Not found (404).", 404)
        }
        val c = controller(ScriptedLlm(mutableListOf()), FakeGitHub())
        val bad = SessionController(SessionData("s2", "me/none", "main"), gh, testRouter(ScriptedLlm(mutableListOf())), { AgentSettings() }, store, scope,
            Dispatchers.Unconfined, Dispatchers.Unconfined, Dispatchers.Unconfined)
        bad.load()
        assertTrue((bad.phase as Phase.Failed).message.contains("404"))
        bad.send("hello there")     // ignored while not ready
        assertTrue(bad.items.isEmpty())
    }

    @Test fun modelFailureBecomesAnErrorItem() = runBlocking {
        val c = controller(ScriptedLlm(mutableListOf({ throw ProviderError("401 bad key", 401) }, { throw ProviderError("401 bad key", 401) })), FakeGitHub())
        c.load(); c.send("do a thing please")
        assertFalse(c.running)
        assertEquals("error", c.items.last().kind)
        assertTrue(c.items.last().text.contains("every model"))
    }

    @Test fun reusedToolCallIdsDoNotCollide() = runBlocking {
        // Several open models answer every step with id "call_0".
        val llm = ScriptedLlm(mutableListOf(
            call("read_file", """{"path":"src/a.kt"}""", "call_0"),
            call("grep", """{"pattern":"fun"}""", "call_0"),
            call("edit_file", """{"path":"src/a.kt","old":"= 1","new":"= 2"}""", "call_0"),
            say("done"),
        ))
        val c = controller(llm, FakeGitHub())
        c.load(); c.send("do three things with one id")
        val tools = c.items.filter { it.kind == "tool" }
        assertEquals(listOf("read_file", "grep", "edit_file"), tools.map { it.extra })
        assertTrue(tools.all { it.ok && !it.running })
        assertEquals(listOf("src/a.kt", "fun", "src/a.kt"), tools.map { it.text })
        assertEquals(1, c.changes.size)
    }

    @Test fun argSummary() {
        assertEquals("a/b.kt", SessionController.argSummary("""{"path":"a/b.kt","old":"x"}"""))
        assertEquals("foo", SessionController.argSummary("""{"pattern":"foo"}"""))
        assertEquals("a → b", SessionController.argSummary("""{"from":"a","to":"b"}"""))
        assertEquals("", SessionController.argSummary("nope"))
    }
}
