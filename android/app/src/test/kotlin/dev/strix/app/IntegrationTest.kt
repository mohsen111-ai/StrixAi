package dev.strix.app

import dev.strix.app.core.*
import dev.strix.app.data.SessionData
import dev.strix.app.data.SessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Real OkHttp clients, real threads, one mock server standing in for both the model API and GitHub. */
class IntegrationTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var server: MockWebServer
    private val seen = java.util.Collections.synchronizedList(mutableListOf<String>())
    private val llmBodies = java.util.Collections.synchronizedList(mutableListOf<JsonObject>())
    private var llmCalls = 0

    private fun zip(vararg e: Pair<String, String>): Buffer {
        val bo = ByteArrayOutputStream()
        ZipOutputStream(bo).use { z -> e.forEach { (n, b) -> z.putNextEntry(ZipEntry("me-app-sha1/$n")); z.write(b.toByteArray()); z.closeEntry() } }
        return Buffer().write(bo.toByteArray())
    }

    private fun toolCall(id: String, name: String, args: String) =
        """{"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[{"id":"$id","type":"function","function":{"name":"$name","arguments":${kotlinx.serialization.json.JsonPrimitive(args)}}}]}}],"usage":{"prompt_tokens":50,"completion_tokens":10}}"""

    private val replies = listOf(
        toolCall("c1", "grep", """{"pattern":"greet"}"""),
        toolCall("c2", "read_file", """{"path":"src/Greeter.kt"}"""),
        toolCall("c3", "edit_file", """{"path":"src/Greeter.kt","old":"Hello, ${'$'}name","new":"Hello, ${'$'}name!"}"""),
        toolCall("c4", "write_file", """{"path":"src/GreeterTest.kt","content":"class GreeterTest\n"}"""),
        """{"choices":[{"message":{"role":"assistant","content":"Added the exclamation mark and a test stub."}}]}""",
        """{"choices":[{"message":{"role":"assistant","content":"Add exclamation to greeting"}}]}""",
    )

    @Before fun up() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(r: RecordedRequest): MockResponse {
                val path = r.path!!
                seen += "${r.method} $path"
                return when {
                    path.startsWith("/gh/repos/me/app/git/ref/heads/main") -> MockResponse().setBody("""{"object":{"sha":"sha1"}}""")
                    path.startsWith("/gh/repos/me/app/zipball/sha1") -> MockResponse().setBody(zip("README.md" to "# app\n", "src/Greeter.kt" to "fun greet(name: String) = \"Hello, \$name\"\n", "logo.png" to "\u0000\u0001"))
                    path == "/gh/repos/me/app/git/refs" -> MockResponse().setResponseCode(201).setBody("{}")
                    path.startsWith("/gh/repos/me/app/git/commits/sha1") -> MockResponse().setBody("""{"tree":{"sha":"tree1"}}""")
                    path == "/gh/repos/me/app/git/trees" -> MockResponse().setResponseCode(201).setBody("""{"sha":"tree2"}""")
                    path == "/gh/repos/me/app/git/commits" -> MockResponse().setResponseCode(201).setBody("""{"sha":"sha2"}""")
                    path.startsWith("/gh/repos/me/app/git/refs/heads/") -> MockResponse().setBody("{}")
                    path.startsWith("/gh/repos/me/app/pulls?") -> MockResponse().setBody("[]")
                    path == "/gh/repos/me/app/pulls" -> MockResponse().setResponseCode(201).setBody("""{"html_url":"https://github.com/me/app/pull/7"}""")
                    path == "/llm/chat/completions" -> {
                        llmBodies += StrixJson.parseToJsonElement(r.body.readUtf8()).jsonObject
                        val i = synchronized(this@IntegrationTest) { llmCalls++ }
                        MockResponse().setBody(replies[i])
                    }
                    else -> MockResponse().setResponseCode(404).setBody("""{"message":"Not Found"}""")
                }
            }
        }
        server.start()
    }

    @After fun down() { server.shutdown() }

    @Test fun realClientsEndToEnd() = runBlocking {
        val http = newHttpClient()
        val gh = GitHubApi(http, "ghp_test", server.url("/gh").toString().trimEnd('/'))
        val providers = mapOf("p" to ProviderConfig("p", "P", server.url("/llm").toString().trimEnd('/'), "sk-test"))
        val chains = mapOf(Roles.CODER to listOf(ModelRef("p", "coder-model")), Roles.FAST to listOf(ModelRef("p", "fast-model")), Roles.PLANNER to listOf(ModelRef("p", "plan")))
        val router = Router({ chains }, { providers }, OpenAiCompatClient(http), { 0.2 })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val c = SessionController(SessionData("i1", "me/app", "main"), gh, router, { AgentSettings(planning = false) }, SessionStore(tmp.newFolder("s")), scope,
            Dispatchers.Unconfined, Dispatchers.Default, Dispatchers.IO)

        c.load()
        assertEquals(Phase.Ready, c.phase)
        c.send("add an exclamation mark to the greeting and a test")
        withTimeout(20_000) { while (c.running) delay(20) }

        // the agent edited the right files through the real tool loop
        assertEquals(listOf("src/Greeter.kt", "src/GreeterTest.kt"), c.changes.map { it.path })
        assertEquals("say", c.items.last().kind)
        assertEquals(listOf("grep", "read_file", "edit_file", "write_file"), c.items.filter { it.kind == "tool" }.map { it.extra })
        assertTrue(c.items.filter { it.kind == "tool" }.all { it.ok && !it.running })

        // every request carried the tool schemas, the key, and a well-formed conversation
        llmBodies.forEach { b ->
            val msgs = b["messages"]!!.jsonArray.map { it.jsonObject }
            val open = HashSet<String>()
            for (m in msgs) when (m["role"]!!.jsonPrimitive.content) {
                "assistant" -> { assertTrue(open.isEmpty()); m["tool_calls"]?.takeIf { it !is JsonNull }?.jsonArray?.forEach { open += it.jsonObject["id"]!!.jsonPrimitive.content } }
                "tool" -> assertTrue(open.remove(m["tool_call_id"]!!.jsonPrimitive.content))
            }
            assertTrue(open.isEmpty())
        }
        assertEquals("coder-model", llmBodies[0]["model"]!!.jsonPrimitive.content)
        assertEquals(9, llmBodies[0]["tools"]!!.jsonArray.size)
        assertTrue(llmBodies[0]["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonPrimitive.content.contains("src/Greeter.kt"))

        // commit + PR through the real git data API
        val msg = c.suggestCommitMessage()
        assertEquals("Add exclamation to greeting", msg)
        val out = c.commit(msg, "strix/exclaim", true).getOrThrow()
        assertEquals("https://github.com/me/app/pull/7", out.prUrl)
        assertTrue(c.changes.isEmpty())
        val order = seen.filter { !it.startsWith("POST /llm") }
        assertEquals(
            listOf("GET /gh/repos/me/app/git/ref/heads/main", "GET /gh/repos/me/app/zipball/sha1", "POST /gh/repos/me/app/git/refs",
                "GET /gh/repos/me/app/git/commits/sha1", "POST /gh/repos/me/app/git/trees", "POST /gh/repos/me/app/git/commits",
                "PATCH /gh/repos/me/app/git/refs/heads/strix/exclaim"),
            order.take(7),
        )
        assertTrue(order[7].startsWith("GET /gh/repos/me/app/pulls?"))
        assertEquals("POST /gh/repos/me/app/pulls", order[8])

        // the tree payload had the edited file's real content
        val treeReq = server.takeAll("POST", "/gh/repos/me/app/git/trees").single()
        assertTrue(treeReq.contains("Hello, \$name!"))
        scope.coroutineContext[kotlinx.coroutines.Job]!!.cancel()
        Unit
    }

    private fun MockWebServer.takeAll(method: String, path: String): List<String> {
        val out = mutableListOf<String>()
        while (true) {
            val r = takeRequest(50, java.util.concurrent.TimeUnit.MILLISECONDS) ?: break
            if (r.method == method && r.path == path) out += r.body.readUtf8()
        }
        return out
    }
}
