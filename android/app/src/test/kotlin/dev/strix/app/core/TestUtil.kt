package dev.strix.app.core

import kotlinx.serialization.json.JsonArray

fun snap(vararg files: Pair<String, String>, others: Set<String> = emptySet()) = Snapshot(files.toMap(), others, false)

fun ws(vararg files: Pair<String, String>) = Workspace("me/app", "main", "sha0", snap(*files))

/** A scripted model: each call pops the next reply. */
class ScriptedLlm(private val replies: MutableList<() -> LlmResponse>) : LlmClient {
    val seen = mutableListOf<Pair<String, List<ChatMessage>>>()
    override suspend fun chat(provider: ProviderConfig, model: String, messages: List<ChatMessage>, tools: JsonArray?, temperature: Double): LlmResponse {
        seen += model to messages.toList()
        if (replies.isEmpty()) throw ProviderError("script exhausted", retryable = false)
        return replies.removeAt(0)()
    }
}

fun say(text: String) = { LlmResponse(text) }
fun call(name: String, args: String, id: String = "c${System.nanoTime()}") = { LlmResponse("", listOf(ToolCall(id, name, args))) }
fun calls(vararg c: Triple<String, String, String>) = { LlmResponse("", c.map { ToolCall(it.third, it.first, it.second) }) }

fun testRouter(client: LlmClient, chains: Map<String, List<ModelRef>> = mapOf(
    Roles.CODER to listOf(ModelRef("p", "a"), ModelRef("p", "b")),
    Roles.PLANNER to listOf(ModelRef("p", "plan")),
    Roles.FAST to listOf(ModelRef("p", "fast")),
), now: () -> Long = { 0L }) = Router(
    { chains },
    { mapOf("p" to ProviderConfig("p", "P", "http://x", "key")) },
    client,
    now = now,
    sleep = { },
)
