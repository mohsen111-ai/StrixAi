package dev.strix.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.strix.app.AppViewModel
import dev.strix.app.core.ModelRef
import dev.strix.app.core.Providers
import dev.strix.app.core.Roles
import dev.strix.app.data.ModelInfo

@Composable
fun ModelsScreen(vm: AppViewModel) {
    var adding by remember { mutableStateOf<String?>(null) }
    val roles = vm.roles
    val providers = vm.settings.providers()

    fun update(role: String, list: List<ModelRef>) = vm.saveRoles(roles + (role to list))

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            IconBtn(Ico.Back, "Back", { vm.back() })
            Column(Modifier.weight(1f)) {
                Text("MODEL ROUTER", style = T.Title.copy(fontSize = 20.sp))
                Text("Each role is a fallback chain: first model first.", style = T.Small)
            }
            Chip("RESET", onClick = { vm.resetRoles() })
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            items(Roles.all, key = { it }) { role ->
                val chain = roles[role].orEmpty()
                Panel(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Label(role)
                        Text(Roles.blurb[role].orEmpty(), style = T.Small)
                        if (chain.isEmpty()) Text("No models: this role will fail until you add one.", style = T.Small.copy(color = C.Crimson))
                        chain.forEachIndexed { i, ref ->
                            val hasKey = providers[ref.provider]?.let { !it.needsKey || it.apiKey.isNotBlank() || (it.id == Providers.CUSTOM && it.baseUrl.isNotBlank()) } ?: false
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("${i + 1}", style = T.Code.copy(color = C.Crimson), modifier = Modifier.padding(end = 2.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(ref.model, style = T.Code, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text(ref.provider, style = T.CodeSmall)
                                        if (!hasKey) Text("NO KEY", style = T.Label.copy(color = C.Crimson))
                                    }
                                }
                                IconBtn(Ico.Up, "Move up", { if (i > 0) update(role, chain.toMutableList().also { it.add(i - 1, it.removeAt(i)) }) }, enabled = i > 0)
                                IconBtn(Ico.Down, "Move down", { if (i < chain.lastIndex) update(role, chain.toMutableList().also { it.add(i + 1, it.removeAt(i)) }) }, enabled = i < chain.lastIndex)
                                IconBtn(Ico.Close, "Remove", { update(role, chain - ref) }, tint = C.Crimson)
                            }
                        }
                        NeonButton("Add model", { adding = role }, primary = false, icon = Ico.Plus)
                    }
                }
            }
        }
    }
    adding?.let { role ->
        ModelPicker(vm, onDismiss = { adding = null }) { ref ->
            val chain = roles[role].orEmpty()
            if (ref !in chain) vm.saveRoles(roles + (role to chain + ref))
            adding = null
        }
    }
}

@Composable
private fun ModelPicker(vm: AppViewModel, onDismiss: () -> Unit, onPick: (ModelRef) -> Unit) {
    var tab by remember { mutableStateOf(0) }
    var models by remember { mutableStateOf<List<ModelInfo>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var freeOnly by remember { mutableStateOf(true) }
    var toolsOnly by remember { mutableStateOf(true) }
    var provider by remember { mutableStateOf(Providers.OPENROUTER) }
    var manual by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        vm.openRouterModels().onSuccess { models = it }.onFailure { error = it.message }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Panel(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(12.dp), accent = true, fill = C.Bg2) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Label("Add a model", Modifier.weight(1f))
                    IconBtn(Ico.Close, "Close", onDismiss)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("OPENROUTER CATALOGUE", active = tab == 0, onClick = { tab = 0 })
                    Chip("TYPE AN ID", active = tab == 1, onClick = { tab = 1 })
                }
                if (tab == 0) {
                    StrixField(query, { query = it }, placeholder = "Search: qwen, kimi, deepseek, gemini…")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Chip("FREE ONLY", active = freeOnly, onClick = { freeOnly = !freeOnly })
                        Chip("TOOL CALLS", active = toolsOnly, onClick = { toolsOnly = !toolsOnly })
                    }
                    val list = models
                    when {
                        error != null -> Text("Couldn't load the catalogue: $error", style = T.Small.copy(color = C.Crimson))
                        list == null -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { OwlEmblem(Modifier.size(48.dp), pulse = true) }
                        else -> {
                            val shown = list.filter { (!freeOnly || it.free) && (!toolsOnly || it.tools) && (query.isBlank() || it.id.contains(query.trim(), true) || it.name.contains(query.trim(), true)) }
                            Text("${shown.size} models  ·  coding agents need tool calling", style = T.Small)
                            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                items(shown.take(150), key = { it.id }) { m ->
                                    Row(
                                        Modifier.fillMaxWidth().pressable(true, { onPick(ModelRef(Providers.OPENROUTER, m.id)) }).padding(vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Column(Modifier.weight(1f)) {
                                            Text(m.name, style = T.Body, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text(m.id + if (m.contextLength > 0) "  ·  ${m.contextLength / 1000}k" else "", style = T.CodeSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                        if (m.free) Chip("FREE", active = true)
                                        Icon(Ico.Plus, null, Modifier.size(16.dp), tint = C.Crimson)
                                    }
                                }
                            }
                        }
                    }
                } else {
                    Text("Use any provider you have set up. Gemini example: gemini-3.5-flash-lite. Custom: whatever your endpoint calls the model.", style = T.Small)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(Providers.OPENROUTER, Providers.GEMINI, Providers.CUSTOM).forEach { p -> Chip(p.uppercase(), active = provider == p, onClick = { provider = p }) }
                    }
                    StrixField(manual, { manual = it.trim() }, label = "Model id", placeholder = "e.g. qwen/qwen3-coder", mono = true)
                    NeonButton("Add", { onPick(ModelRef(provider, manual)) }, enabled = manual.isNotBlank(), icon = Ico.Plus)
                }
            }
        }
    }
}
