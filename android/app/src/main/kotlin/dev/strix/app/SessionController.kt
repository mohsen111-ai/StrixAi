package dev.strix.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.strix.app.core.Agent
import dev.strix.app.core.AgentEvent
import dev.strix.app.core.AgentSettings
import dev.strix.app.core.ChatMessage
import dev.strix.app.core.GitHubError
import dev.strix.app.core.GitHubSource
import dev.strix.app.core.Roles
import dev.strix.app.core.Router
import dev.strix.app.core.StagedChange
import dev.strix.app.core.StrixJson
import dev.strix.app.core.TodoItem
import dev.strix.app.core.ToolBox
import dev.strix.app.core.Workspace
import dev.strix.app.data.SessionData
import dev.strix.app.data.SessionStore
import dev.strix.app.data.TranscriptItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

sealed interface Phase {
    data object Loading : Phase
    data object Ready : Phase
    data class Failed(val message: String) : Phase
}

data class CommitOutcome(val branch: String, val commitUrl: String, val prUrl: String?, val prError: String? = null)

/** One open repo session: the downloaded workspace, the agent, the visible transcript, and the commit flow. */
class SessionController(
    initial: SessionData,
    private val github: GitHubSource,
    private val router: Router,
    private val settings: () -> AgentSettings,
    private val store: SessionStore,
    private val scope: CoroutineScope,
    private val ui: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val work: CoroutineDispatcher = Dispatchers.Default,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
    private val onRunning: (Boolean) -> Unit = {},
) {
    val id = initial.id
    val repo = initial.repo
    val baseBranch = initial.baseBranch

    var phase by mutableStateOf<Phase>(Phase.Loading); private set
    var running by mutableStateOf(false); private set
    var committing by mutableStateOf(false); private set
    var changes by mutableStateOf<List<StagedChange>>(emptyList()); private set
    var todos by mutableStateOf<List<TodoItem>>(emptyList()); private set
    var workBranch by mutableStateOf(initial.workBranch); private set
    var title by mutableStateOf(initial.title); private set
    var coderModel by mutableStateOf<String?>(null); private set
    val items = mutableStateListOf<TranscriptItem>().also { it.addAll(initial.items) }

    private var saved = initial
    private var ws: Workspace? = null
    private var agent: Agent? = null
    private var job: Job? = null
    private var lastSummary = ""

    private fun post(block: () -> Unit) { scope.launch(ui) { block() } }

    suspend fun load() {
        phase = Phase.Loading
        try {
            val w = withContext(work) {
                val branch = saved.workBranch ?: baseBranch
                val sha = github.branchSha(repo, branch)
                val snap = github.snapshot(repo, sha)
                Workspace(repo, baseBranch, sha, snap).also { w ->
                    w.workBranch = saved.workBranch
                    w.importOverlay(saved.overlay)
                }
            }
            ws = w
            agent = Agent({ settings() }, router, ToolBox(w), w, ::onEvent, saved.messages)
            changes = w.changes()
            coderModel = router.current(Roles.CODER)?.toString()
            if (w.truncated) items += TranscriptItem("info", "This repo is large: only part of it was downloaded.")
            phase = Phase.Ready
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            phase = Phase.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    fun send(text: String) {
        val a = agent ?: return
        if (running || text.isBlank() || phase != Phase.Ready) return
        val task = text.trim()
        running = true
        onRunning(true)
        if (title.isBlank()) title = task.lineSequence().first().take(60)
        items += TranscriptItem("user", task)
        job = scope.launch(work) {
            try {
                lastSummary = a.run(task)
            } catch (e: CancellationException) {
                post { items += TranscriptItem("info", "Stopped.") }
            } catch (e: Exception) {
                post { items += TranscriptItem("error", e.message ?: e.javaClass.simpleName) }
            } finally {
                post {
                    for (i in items.indices) if (items[i].running) items[i] = items[i].copy(running = false, ok = false, text = items[i].text)
                    running = false
                    onRunning(false)
                    coderModel = router.current(Roles.CODER)?.toString()
                    persist()
                }
            }
        }
    }

    fun stop() { job?.cancel() }

    private fun onEvent(e: AgentEvent) {
        // Called on the agent's thread. Anything read from the workspace is copied here, then handed to the UI thread.
        when (e) {
            is AgentEvent.ToolDone -> {
                val newChanges = if (ws != null && isWrite(e.name)) ws!!.changes() else null
                post {
                    val i = items.indexOfFirst { it.id == e.id }
                    val item = TranscriptItem("tool", items.getOrNull(i)?.text.orEmpty(), e.name, e.ok, e.id, false, e.output.take(1500), e.summary)
                    if (i >= 0) items[i] = item else items += item
                    if (newChanges != null) { changes = newChanges; persist() }
                }
            }
            is AgentEvent.ToolStart -> post { items += TranscriptItem("tool", argSummary(e.args), e.name, true, e.id, true) }
            is AgentEvent.Say -> post { items += TranscriptItem("say", e.text, e.model) }
            is AgentEvent.Plan -> post { items += TranscriptItem("plan", e.text, e.model) }
            is AgentEvent.Info -> post { items += TranscriptItem("info", e.text) }
            is AgentEvent.Switched -> post { items += TranscriptItem("info", "${e.role}: switched to ${e.model} (${e.reason})"); coderModel = e.model }
            is AgentEvent.Compacted -> post { items += TranscriptItem("info", "Context ${e.text}.") }
            is AgentEvent.Todos -> post { todos = e.items }
            is AgentEvent.Failed -> post { items += TranscriptItem("error", e.text) }
            is AgentEvent.Finished -> Unit
        }
    }

    private fun isWrite(name: String) = name in setOf("write_file", "edit_file", "delete_file", "move_file")

    // ---- review ------------------------------------------------------------
    fun revert(path: String) {
        if (running) return
        ws?.revert(path); changes = ws?.changes().orEmpty(); persist()
    }

    fun revertAll() {
        if (running) return
        ws?.revertAll(); changes = emptyList(); persist()
    }

    suspend fun suggestCommitMessage(): String {
        val cs = changes
        val fallback = if (cs.size == 1) "Update ${cs[0].path}" else "Update ${cs.size} files"
        if (cs.isEmpty()) return fallback
        val digest = buildString {
            for (c in cs.take(12)) {
                append("${c.kind} ${c.path} (+${c.diff.added} -${c.diff.removed})\n")
                c.diff.lines.take(25).forEach { append(it.text.take(120)).append('\n') }
                if (length > 7000) break
            }
        }
        return try {
            val (r, _) = router.complete(
                Roles.FAST,
                listOf(
                    ChatMessage.system("Write a git commit message for these changes: imperative subject under 60 characters, optionally a blank line and a short body. Reply with the message only, no quotes or markdown."),
                    ChatMessage.user(digest.take(8000)),
                ),
            )
            r.content.trim().trim('`', '"').ifBlank { fallback }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            fallback
        }
    }

    fun defaultBranchName(): String {
        val slug = title.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(28).trim('-').ifEmpty { "changes" }
        return "strix/$slug-" + (clock() / 1000 % 46656).toString(36).padStart(3, '0')
    }

    /** Commits every staged edit as one commit. Creates [branch] from the base commit unless this session already has a working branch. */
    suspend fun commit(message: String, branch: String, openPr: Boolean): Result<CommitOutcome> {
        val w = ws ?: return Result.failure(IllegalStateException("workspace not loaded"))
        if (running) return Result.failure(IllegalStateException("wait for the agent to finish"))
        if (!w.hasChanges()) return Result.failure(IllegalStateException("nothing to commit"))
        committing = true
        return try {
            val result = withContext(work) {
                val target = w.workBranch ?: branch.trim().ifEmpty { throw IllegalArgumentException("branch name is empty") }
                if (w.workBranch == null && target != baseBranch) github.createBranch(repo, target, w.baseSha)
                val files = w.toFileChanges()
                val msg = message.trim().ifEmpty { "Update ${files.size} files" }
                val c = github.commit(repo, target, w.baseSha, files, msg)
                w.markCommitted(c.commitSha, target)
                var pr: String? = null
                var prErr: String? = null
                if (openPr && target != baseBranch) {
                    try {
                        pr = github.existingPullRequest(repo, target, baseBranch) ?: github.openPullRequest(
                            repo, msg.lineSequence().first(),
                            (lastSummary.ifBlank { msg } + "\n\n" + files.joinToString("\n") { "- `${it.path}`" }).take(6000) + "\n\n_Made with Strix_",
                            target, baseBranch,
                        )
                    } catch (e: GitHubError) {
                        prErr = e.message
                    }
                }
                CommitOutcome(target, c.url, pr, prErr)
            }
            workBranch = result.branch
            changes = emptyList()
            items += TranscriptItem("link", "Committed to ${result.branch}", result.commitUrl)
            result.prUrl?.let { items += TranscriptItem("link", "Pull request", it) }
            result.prError?.let { items += TranscriptItem("error", "Committed, but the pull request failed: $it") }
            persist()
            Result.success(result)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            committing = false
        }
    }

    // ---- persistence ---------------------------------------------------------
    private var persistedMessages: List<ChatMessage> = initial.messages

    fun persist() {
        val w = ws
        val msgs = if (!running) agent?.messages?.toList() ?: persistedMessages else persistedMessages
        persistedMessages = msgs
        val data = SessionData(
            id, repo, baseBranch, w?.workBranch ?: workBranch, title, clock(),
            msgs, items.toList().takeLast(400), w?.exportOverlay() ?: saved.overlay,
        )
        saved = data
        scope.launch(io) { runCatching { store.save(data) } }
    }

    companion object {
        fun argSummary(raw: String): String = try {
            val o = StrixJson.parseToJsonElement(raw) as JsonObject
            listOf("path", "pattern", "from").firstNotNullOfOrNull { o[it]?.jsonPrimitive?.contentOrNull }
                ?.let { p -> if (o["to"] != null) "$p → ${o["to"]!!.jsonPrimitive.contentOrNull}" else p } ?: ""
        } catch (e: Exception) { "" }
    }
}

object ChangeKindLabel {
    fun of(k: dev.strix.app.core.ChangeKind) = when (k) {
        dev.strix.app.core.ChangeKind.ADDED -> "NEW"
        dev.strix.app.core.ChangeKind.MODIFIED -> "EDIT"
        dev.strix.app.core.ChangeKind.DELETED -> "DEL"
    }
}
