package dev.strix.app.core

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

interface LlmClient {
    suspend fun chat(
        provider: ProviderConfig,
        model: String,
        messages: List<ChatMessage>,
        tools: JsonArray?,
        temperature: Double,
    ): LlmResponse
}

val StrixJson = Json { ignoreUnknownKeys = true; encodeDefaults = true; isLenient = true }

fun newHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(20, TimeUnit.SECONDS)
    .readTimeout(180, TimeUnit.SECONDS)
    .writeTimeout(60, TimeUnit.SECONDS)
    .build()

suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) { if (cont.isActive) cont.resumeWithException(e) }
        override fun onResponse(call: Call, response: Response) { cont.resume(response) }
    })
}

/** One OpenAI-compatible chat client. OpenRouter, Gemini, Ollama, LM Studio and vLLM all speak this. */
class OpenAiCompatClient(private val http: OkHttpClient = newHttpClient()) : LlmClient {
    override suspend fun chat(
        provider: ProviderConfig,
        model: String,
        messages: List<ChatMessage>,
        tools: JsonArray?,
        temperature: Double,
    ): LlmResponse {
        if (provider.baseUrl.isBlank()) throw ProviderError("${provider.name}: no base URL set")
        if (provider.needsKey && provider.apiKey.isBlank()) throw ProviderError("${provider.name}: no API key set")
        val body = buildJsonObject {
            put("model", model)
            put("temperature", temperature)
            put("messages", buildJsonArray { messages.forEach { add(it.toJson()) } })
            if (tools != null && tools.isNotEmpty()) {
                put("tools", tools)
                put("tool_choice", "auto")
            }
        }
        val rb = Request.Builder()
            .url(provider.baseUrl.trimEnd('/') + "/chat/completions")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .header("Content-Type", "application/json")
        if (provider.apiKey.isNotBlank()) rb.header("Authorization", "Bearer ${provider.apiKey}")
        if (provider.baseUrl.contains("openrouter")) {
            rb.header("X-Title", "Strix")
            rb.header("HTTP-Referer", "https://github.com/mohsen111-ai/StrixAi")
        }
        provider.headers.forEach { (k, v) -> rb.header(k, v) }

        val text: String
        val code: Int
        val retryAfter: Double?
        try {
            http.newCall(rb.build()).await().use { r ->
                code = r.code
                retryAfter = r.header("retry-after")?.toDoubleOrNull()
                text = r.body?.string().orEmpty()
            }
        } catch (e: IOException) {
            throw ProviderError("network error: ${e.javaClass.simpleName}: ${e.message}", retryable = true)
        }
        if (code >= 400) {
            val retryable = code in setOf(408, 409, 425, 429) || code >= 500
            throw ProviderError("$code: ${text.take(300)}", code, retryable, retryAfter)
        }
        return parseResponse(text, model)
    }

    companion object {
        private val TEXT_CALL = Regex("<tool_call>\\s*(\\{.*?\\})\\s*</tool_call>", RegexOption.DOT_MATCHES_ALL)
        private val THINK = Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL)

        fun parseResponse(text: String, model: String): LlmResponse {
            val data: JsonObject = try {
                StrixJson.parseToJsonElement(text).jsonObject
            } catch (e: Exception) {
                throw ProviderError("provider returned non-JSON body", retryable = true)
            }
            val choices = data["choices"] as? JsonArray
            if (choices == null || choices.isEmpty()) {
                val err = data["error"] as? JsonObject
                val msg = err?.get("message")?.jsonPrimitive?.contentOrNull ?: text.take(200)
                val status = err?.get("code")?.jsonPrimitive?.intOrNull
                throw ProviderError("empty response: $msg", status, retryable = true)
            }
            val message = choices[0].jsonObject["message"] as? JsonObject ?: JsonObject(emptyMap())
            var content = contentOf(message["content"])
            val calls = (message["tool_calls"] as? JsonArray).orEmpty().mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                val fn = o["function"] as? JsonObject ?: return@mapNotNull null
                val name = fn["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val args = when (val a = fn["arguments"]) {
                    null -> "{}"
                    is JsonPrimitive -> a.contentOrNull?.ifBlank { "{}" } ?: "{}"
                    else -> a.toString()
                }
                ToolCall(o["id"]?.jsonPrimitive?.contentOrNull?.ifBlank { null } ?: newCallId(), name, args)
            }.toMutableList()
            if (calls.isEmpty()) {
                calls += textToolCalls(content)
                if (calls.isNotEmpty()) content = TEXT_CALL.replace(content, "").trim()
            }
            content = THINK.replace(content, "").trim()
            if (content.isBlank() && calls.isEmpty()) throw ProviderError("model returned an empty message", retryable = true)
            val usage = data["usage"] as? JsonObject
            return LlmResponse(
                content,
                calls,
                usage?.get("prompt_tokens")?.jsonPrimitive?.intOrNull ?: 0,
                usage?.get("completion_tokens")?.jsonPrimitive?.intOrNull ?: 0,
                data["model"]?.jsonPrimitive?.contentOrNull ?: model,
            )
        }

        private fun contentOf(el: JsonElement?): String = when (el) {
            null -> ""
            is JsonArray -> el.joinToString("") { (it as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull.orEmpty() }
            is JsonPrimitive -> if (el.isString) el.content else ""
            else -> ""
        }

        /** Some open models print tool calls as <tool_call>{...}</tool_call> text instead of using the API field. */
        fun textToolCalls(content: String): List<ToolCall> = TEXT_CALL.findAll(content).mapNotNull { m ->
            try {
                val o = StrixJson.parseToJsonElement(m.groupValues[1]).jsonObject
                val name = o["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val args = o["arguments"] ?: o["parameters"] ?: JsonObject(emptyMap())
                ToolCall(newCallId(), name, if (args is JsonPrimitive && args.isString) args.content else args.toString())
            } catch (e: Exception) {
                null
            }
        }.toList()

        fun newCallId() = "call_" + UUID.randomUUID().toString().replace("-", "").take(8)
    }
}
