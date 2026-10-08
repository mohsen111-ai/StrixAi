package dev.strix.app.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** "provider:model-id". Model ids may contain ':' (":free"), so only the first ':' splits. */
@Serializable
data class ModelRef(val provider: String, val model: String) {
    override fun toString() = "$provider:$model"

    companion object {
        fun parse(s: String): ModelRef? {
            val i = s.indexOf(':')
            return if (i <= 0 || i == s.length - 1) null else ModelRef(s.substring(0, i).trim(), s.substring(i + 1).trim())
        }
    }
}

@Serializable
data class ProviderConfig(
    val id: String,
    val name: String,
    val baseUrl: String,
    val apiKey: String = "",
    val needsKey: Boolean = true,
    val headers: Map<String, String> = emptyMap(),
)

@Serializable
data class AgentSettings(
    val planning: Boolean = true,
    val maxSteps: Int = 40,
    val contextTokens: Int = 100_000,
    val temperature: Double = 0.2,
    val badCallsBeforeSwitch: Int = 3,
)

object Roles {
    const val PLANNER = "planner"
    const val CODER = "coder"
    const val FAST = "fast"
    val all = listOf(PLANNER, CODER, FAST)
    val blurb = mapOf(
        PLANNER to "Up-front plan for bigger tasks. Hard reasoning, no tools.",
        CODER to "The main tool loop: reads, edits and creates files.",
        FAST to "Commit messages and context summaries. Cheap and quick.",
    )

    /** Free-first so a new key works at no cost, then cheap paid models, then Gemini's separate free quota. */
    val defaults: Map<String, List<ModelRef>> = mapOf(
        PLANNER to listOf("openrouter:nvidia/nemotron-3-ultra-550b-a55b:free", "openrouter:deepseek/deepseek-v4-flash", "openrouter:moonshotai/kimi-k2-thinking", "gemini:gemini-3.5-flash-lite"),
        CODER to listOf("openrouter:poolside/laguna-s-2.1:free", "openrouter:nvidia/nemotron-3-ultra-550b-a55b:free", "openrouter:qwen/qwen3-coder-next", "openrouter:deepseek/deepseek-v4-flash", "openrouter:moonshotai/kimi-k2.6", "gemini:gemini-3.5-flash-lite"),
        FAST to listOf("openrouter:google/gemma-4-26b-a4b-it:free", "gemini:gemini-3.5-flash-lite", "openrouter:google/gemini-3.5-flash-lite"),
    ).mapValues { (_, v) -> v.mapNotNull { ModelRef.parse(it) } }
}

object Providers {
    const val OPENROUTER = "openrouter"
    const val GEMINI = "gemini"
    const val CUSTOM = "custom"

    fun defaults(): List<ProviderConfig> = listOf(
        ProviderConfig(OPENROUTER, "OpenRouter", "https://openrouter.ai/api/v1"),
        ProviderConfig(GEMINI, "Google AI Studio", "https://generativelanguage.googleapis.com/v1beta/openai"),
        ProviderConfig(CUSTOM, "Custom (OpenAI-compatible)", "", needsKey = false),
    )
}

@Serializable
data class ToolCall(val id: String, val name: String, val arguments: String)

@Serializable
data class ChatMessage(
    val role: String,
    val content: String? = null,
    val toolCalls: List<ToolCall> = emptyList(),
    val toolCallId: String? = null,
    val name: String? = null,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("role", role)
        when {
            role == "assistant" && toolCalls.isNotEmpty() -> {
                if (content.isNullOrBlank()) put("content", JsonNull) else put("content", content)
                put("tool_calls", buildJsonArray {
                    toolCalls.forEach { tc ->
                        add(buildJsonObject {
                            put("id", tc.id)
                            put("type", "function")
                            put("function", buildJsonObject { put("name", tc.name); put("arguments", tc.arguments) })
                        })
                    }
                })
            }
            role == "tool" -> {
                put("tool_call_id", toolCallId ?: "")
                if (name != null) put("name", name)
                put("content", content ?: "")
            }
            else -> put("content", content ?: "")
        }
    }

    companion object {
        fun system(t: String) = ChatMessage("system", t)
        fun user(t: String) = ChatMessage("user", t)
        fun assistant(t: String?, calls: List<ToolCall> = emptyList()) = ChatMessage("assistant", t, calls)
        fun tool(id: String, name: String, t: String) = ChatMessage("tool", t, toolCallId = id, name = name)
    }
}

data class LlmResponse(
    val content: String,
    val toolCalls: List<ToolCall> = emptyList(),
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val model: String = "",
)

class ProviderError(
    message: String,
    val status: Int? = null,
    val retryable: Boolean = false,
    val retryAfterSec: Double? = null,
) : Exception(message)

class AllModelsFailed(message: String) : Exception(message)

fun JsonArray.isEmptyArray() = this.size == 0
