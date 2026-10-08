package dev.strix.app

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.strix.app.core.AgentSettings
import dev.strix.app.core.GitHubApi
import dev.strix.app.core.ModelRef
import dev.strix.app.core.OpenAiCompatClient
import dev.strix.app.core.RepoInfo
import dev.strix.app.core.Roles
import dev.strix.app.core.Router
import dev.strix.app.core.newHttpClient
import dev.strix.app.data.ModelInfo
import dev.strix.app.data.PrefsStore
import dev.strix.app.data.Remote
import dev.strix.app.data.SessionData
import dev.strix.app.data.SessionStore
import dev.strix.app.data.SettingsStore
import dev.strix.app.data.secretStore
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

sealed interface Screen {
    data object Home : Screen
    data object Settings : Screen
    data object Models : Screen
    data object Session : Screen
    data object Diff : Screen
}

sealed interface Load<out T> {
    data object Idle : Load<Nothing>
    data object Busy : Load<Nothing>
    data class Done<T>(val value: T) : Load<T>
    data class Failed(val message: String) : Load<Nothing>
}

class AppViewModel(private val app: Application) : AndroidViewModel(app) {
    private val http = newHttpClient()
    val settings = SettingsStore(PrefsStore(app.getSharedPreferences("strix", 0)), secretStore(app))
    private val sessionStore = SessionStore(File(app.filesDir, "sessions"))
    val remote = Remote(http)
    val router = Router({ settings.roles }, { settings.providers() }, OpenAiCompatClient(http), { settings.agent.temperature })

    val stack = mutableStateListOf<Screen>(Screen.Home)
    val screen: Screen get() = stack.last()

    var repos by mutableStateOf<Load<List<RepoInfo>>>(Load.Idle); private set
    var recents by mutableStateOf<List<SessionData>>(emptyList()); private set
    var session by mutableStateOf<SessionController?>(null); private set
    var roles by mutableStateOf(settings.roles); private set
    var agent by mutableStateOf(settings.agent); private set
    var githubUser by mutableStateOf<String?>(null); private set
    var configured by mutableStateOf(settings.hasGithub && settings.hasAnyModelKey); private set

    private val controllers = HashMap<String, SessionController>()

    init {
        refreshRecents()
        if (settings.hasGithub) loadRepos()
    }

    fun go(s: Screen) { if (stack.last() != s) stack.add(s) }
    fun back(): Boolean { if (stack.size > 1) { stack.removeAt(stack.lastIndex); return true }; return false }

    fun refreshRecents() { recents = sessionStore.list() }

    fun refreshConfigured() { configured = settings.hasGithub && settings.hasAnyModelKey }

    fun loadRepos() {
        if (!settings.hasGithub) { repos = Load.Failed("Add your GitHub token in Settings first."); return }
        repos = Load.Busy
        viewModelScope.launch {
            repos = try {
                val api = GitHubApi(http, settings.githubToken)
                val all = ArrayList<RepoInfo>()
                for (page in 1..5) {   // up to 500 repos, newest push first
                    val chunk = api.listRepos(page)
                    all += chunk
                    if (chunk.size < 100) break
                }
                Load.Done(all.toList())
            } catch (e: Exception) {
                Load.Failed(e.message ?: "Could not load repositories")
            }
        }
    }

    suspend fun branchesOf(repo: String): Result<List<String>> = runCatching { GitHubApi(http, settings.githubToken).branches(repo) }

    fun open(repo: String, branch: String) {
        val data = SessionData(UUID.randomUUID().toString(), repo, branch, updatedAt = System.currentTimeMillis())
        attach(data)
    }

    fun resume(id: String) {
        val live = controllers[id]
        if (live != null) { session = live; go(Screen.Session); return }
        val data = sessionStore.load(id) ?: return
        attach(data)
    }

    fun deleteSession(id: String) {
        controllers.remove(id)?.stop()
        if (session?.id == id) session = null
        sessionStore.delete(id)
        refreshRecents()
    }

    private fun attach(data: SessionData) {
        val c = SessionController(
            data, GitHubApi(http, settings.githubToken), router, { settings.agent }, sessionStore, viewModelScope,
            onRunning = { AgentService.setRunning(app, it); if (!it) refreshRecents() },
        )
        controllers[data.id] = c
        session = c
        go(Screen.Session)
        viewModelScope.launch { c.load() }
    }

    fun retryLoad() { session?.let { c -> viewModelScope.launch { c.load() } } }

    /** Test hook: put the UI into a known state without touching the network. */
    internal fun seedForTest(repos: Load<List<RepoInfo>>? = null, recents: List<SessionData>? = null, session: SessionController? = null) {
        repos?.let { this.repos = it }
        recents?.let { this.recents = it }
        session?.let { this.session = it }
    }

    // ---- settings -------------------------------------------------------------
    fun saveRoles(r: Map<String, List<ModelRef>>) { settings.roles = r; roles = r }
    fun resetRoles() = saveRoles(Roles.defaults)
    fun saveAgent(a: AgentSettings) { settings.agent = a; agent = a }

    suspend fun verifyGithub(token: String): Result<String> = runCatching {
        GitHubApi(http, token.trim()).whoAmI().also { githubUser = it }
    }.map { "Signed in as $it" }.recoverCatching { throw Exception(it.message ?: "GitHub check failed") }

    suspend fun verifyOpenRouter(key: String) = remote.verifyOpenRouter(key.trim())
    suspend fun verifyGemini(key: String) = remote.verifyOpenAiCompatible("Gemini", "https://generativelanguage.googleapis.com/v1beta/openai", key.trim())
    suspend fun verifyCustom(url: String, key: String) = remote.verifyOpenAiCompatible("Endpoint", url.trim(), key.trim())
    suspend fun openRouterModels(): Result<List<ModelInfo>> = remote.openRouterModels()
}
