package dev.strix.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dev.strix.app.core.AgentSettings
import dev.strix.app.core.ChatMessage
import dev.strix.app.core.ModelRef
import dev.strix.app.core.OverlayEntry
import dev.strix.app.core.ProviderConfig
import dev.strix.app.core.Providers
import dev.strix.app.core.Roles
import dev.strix.app.core.StrixJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File

interface KeyValueStore {
    fun get(key: String): String?
    fun put(key: String, value: String?)
}

class MemoryStore : KeyValueStore {
    private val m = HashMap<String, String>()
    override fun get(key: String) = m[key]
    override fun put(key: String, value: String?) { if (value == null) m.remove(key) else m[key] = value }
}

class PrefsStore(private val prefs: SharedPreferences) : KeyValueStore {
    override fun get(key: String): String? = prefs.getString(key, null)
    override fun put(key: String, value: String?) { prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply() }
}

/** API keys and the GitHub token live in Keystore-backed encrypted prefs. If the device's keystore misbehaves, fall back to private app storage rather than crash. */
fun secretStore(ctx: Context): KeyValueStore = try {
    val key = MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
    PrefsStore(EncryptedSharedPreferences.create(ctx, "strix_secrets", key,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV, EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM))
} catch (e: Exception) {
    PrefsStore(ctx.getSharedPreferences("strix_secrets_plain", Context.MODE_PRIVATE))
}

/** Everything the user configures. Secrets go to [secrets], the rest to [plain]. */
class SettingsStore(private val plain: KeyValueStore, private val secrets: KeyValueStore) {
    var githubToken: String get() = secrets.get(K_GH).orEmpty(); set(v) = secrets.put(K_GH, v.trim().ifEmpty { null })
    var openrouterKey: String get() = secrets.get(K_OR).orEmpty(); set(v) = secrets.put(K_OR, v.trim().ifEmpty { null })
    var geminiKey: String get() = secrets.get(K_GM).orEmpty(); set(v) = secrets.put(K_GM, v.trim().ifEmpty { null })
    var customKey: String get() = secrets.get(K_CK).orEmpty(); set(v) = secrets.put(K_CK, v.trim().ifEmpty { null })
    var customUrl: String get() = plain.get(K_CU).orEmpty(); set(v) = plain.put(K_CU, v.trim().ifEmpty { null })

    var roles: Map<String, List<ModelRef>>
        get() = plain.get(K_ROLES)?.let { runCatching { StrixJson.decodeFromString(rolesSer, it) }.getOrNull() }
            ?.let { saved -> Roles.all.associateWith { saved[it] ?: Roles.defaults[it].orEmpty() } }
            ?: Roles.defaults
        set(v) = plain.put(K_ROLES, StrixJson.encodeToString(rolesSer, v))

    var agent: AgentSettings
        get() = plain.get(K_AGENT)?.let { runCatching { StrixJson.decodeFromString(AgentSettings.serializer(), it) }.getOrNull() } ?: AgentSettings()
        set(v) = plain.put(K_AGENT, StrixJson.encodeToString(AgentSettings.serializer(), v))

    fun providers(): Map<String, ProviderConfig> = Providers.defaults().map {
        when (it.id) {
            Providers.OPENROUTER -> it.copy(apiKey = openrouterKey)
            Providers.GEMINI -> it.copy(apiKey = geminiKey)
            else -> it.copy(baseUrl = customUrl, apiKey = customKey)
        }
    }.associateBy { it.id }

    val hasGithub get() = githubToken.isNotBlank()
    val hasAnyModelKey get() = openrouterKey.isNotBlank() || geminiKey.isNotBlank() || (customUrl.isNotBlank())

    private companion object {
        const val K_GH = "github_token"; const val K_OR = "openrouter_key"; const val K_GM = "gemini_key"
        const val K_CK = "custom_key"; const val K_CU = "custom_url"; const val K_ROLES = "roles"; const val K_AGENT = "agent"
        val rolesSer = MapSerializer(String.serializer(), ListSerializer(ModelRef.serializer()))
    }
}

@Serializable
data class TranscriptItem(
    val kind: String,          // user say plan tool info error link todos
    val text: String = "",
    val extra: String = "",    // tool name / model / url
    val ok: Boolean = true,
    val id: String = "",
    val running: Boolean = false,
    val detail: String = "",
    val summary: String = "",
)

@Serializable
data class SessionData(
    val id: String,
    val repo: String,
    val baseBranch: String,
    val workBranch: String? = null,
    val title: String = "",
    val updatedAt: Long = 0,
    val messages: List<ChatMessage> = emptyList(),
    val items: List<TranscriptItem> = emptyList(),
    val overlay: List<OverlayEntry> = emptyList(),
)

/** One JSON file per session in app-private storage. */
class SessionStore(private val dir: File) {
    init { dir.mkdirs() }
    private fun file(id: String) = File(dir, "$id.json")

    fun save(d: SessionData) {
        val tmp = File(dir, "${d.id}.tmp")
        tmp.writeText(StrixJson.encodeToString(SessionData.serializer(), d))
        if (!tmp.renameTo(file(d.id))) { file(d.id).writeText(tmp.readText()); tmp.delete() }
    }

    fun load(id: String): SessionData? = runCatching { StrixJson.decodeFromString(SessionData.serializer(), file(id).readText()) }.getOrNull()

    fun list(): List<SessionData> = (dir.listFiles { f -> f.extension == "json" } ?: emptyArray())
        .mapNotNull { f -> runCatching { StrixJson.decodeFromString(SessionData.serializer(), f.readText()) }.getOrNull() }
        .sortedByDescending { it.updatedAt }

    fun delete(id: String) { file(id).delete() }
}
