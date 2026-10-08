package dev.strix.app.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AgentTest {
    private fun setup(llm: LlmClient, workspace: Workspace, settings: AgentSettings = AgentSettings(planning = false), events: MutableList<AgentEvent> = mutableListOf()): Pair<Agent, MutableList<AgentEvent>> {
        val tb = ToolBox(workspace)
        val router = testRouter(llm)
        return Agent({ settings }, router, tb, workspace, { events += it }) to events
    }

    /** Every assistant tool call must be answered by a tool message, and no tool message may be orphaned. */
    private fun assertValid(msgs: List<ChatMessage>) {
        val open = HashSet<String>()
        for (m in msgs) {
            if (m.role == "assistant") { assertTrue("unanswered ${open}", open.isEmpty()); m.toolCalls.forEach { open += it.id } }
            else if (m.role == "tool") assertTrue("orphan tool message ${m.toolCallId}", open.remove(m.toolCallId))
            else assertTrue("user message with unanswered calls $open", open.isEmpty())
        }
        assertTrue("dangling calls $open", open.isEmpty())
    }

    @Test fun readsEditsAndFinishes() = runBlocking {
        val w = ws("src/a.kt" to "fun a() = 1\n")
        val llm = ScriptedLlm(mutableListOf(
            call("list_dir", """{"path":"src"}"""),
            call("read_file", """{"path":"src/a.kt"}"""),
            call("edit_file", """{"path":"src/a.kt","old":"= 1","new":"= 2"}"""),
            say("Changed a() to return 2."),
        ))
        val (agent, events) = setup(llm, w)
        val out = agent.run("make a return two")
        assertEquals("Changed a() to return 2.", out)
        assertEquals("fun a() = 2\n", w.read("src/a.kt"))
        assertEquals(listOf("list_dir", "read_file", "edit_file"), events.filterIsInstance<AgentEvent.ToolStart>().map { it.name })
        assertTrue(events.filterIsInstance<AgentEvent.ToolDone>().all { it.ok })
        assertEquals(4, (events.last() as AgentEvent.Finished).steps)
        assertValid(agent.messages)
        assertTrue(agent.messages[0].content!!.contains("src/"))
    }

    @Test fun toolErrorsAreFedBackNotThrown() = runBlocking {
        val w = ws("a.txt" to "x")
        val llm = ScriptedLlm(mutableListOf(
            call("read_file", """{"path":"missing.txt"}"""),
            call("nonexistent_tool", "{}"),
            call("read_file", "garbage{{"),
            call("read_file", "{}"),
            say("gave up"),
        ))
        val (agent, events) = setup(llm, w)
        agent.run("do something")
        val done = events.filterIsInstance<AgentEvent.ToolDone>()
        assertEquals(4, done.size); assertTrue(done.none { it.ok })
        assertTrue(done[1].output.contains("unknown tool"))
        assertTrue(done[2].output.contains("not valid JSON"))
        assertTrue(done[3].output.contains("missing required"))
        assertValid(agent.messages)
    }

    @Test fun plannerRunsOnlyForBiggerFirstTasks() = runBlocking {
        val llm = ScriptedLlm(mutableListOf(say("1. read\n2. edit"), say("done")))
        val (agent, events) = setup(llm, ws("a.txt" to "x"), AgentSettings(planning = true))
        agent.run("please add a really thorough greeting feature to the project")
        assertEquals("plan", llm.seen[0].first)
        assertTrue(events.any { it is AgentEvent.Plan })
        assertTrue(llm.seen[1].second.any { it.content?.contains("1. read") == true })

        val llm2 = ScriptedLlm(mutableListOf(say("hi")))
        val (a2, e2) = setup(llm2, ws("a.txt" to "x"), AgentSettings(planning = true))
        a2.run("fix typo")
        assertFalse(e2.any { it is AgentEvent.Plan })
    }

    @Test fun plannerFailureDoesNotStopTheTask() = runBlocking {
        val llm = ScriptedLlm(mutableListOf({ throw ProviderError("401", 401) }, say("done anyway")))
        val chains = mapOf(Roles.PLANNER to listOf(ModelRef("p", "plan")), Roles.CODER to listOf(ModelRef("p", "a")))
        val w = ws("a.txt" to "x")
        val events = mutableListOf<AgentEvent>()
        val agent = Agent({ AgentSettings() }, testRouter(llm, chains), ToolBox(w), w, { events += it })
        assertEquals("done anyway", agent.run("please add a really thorough greeting feature to the project"))
        assertTrue(events.any { it is AgentEvent.Info && it.text.contains("Planner unavailable") })
    }

    @Test fun switchesModelAfterRepeatedBadCalls() = runBlocking {
        val bad = { LlmResponse("", listOf(ToolCall("x${System.nanoTime()}", "read_file", "oops"))) }
        val llm = ScriptedLlm(mutableListOf(bad, bad, bad, say("recovered on b")))
        val (agent, events) = setup(llm, ws("a.txt" to "x"))
        assertEquals("recovered on b", agent.run("do something"))
        assertEquals(listOf("a", "a", "a", "b"), llm.seen.map { it.first })
        assertTrue(events.any { it is AgentEvent.Info && it.text.contains("switching model") })
        assertValid(agent.messages)
    }

    @Test fun repeatedIdenticalCallsGetNudged() = runBlocking {
        val same = call("list_dir", """{"path":""}""")
        val llm = ScriptedLlm(mutableListOf(same, same, same, say("ok")))
        val (agent, _) = setup(llm, ws("a.txt" to "x"))
        agent.run("look around")
        assertTrue(agent.messages.any { it.content?.contains("repeating the same tool call") == true })
    }

    @Test fun stopsAtStepLimit() = runBlocking {
        val llm = ScriptedLlm((1..10).map { call("list_dir", """{"path":"$it"}""") }.toMutableList())
        val (agent, events) = setup(llm, ws("a.txt" to "x"), AgentSettings(planning = false, maxSteps = 3))
        val out = agent.run("loop forever")
        assertTrue(out.contains("Stopped after 3 steps"))
        assertTrue((events.last() as AgentEvent.Finished).stoppedEarly)
        assertValid(agent.messages)
    }

    @Test fun cancellationLeavesAValidConversation() = runBlocking {
        val w = ws("a.txt" to "x")
        lateinit var job: Job
        val llm = ScriptedLlm(mutableListOf({
            job.cancel()
            LlmResponse("", listOf(ToolCall("t1", "list_dir", "{}"), ToolCall("t2", "read_file", """{"path":"a.txt"}""")))
        }))
        val (agent, _) = setup(llm, w)
        job = launch(start = CoroutineStart.LAZY) { agent.run("do it") }
        job.start(); job.join()
        assertTrue(job.isCancelled)
        assertValid(agent.messages)
        // and the session continues cleanly afterwards
        val llm2 = ScriptedLlm(mutableListOf(say("fine")))
        val agent2 = Agent({ AgentSettings(planning = false) }, testRouter(llm2), ToolBox(w), w, { }, agent.messages)
        assertEquals("fine", agent2.run("continue"))
    }

    @Test fun followUpTurnsKeepHistoryAndSeeNewEdits() = runBlocking {
        val w = ws("a.txt" to "x")
        val llm = ScriptedLlm(mutableListOf(call("write_file", """{"path":"new/file.kt","content":"//"}"""), say("first"), say("second")))
        val (agent, _) = setup(llm, w)
        agent.run("create file")
        agent.run("what did you do?")
        val last = llm.seen.last().second
        assertTrue(last[0].content!!.contains("new/"))     // repo map refreshed with the staged file
        assertEquals(2, last.count { it.role == "user" })
        assertValid(agent.messages)
    }

    @Test fun compactionTrimsThenSummarises() = runBlocking {
        val big = "z".repeat(6000)
        val w = ws("big.txt" to (1..400).joinToString("\n") { "line $it $big".take(80) })
        val steps = (1..12).map { i -> call("read_file", """{"path":"big.txt","start_line":${i * 10},"end_line":${i * 10 + 300}}""") }
        val llm = ScriptedLlm((steps + listOf(say("finished"))).toMutableList().also { it.add(8, say("SUMMARY of work")) })
        val events = mutableListOf<AgentEvent>()
        val (agent, _) = setup(llm, w, AgentSettings(planning = false, contextTokens = 6000, maxSteps = 30), events)
        val out = agent.run("read a lot")
        assertEquals("finished", out)
        assertTrue(events.any { it is AgentEvent.Compacted })
        assertValid(agent.messages)
        assertTrue(Agent.estimateTokens(agent.messages) < 20000)
    }

    @Test fun compactionFallsBackWhenSummaryFails() = runBlocking {
        val w = ws("big.txt" to "q".repeat(20000))
        val reads = (1..10).map { call("read_file", """{"path":"big.txt"}""") }
        val llm = object : LlmClient {
            val inner = ScriptedLlm((reads + listOf(say("end"))).toMutableList())
            override suspend fun chat(provider: ProviderConfig, model: String, messages: List<ChatMessage>, tools: kotlinx.serialization.json.JsonArray?, temperature: Double): LlmResponse =
                if (model == "fast") throw ProviderError("down", 500) else inner.chat(provider, model, messages, tools, temperature)
        }
        val events = mutableListOf<AgentEvent>()
        val (agent, _) = setup(llm, w, AgentSettings(planning = false, contextTokens = 3000, maxSteps = 30), events)
        assertEquals("end", agent.run("go"))
        assertTrue(events.filterIsInstance<AgentEvent.Compacted>().any { it.text.contains("dropped") })
        assertValid(agent.messages)
    }

    @Test fun todoToolEmitsEvent() = runBlocking {
        val llm = ScriptedLlm(mutableListOf(call("todo", """{"items":[{"text":"one","done":false}]}"""), say("ok")))
        val (agent, events) = setup(llm, ws("a" to "b"))
        agent.run("plan it out")
        assertEquals("one", events.filterIsInstance<AgentEvent.Todos>().single().items.single().text)
    }
}
