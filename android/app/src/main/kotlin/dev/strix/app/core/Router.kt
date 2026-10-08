package dev.strix.app.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray

/** Each role is a fallback chain of models. Rate limits, errors and repeated bad tool calls move to the next model. */
class Router(
    private val chains: () -> Map<String, List<ModelRef>>,
    private val providers: () -> Map<String, ProviderConfig>,
    private val client: LlmClient,
    private val temperature: () -> Double = { 0.2 },
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    var onSwitch: ((role: String, to: ModelRef, reason: String) -> Unit)? = null,
) {
    private val cooldownUntil = HashMap<String, Long>()
    private val last = HashMap<String, ModelRef>()
    var promptTokens = 0L; private set
    var completionTokens = 0L; private set
    var calls = 0; private set

    fun chain(role: String): List<ModelRef> = chains()[role].orEmpty()

    fun current(role: String): ModelRef? = last[role] ?: chain(role).firstOrNull()

    suspend fun complete(
        role: String,
        messages: List<ChatMessage>,
        tools: JsonArray? = null,
        skip: Set<ModelRef> = emptySet(),
    ): Pair<LlmResponse, ModelRef> {
        val all = chain(role)
        if (all.isEmpty()) throw AllModelsFailed("no models configured for role '$role'. Open Router and add one.")
        val chain = all.filter { it !in skip }.ifEmpty { all }
        val t = now()
        val ready = chain.filter { (cooldownUntil[it.toString()] ?: 0) <= t }
        val order = ready + chain.filter { it !in ready }
        val errors = ArrayList<String>()
        for ((idx, ref) in order.withIndex()) {
            val hasNext = idx < order.size - 1
            val attempts = if (hasNext) 1 else 3
            val provider = providers()[ref.provider]
            if (provider == null) {
                errors += "$ref: unknown provider"
                if (hasNext) onSwitch?.invoke(role, order[idx + 1], "unknown provider ${ref.provider}")
                continue
            }
            for (attempt in 0 until attempts) {
                try {
                    val resp = client.chat(provider, ref.model, messages, tools, temperature())
                    if (last[role] != ref && last.containsKey(role)) onSwitch?.invoke(role, ref, "recovered")
                    last[role] = ref
                    promptTokens += resp.promptTokens
                    completionTokens += resp.completionTokens
                    calls++
                    return resp to ref
                } catch (e: CancellationException) {
                    throw e
                } catch (e: ProviderError) {
                    errors += "$ref: ${e.message}"
                    when {
                        e.status == 429 -> cooldownUntil[ref.toString()] = now() + ((e.retryAfterSec ?: 45.0) * 1000).toLong()
                        e.retryable -> cooldownUntil[ref.toString()] = now() + 15_000
                        else -> cooldownUntil[ref.toString()] = now() + 60_000
                    }
                    if (e.retryable && !hasNext && attempt < attempts - 1) {
                        sleep(minOf(((e.retryAfterSec ?: (2.0 * (attempt + 1))) * 1000).toLong(), 10_000))
                        continue
                    }
                    if (hasNext) onSwitch?.invoke(role, order[idx + 1], (e.message ?: "error").take(90))
                    break
                }
            }
        }
        throw AllModelsFailed("every model for role '$role' failed:\n  " + errors.takeLast(6).joinToString("\n  "))
    }
}
