package dev.strix.app.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonObject

sealed interface AgentEvent {
    data class Info(val text: String) : AgentEvent
    data class Plan(val text: String, val model: String) : AgentEvent
    data class Switched(val role: String, val model: String, val reason: String) : AgentEvent
    data class Say(val text: String, val model: String) : AgentEvent
    data class ToolStart(val id: String, val name: String, val args: String) : AgentEvent
    data class ToolDone(val id: String, val name: String, val ok: Boolean, val summary: String, val output: String) : AgentEvent
    data class Todos(val items: List<TodoItem>) : AgentEvent
    data class Compacted(val text: String) : AgentEvent
    data class Failed(val text: String) : AgentEvent
    data class Finished(val steps: Int, val stoppedEarly: Boolean) : AgentEvent
}

val SYSTEM_PROMPT = """You are Strix, an autonomous coding agent. You work on the GitHub repository {repo} (base branch {branch}) through tools, and you finish the user's task by calling them, not by describing what you would do.

Your tools read and edit the repository in memory. You cannot run code, tests or shell commands. The user reviews your staged changes as a diff and then commits them. So be careful and verify by reading.

How to work:
- Explore first: list_dir, glob, grep and read_file before editing. Never invent file contents or paths you have not seen.
- Make small, focused changes with edit_file ('old' must match the file exactly and be unique). Use write_file for new files or complete rewrites.
- After editing, re-read the changed region to check syntax, imports, names and that callers still match. Fix what you find.
- When you add code that needs tests, build files or docs, add them too. Match the existing style, naming and libraries of the repo.
- For tasks with several steps keep a checklist with the todo tool and update it as you go.
- Never write secrets into files. Do not touch files the task does not need.
- When finished, reply without tool calls with a short summary: what changed, and anything the user should check or run themselves (for example the test command). If you need an answer from the user, ask in that reply.

{notes}
Repository map:
{map}
"""

const val PLANNER_PROMPT = "You are the planning model for a coding agent. Given the task and the repository map, write a concise plan: 3 to 6 numbered steps naming the files to inspect or change and how to check the result by reading. No code, no preamble."

class Agent(
    private val settings: () -> AgentSettings,
    private val router: Router,
    private val tools: ToolBox,
    private val ws: Workspace,
    private val emit: (AgentEvent) -> Unit,
    initialMessages: List<ChatMessage> = emptyList(),
) {
    var messages: MutableList<ChatMessage> = initialMessages.toMutableList()
        private set

    init {
        if (messages.isEmpty()) reset()
    }

    fun reset() {
        messages = mutableListOf(ChatMessage.system(buildSystem()))
    }

    private fun buildSystem(): String {
        var notes = ""
        for (n in listOf("STRIX.md", "AGENTS.md", "CLAUDE.md")) {
            val t = ws.read(n)
            if (t != null) { notes = "Project notes ($n):\n${t.take(4000)}\n"; break }
        }
        return SYSTEM_PROMPT.replace("{repo}", ws.repo).replace("{branch}", ws.workBranch ?: ws.baseBranch)
            .replace("{notes}", notes).replace("{map}", ws.repoMap())
    }

    /** Runs one user turn to completion. Returns the final assistant text. Cancellation stops it cleanly. */
    suspend fun run(task: String): String {
        val cfg = settings()
        // The system prompt is rebuilt each turn so the repo map reflects staged edits.
        if (messages.isNotEmpty() && messages[0].role == "system") messages[0] = ChatMessage.system(buildSystem())
        messages += ChatMessage.user(task)

        if (cfg.planning && task.split(Regex("\\s+")).size >= 8 && messages.count { it.role == "user" } == 1) plan(task)

        var bad = 0
        val skip = HashSet<ModelRef>()
        var lastSig = ""
        var repeats = 0
        var final = ""
        var steps = 0
        var stopped = false
        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                if (steps >= cfg.maxSteps) {
                    stopped = true
                    emit(AgentEvent.Info("Reached the ${cfg.maxSteps}-step limit. Send \"continue\" to keep going."))
                    final = "Stopped after ${cfg.maxSteps} steps. Say \"continue\" and I'll pick up where I left off."
                    messages += ChatMessage.assistant(final)
                    break
                }
                steps++
                compactIfNeeded(cfg)
                val (resp, ref) = router.complete(Roles.CODER, messages, tools.schemas(), skip)
                if (resp.toolCalls.isEmpty()) {
                    final = resp.content
                    messages += ChatMessage.assistant(final)
                    emit(AgentEvent.Say(final, ref.toString()))
                    break
                }
                if (resp.content.isNotBlank()) emit(AgentEvent.Say(resp.content, ref.toString()))
                messages += ChatMessage.assistant(resp.content.ifBlank { null }, resp.toolCalls)

                var badThisRound = 0
                for (call in resp.toolCalls) {
                    currentCoroutineContext().ensureActive()
                    emit(AgentEvent.ToolStart(call.id, call.name, call.arguments))
                    val result = runTool(call)
                    if (!result.ok) badThisRound++
                    emit(AgentEvent.ToolDone(call.id, call.name, result.ok, result.summary, result.text))
                    messages += ChatMessage.tool(call.id, call.name, result.text.take(MAX_TOOL_CHARS))
                }
                bad = if (badThisRound == resp.toolCalls.size) bad + 1 else 0
                if (bad >= cfg.badCallsBeforeSwitch) {
                    skip += ref
                    bad = 0
                    messages += ChatMessage.user("(system) The last tool calls failed repeatedly. Re-read the files involved and try a different approach.")
                    emit(AgentEvent.Info("$ref kept failing tool calls: switching model"))
                }
                val sig = resp.toolCalls.joinToString("|") { it.name + it.arguments }
                if (sig == lastSig) repeats++ else { repeats = 0; lastSig = sig }
                if (repeats >= 2) {
                    messages += ChatMessage.user("(system) You are repeating the same tool call. Stop and either try something different or finish with a summary.")
                    repeats = 0
                }
            }
        } catch (e: CancellationException) {
            repairDangling()
            throw e
        }
        emit(AgentEvent.Finished(steps, stopped))
        return final
    }

    private suspend fun plan(task: String) {
        try {
            val msgs = listOf(
                ChatMessage.system(PLANNER_PROMPT),
                ChatMessage.user("Task: $task\n\nRepository map:\n${ws.repoMap(80)}"),
            )
            val (resp, ref) = router.complete(Roles.PLANNER, msgs)
            val text = resp.content.trim()
            if (text.isNotEmpty()) {
                emit(AgentEvent.Plan(text, ref.toString()))
                messages += ChatMessage.user("(system) Plan from the planning model, follow it unless the code says otherwise:\n$text")
                messages += ChatMessage.assistant("Understood. I'll follow the plan.")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emit(AgentEvent.Info("Planner unavailable, continuing without a plan"))
        }
    }

    private fun runTool(call: ToolCall): ToolResult {
        val args: JsonObject = tools.parseArgs(call.arguments)
            ?: return ToolResult(false, "error: arguments were not valid JSON. Send a single JSON object. You sent: ${call.arguments.take(200)}")
        tools.validate(call.name, args)?.let { return ToolResult(false, "error: $it") }
        return try {
            val r = tools.execute(call.name, args)
            if (call.name == "todo") emit(AgentEvent.Todos(tools.todos))
            r
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolResult(false, "error: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** If a run is cancelled between an assistant tool call and its results, close the pairs so the next request is valid. */
    private fun repairDangling() {
        val last = messages.lastIndex
        var i = last
        while (i >= 0 && messages[i].role == "tool") i--
        if (i < 0) return
        val m = messages[i]
        if (m.role == "assistant" && m.toolCalls.isNotEmpty()) {
            val answered = messages.drop(i + 1).filter { it.role == "tool" }.mapNotNull { it.toolCallId }.toSet()
            m.toolCalls.filter { it.id !in answered }.forEach { messages += ChatMessage.tool(it.id, it.name, "cancelled by the user") }
        }
    }

    // ---- context management ------------------------------------------------
    private suspend fun compactIfNeeded(cfg: AgentSettings) {
        val limit = (cfg.contextTokens * 0.7).toInt()
        if (estimateTokens(messages) <= limit) return
        // Pass 1: shrink old tool output.
        val keepFrom = (messages.size - 8).coerceAtLeast(1)
        for (i in 1 until keepFrom) {
            val m = messages[i]
            if (m.role == "tool" && (m.content?.length ?: 0) > 400) messages[i] = m.copy(content = m.content!!.take(300) + "\n...[trimmed]")
        }
        if (estimateTokens(messages) <= limit) { emit(AgentEvent.Compacted("trimmed old tool output")); return }
        // Pass 2: summarise older turns with the cheap model.
        var cut = (messages.size - 6).coerceAtLeast(2)
        while (cut < messages.size && messages[cut].role == "tool") cut++   // never split a tool_call / tool pair
        if (cut >= messages.size || cut <= 1) return
        val old = messages.subList(1, cut)
        val transcript = old.joinToString("\n") { m ->
            val calls = if (m.toolCalls.isNotEmpty()) " [calls: " + m.toolCalls.joinToString { it.name } + "]" else ""
            "${m.role}$calls: ${(m.content ?: "").take(500)}"
        }.take(24_000)
        try {
            val (resp, _) = router.complete(
                Roles.FAST,
                listOf(
                    ChatMessage.system("Summarise this coding-agent transcript for the agent's own later use: the task, files read and changed (paths), decisions, and what is left. Under 300 words."),
                    ChatMessage.user(transcript),
                ),
            )
            val rest = messages.subList(cut, messages.size).toList()
            val sys = messages[0]
            messages = (listOf(sys, ChatMessage.user("(system) Summary of earlier work in this session:\n${resp.content}"), ChatMessage.assistant("Noted.")) + rest).toMutableList()
            emit(AgentEvent.Compacted("summarised ${old.size} earlier messages"))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Cheap fallback: drop the oldest turns outright.
            val rest = messages.subList(cut, messages.size).toList()
            messages = (listOf(messages[0], ChatMessage.user("(system) Earlier turns were dropped to save space. Re-read files if needed.")) + rest).toMutableList()
            emit(AgentEvent.Compacted("dropped ${old.size} old messages"))
        }
    }

    companion object {
        const val MAX_TOOL_CHARS = 12_000
        fun estimateTokens(msgs: List<ChatMessage>): Int =
            msgs.sumOf { (it.content?.length ?: 0) + it.toolCalls.sumOf { c -> c.arguments.length + c.name.length } + 8 } / 4
    }
}
