package dev.strix.app.core

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ProviderRouterTest {
    private lateinit var server: MockWebServer
    @Before fun up() { server = MockWebServer(); server.start() }
    @After fun down() { server.shutdown() }
    private fun provider(key: String = "k") = ProviderConfig("p", "P", server.url("/v1").toString(), key)

    @Test fun parsesToolCallsAndSendsBearer() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"model":"m","choices":[{"message":{"content":null,"tool_calls":[{"id":"c1","type":"function","function":{"name":"read_file","arguments":"{\"path\":\"a\"}"}}]}}],"usage":{"prompt_tokens":10,"completion_tokens":5}}"""))
        val r = OpenAiCompatClient().chat(provider(), "m", listOf(ChatMessage.user("hi")), ToolBox(ws()).schemas(), 0.2)
        assertEquals("read_file", r.toolCalls.single().name)
        assertEquals(10, r.promptTokens)
        val req = server.takeRequest()
        assertEquals("Bearer k", req.getHeader("Authorization"))
        assertEquals("/v1/chat/completions", req.path)
        val body = StrixJson.parseToJsonElement(req.body.readUtf8()).jsonObject
        assertEquals("auto", body["tool_choice"]!!.jsonPrimitive.content)
        assertEquals(9, body["tools"]!!.jsonArray.size)
    }

    @Test fun readsTextToolCallsAndStripsThinking() {
        val r = OpenAiCompatClient.parseResponse("""{"choices":[{"message":{"content":"<think>hmm</think>ok <tool_call>{\"name\":\"grep\",\"arguments\":{\"pattern\":\"x\"}}</tool_call>"}}]}""", "m")
        assertEquals("grep", r.toolCalls.single().name)
        assertEquals("""{"pattern":"x"}""", r.toolCalls.single().arguments)
        assertEquals("ok", r.content)
    }

    @Test fun contentPartsAndObjectArguments() {
        val r = OpenAiCompatClient.parseResponse("""{"choices":[{"message":{"content":[{"type":"text","text":"he"},{"type":"text","text":"llo"}]}}]}""", "m")
        assertEquals("hello", r.content)
        val r2 = OpenAiCompatClient.parseResponse("""{"choices":[{"message":{"tool_calls":[{"function":{"name":"glob","arguments":{"pattern":"*"}}}]}}]}""", "m")
        assertEquals("""{"pattern":"*"}""", r2.toolCalls.single().arguments)
        assertTrue(r2.toolCalls.single().id.startsWith("call_"))
    }

    @Test fun errorsAreClassified() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("retry-after", "7").setBody("slow down"))
        val e = runCatching { OpenAiCompatClient().chat(provider(), "m", listOf(ChatMessage.user("x")), null, 0.2) }.exceptionOrNull() as ProviderError
        assertEquals(429, e.status); assertTrue(e.retryable); assertEquals(7.0, e.retryAfterSec!!, 0.01)
        server.enqueue(MockResponse().setResponseCode(401).setBody("bad key"))
        val e2 = runCatching { OpenAiCompatClient().chat(provider(), "m", listOf(ChatMessage.user("x")), null, 0.2) }.exceptionOrNull() as ProviderError
        assertFalse(e2.retryable)
        server.enqueue(MockResponse().setBody("""{"error":{"message":"upstream down","code":502}}"""))
        val e3 = runCatching { OpenAiCompatClient().chat(provider(), "m", listOf(ChatMessage.user("x")), null, 0.2) }.exceptionOrNull() as ProviderError
        assertTrue(e3.retryable); assertEquals(502, e3.status)
        assertTrue(runCatching { OpenAiCompatClient().chat(provider(""), "m", emptyList(), null, 0.2) }.exceptionOrNull()!!.message!!.contains("no API key"))
    }

    @Test fun messageSerialization() {
        val m = ChatMessage.assistant(null, listOf(ToolCall("i", "glob", "{}"))).toJson()
        assertEquals("kotlinx.serialization.json.JsonNull", m["content"]!!::class.qualifiedName)
        assertEquals("i", m["tool_calls"]!!.jsonArray[0].jsonObject["id"]!!.jsonPrimitive.content)
        val t = ChatMessage.tool("i", "glob", "res").toJson()
        assertEquals("i", t["tool_call_id"]!!.jsonPrimitive.content)
    }

    @Test fun modelRefParsing() {
        assertEquals(ModelRef("openrouter", "nvidia/x:free"), ModelRef.parse("openrouter:nvidia/x:free"))
        assertNull(ModelRef.parse("nocolon")); assertNull(ModelRef.parse(":x")); assertNull(ModelRef.parse("x:"))
    }

    // ---- router -------------------------------------------------------------
    @Test fun fallsBackToNextModel() = runBlocking {
        val llm = ScriptedLlm(mutableListOf({ throw ProviderError("429", 429, true, 30.0) }, say("from b")))
        var switched: String? = null
        val router = testRouter(llm).also { it.onSwitch = { _, to, _ -> switched = to.toString() } }
        val (r, ref) = router.complete(Roles.CODER, listOf(ChatMessage.user("x")))
        assertEquals("from b", r.content); assertEquals("p:b", ref.toString()); assertEquals("p:b", switched)
        assertEquals(listOf("a", "b"), llm.seen.map { it.first })
    }

    @Test fun rateLimitedModelIsSkippedUntilCooldownEnds() = runBlocking {
        var t = 0L
        val llm = ScriptedLlm(mutableListOf({ throw ProviderError("429", 429, true, 30.0) }, say("b1"), say("b2"), say("a again")))
        val router = testRouter(llm, now = { t })
        router.complete(Roles.CODER, listOf(ChatMessage.user("x")))
        router.complete(Roles.CODER, listOf(ChatMessage.user("x")))
        assertEquals(listOf("a", "b", "b"), llm.seen.map { it.first })
        t = 31_000
        assertEquals("p:a", router.complete(Roles.CODER, listOf(ChatMessage.user("x"))).second.toString())
    }

    @Test fun skipSetMovesOffABadModel() = runBlocking {
        val llm = ScriptedLlm(mutableListOf(say("ok")))
        val (_, ref) = testRouter(llm).complete(Roles.CODER, listOf(ChatMessage.user("x")), skip = setOf(ModelRef("p", "a")))
        assertEquals("p:b", ref.toString())
    }

    @Test fun lastModelRetriesTransientErrors() = runBlocking {
        val chains = mapOf(Roles.CODER to listOf(ModelRef("p", "only")))
        val llm = ScriptedLlm(mutableListOf({ throw ProviderError("503", 503, true) }, say("fine")))
        assertEquals("fine", testRouter(llm, chains).complete(Roles.CODER, listOf(ChatMessage.user("x"))).first.content)
    }

    @Test fun everythingFailing() = runBlocking {
        val llm = ScriptedLlm(mutableListOf({ throw ProviderError("401 bad key", 401) }, { throw ProviderError("500", 500, true) }, { throw ProviderError("500", 500, true) }, { throw ProviderError("500", 500, true) }))
        val e = runCatching { testRouter(llm).complete(Roles.CODER, listOf(ChatMessage.user("x"))) }.exceptionOrNull()
        assertTrue(e is AllModelsFailed); assertTrue(e!!.message!!.contains("p:a"))
        val none = runCatching { testRouter(llm, emptyMap()).complete(Roles.CODER, emptyList()) }.exceptionOrNull()
        assertTrue(none!!.message!!.contains("Open Router"))
    }

    @Test fun unknownProviderIsSkipped() = runBlocking {
        val chains = mapOf(Roles.CODER to listOf(ModelRef("ghost", "m"), ModelRef("p", "b")))
        val (_, ref) = testRouter(ScriptedLlm(mutableListOf(say("ok"))), chains).complete(Roles.CODER, listOf(ChatMessage.user("x")))
        assertEquals("p:b", ref.toString())
    }
}
