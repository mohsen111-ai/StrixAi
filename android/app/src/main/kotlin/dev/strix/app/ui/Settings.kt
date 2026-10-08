package dev.strix.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.strix.app.AppViewModel
import dev.strix.app.BuildConfig
import dev.strix.app.Screen
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(vm: AppViewModel) {
    val s = vm.settings
    var gh by remember { mutableStateOf(s.githubToken) }
    var orKey by remember { mutableStateOf(s.openrouterKey) }
    var gmKey by remember { mutableStateOf(s.geminiKey) }
    var cUrl by remember { mutableStateOf(s.customUrl) }
    var cKey by remember { mutableStateOf(s.customKey) }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            IconBtn(Ico.Back, "Back", { vm.refreshConfigured(); vm.back() })
            Text("SETTINGS", style = T.Title.copy(fontSize = 20.sp))
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Section("GitHub") {
                Help("Create a token with access to your repos. Fine-grained: Contents and Pull requests set to Read and write. Or a classic token with the repo scope.")
                LinkRow("Create fine-grained token", "https://github.com/settings/personal-access-tokens/new")
                LinkRow("Create classic token (repo scope)", "https://github.com/settings/tokens/new?scopes=repo&description=Strix")
                KeyRow("Token", gh, { gh = it; s.githubToken = it; vm.refreshConfigured() }, "ghp_… or github_pat_…") { vm.verifyGithub(it).also { r -> if (r.isSuccess) vm.loadRepos() } }
            }
            Section("Models") {
                Help("One key is enough to start. OpenRouter has free models, but free ones allow only about 50 requests a day until you add a few dollars of credit (then about 1000/day, plus cheap paid models). Gemini has its own free quota, so adding both gives the most headroom.")
                LinkRow("Get an OpenRouter key", "https://openrouter.ai/keys")
                KeyRow("OpenRouter key", orKey, { orKey = it; s.openrouterKey = it; vm.refreshConfigured() }, "sk-or-…") { vm.verifyOpenRouter(it) }
                LinkRow("Get a Gemini key", "https://aistudio.google.com/apikey")
                KeyRow("Gemini key", gmKey, { gmKey = it; s.geminiKey = it; vm.refreshConfigured() }, "AIza…") { vm.verifyGemini(it) }
                Help("Optional: any OpenAI-compatible endpoint (a LiteLLM gateway, Ollama on another machine, DeepSeek or Moonshot directly). Use it in the router as custom:model-name.")
                StrixField(cUrl, { cUrl = it; s.customUrl = it; vm.refreshConfigured() }, label = "Custom base URL", placeholder = "https://host/v1", mono = true)
                KeyRow("Custom key (optional)", cKey, { cKey = it; s.customKey = it }, "") { vm.verifyCustom(cUrl, it) }
                NeonButton("Choose models per role", { vm.go(Screen.Models) }, primary = false, icon = Ico.Right)
            }
            Section("Agent") {
                val a = vm.agent
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StrixSwitch(a.planning, { vm.saveAgent(a.copy(planning = it)) })
                    Column { Text("Plan first", style = T.Body); Text("A planning model outlines bigger tasks before the coder starts.", style = T.Small) }
                }
                Label("Max steps per task: ${a.maxSteps}", accent = false)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf(20, 40, 60, 100).forEach { Chip("$it", active = a.maxSteps == it, onClick = { vm.saveAgent(a.copy(maxSteps = it)) }) } }
                Label("Context budget: ${a.contextTokens / 1000}k tokens", accent = false)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf(32_000, 64_000, 100_000, 200_000).forEach { Chip("${it / 1000}k", active = a.contextTokens == it, onClick = { vm.saveAgent(a.copy(contextTokens = it)) }) } }
                Help("Older tool output is trimmed, then summarised, when a conversation passes 70% of this budget. Set it to your smallest model's window.")
            }
            Section("About") {
                Text("Strix ${BuildConfig.VERSION_NAME}", style = T.Body)
                Help("Keys are stored encrypted on this phone and are only sent to the provider you chose. Repo contents go to the models you configure. Free endpoints may log prompts, so keep private code on paid or local models.")
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Panel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Label(title)
            content()
        }
    }
}

@Composable
private fun Help(text: String) = Text(text, style = T.Small)

@Composable
private fun LinkRow(text: String, url: String) {
    val uri = LocalUriHandler.current
    Row(Modifier.pressable(true, { uri.openUri(url) }), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Ico.Open, null, Modifier.size(14.dp), tint = C.Crimson)
        Text(text, style = T.Small.copy(color = C.White))
    }
}

/** A secret field with a show/hide toggle and a live "does this key work" check. */
@Composable
private fun KeyRow(label: String, value: String, onChange: (String) -> Unit, hint: String, verify: suspend (String) -> Result<String>) {
    var show by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<Result<String>?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        StrixField(
            value, { onChange(it); status = null }, label = label, placeholder = hint, secret = !show, mono = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailing = { Chip(if (show) "HIDE" else "SHOW", onClick = { show = !show }) },
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            NeonButton(if (busy) "Checking…" else "Verify", {
                busy = true
                scope.launch { status = verify(value); busy = false }
            }, primary = false, enabled = !busy && value.isNotBlank())
            status?.let { r ->
                Text(r.fold({ "✓ $it" }, { it.message ?: "failed" }), style = T.Small.copy(color = if (r.isSuccess) C.White else C.Crimson), modifier = Modifier.weight(1f))
            }
        }
    }
}
