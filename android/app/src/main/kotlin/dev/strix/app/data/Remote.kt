package dev.strix.app.data

import dev.strix.app.core.StrixJson
import dev.strix.app.core.await
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

data class ModelInfo(val id: String, val name: String, val contextLength: Int, val free: Boolean, val tools: Boolean)

/** Key checks and the live model catalogue. Each takes its base URL so tests can point at a mock server. */
class Remote(private val http: OkHttpClient) {
    private suspend fun get(url: String, key: String?): Pair<Int, String> = try {
        val rb = Request.Builder().url(url).header("User-Agent", "Strix-Android")
        if (!key.isNullOrBlank()) rb.header("Authorization", "Bearer $key")
        http.newCall(rb.build()).await().use { it.code to it.body?.string().orEmpty() }
    } catch (e: IOException) {
        -1 to (e.message ?: "network error")
    } catch (e: IllegalArgumentException) {
        -1 to "that URL is not valid"
    }

    private fun fail(code: Int, body: String, what: String): Result<String> = Result.failure(
        Exception(when (code) {
            -1 -> "Can't reach $what: $body"
            401, 403 -> "$what rejected the key ($code)"
            else -> "$what answered $code: ${body.take(120)}"
        }),
    )

    suspend fun verifyOpenRouter(key: String, base: String = "https://openrouter.ai/api/v1"): Result<String> {
        if (key.isBlank()) return Result.failure(Exception("Paste a key first"))
        val (code, body) = get("$base/auth/key", key)
        if (code != 200) return fail(code, body, "OpenRouter")
        val d = runCatching { StrixJson.parseToJsonElement(body).jsonObject["data"]?.jsonObject }.getOrNull()
        val label = d?.get("label")?.jsonPrimitive?.contentOrNull
        val free = d?.get("is_free_tier")?.jsonPrimitive?.booleanOrNull
        return Result.success("OpenRouter key works" + (label?.let { " ($it)" } ?: "") + if (free == true) ", free tier" else "")
    }

    suspend fun verifyOpenAiCompatible(name: String, base: String, key: String): Result<String> {
        if (base.isBlank()) return Result.failure(Exception("Enter the base URL first"))
        val (code, body) = get(base.trimEnd('/') + "/models", key)
        if (code != 200) return fail(code, body, name)
        return Result.success("$name key works")
    }

    suspend fun openRouterModels(base: String = "https://openrouter.ai/api/v1"): Result<List<ModelInfo>> {
        val (code, body) = get("$base/models", null)
        if (code != 200) return fail(code, body, "OpenRouter").map { emptyList() }
        return runCatching {
            parseModels(body)
        }.fold({ Result.success(it) }, { Result.failure(Exception("Unexpected model list from OpenRouter")) })
    }

    companion object {
        fun parseModels(body: String): List<ModelInfo> {
            val arr = StrixJson.parseToJsonElement(body).jsonObject["data"] as JsonArray
            return arr.mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                val id = o["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val pricing = o["pricing"] as? JsonObject
                val free = id.endsWith(":free") ||
                    (pricing?.get("prompt")?.jsonPrimitive?.doubleOrNull == 0.0 && pricing["completion"]?.jsonPrimitive?.doubleOrNull == 0.0)
                val params = (o["supported_parameters"] as? JsonArray)?.map { it.jsonPrimitive.content }.orEmpty()
                ModelInfo(
                    id, o["name"]?.jsonPrimitive?.contentOrNull ?: id,
                    o["context_length"]?.jsonPrimitive?.intOrNull ?: 0, free, "tools" in params,
                )
            }.sortedWith(compareByDescending<ModelInfo> { it.free }.thenBy { it.id })
        }
    }
}
