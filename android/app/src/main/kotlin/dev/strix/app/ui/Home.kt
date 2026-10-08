package dev.strix.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
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
import androidx.compose.ui.window.Dialog
import dev.strix.app.AppViewModel
import dev.strix.app.Load
import dev.strix.app.Screen
import dev.strix.app.core.RepoInfo
import dev.strix.app.data.SessionData

fun ago(ms: Long, now: Long = System.currentTimeMillis()): String {
    val s = ((now - ms) / 1000).coerceAtLeast(0)
    return when {
        s < 60 -> "just now"
        s < 3600 -> "${s / 60}m ago"
        s < 86400 -> "${s / 3600}h ago"
        else -> "${s / 86400}d ago"
    }
}

@Composable
fun HomeScreen(vm: AppViewModel) {
    var query by remember { mutableStateOf("") }
    var picking by remember { mutableStateOf<RepoInfo?>(null) }
    LazyColumn(
        Modifier.fillMaxSize().systemBarsPadding(),
        contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 40.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OwlEmblem(Modifier.size(40.dp), strokeUnits = 4f)
                Column(Modifier.weight(1f)) {
                    Text("STRIX", style = T.Title)
                    Text("AUTONOMOUS CODING AGENT", style = T.Label)
                }
                IconBtn(Ico.Gear, "Settings", { vm.go(Screen.Settings) })
            }
        }
        if (!vm.configured) item {
            Panel(Modifier.fillMaxWidth(), accent = true, pulse = true) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Label("Setup needed")
                    Text("Add a GitHub token and one model key (a free OpenRouter key works). Keys stay on this phone, encrypted.", style = T.BodyMute)
                    NeonButton("Open settings", { vm.go(Screen.Settings) }, icon = Ico.Gear)
                }
            }
        }
        if (vm.recents.isNotEmpty()) {
            item { Label("Recent sessions", Modifier.padding(top = 8.dp)) }
            items(vm.recents.take(8), key = { it.id }) { s -> SessionRow(s, onOpen = { vm.resume(s.id) }, onDelete = { vm.deleteSession(s.id) }) }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Label("Your repositories", Modifier.weight(1f))
                IconBtn(Ico.Refresh, "Refresh repositories", { vm.loadRepos() })
            }
        }
        item { StrixField(query, { query = it }, placeholder = "Search repositories") }
        when (val r = vm.repos) {
            Load.Idle -> Unit
            Load.Busy -> item { Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { OwlEmblem(Modifier.size(56.dp), pulse = true) } }
            is Load.Failed -> item {
                Panel(Modifier.fillMaxWidth(), accent = true) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(r.message, style = T.Body.copy(color = C.White))
                        NeonButton("Retry", { vm.loadRepos() }, primary = false)
                    }
                }
            }
            is Load.Done -> {
                val shown = r.value.filter { query.isBlank() || it.fullName.contains(query.trim(), ignoreCase = true) }
                if (shown.isEmpty()) item { Text(if (r.value.isEmpty()) "No repositories found for this token." else "Nothing matches.", style = T.Small, modifier = Modifier.padding(8.dp)) }
                items(shown, key = { it.fullName }) { repo -> RepoRow(repo) { picking = repo } }
            }
        }
    }
    picking?.let { repo -> BranchDialog(vm, repo, onDismiss = { picking = null }) }
}

@Composable
private fun SessionRow(s: SessionData, onOpen: () -> Unit, onDelete: () -> Unit) {
    Panel(Modifier.fillMaxWidth().pressable(true, onOpen)) {
        Row(Modifier.padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(s.repo, style = T.Heading, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(s.title.ifBlank { "New session" }, style = T.Small, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Chip(s.workBranch ?: s.baseBranch)
                    if (s.overlay.isNotEmpty()) Chip("${s.overlay.size} staged", active = true)
                    Text(ago(s.updatedAt), style = T.Small.copy(color = C.Faint), modifier = Modifier.padding(top = 3.dp))
                }
            }
            IconBtn(Ico.Close, "Delete session", onDelete, tint = C.Faint)
        }
    }
}

@Composable
private fun RepoRow(r: RepoInfo, onClick: () -> Unit) {
    Panel(Modifier.fillMaxWidth().pressable(true, onClick)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (r.isPrivate) Icon(Ico.Lock, "private", Modifier.size(14.dp), tint = C.Mute)
                    Text(r.fullName, style = T.Heading, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (r.description.isNotBlank()) Text(r.description, style = T.Small, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Icon(Ico.Right, null, Modifier.size(18.dp), tint = C.Crimson)
        }
    }
}

@Composable
private fun BranchDialog(vm: AppViewModel, repo: RepoInfo, onDismiss: () -> Unit) {
    var branches by remember { mutableStateOf<List<String>>(listOf(repo.defaultBranch)) }
    var chosen by remember { mutableStateOf(repo.defaultBranch) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(repo.fullName) {
        vm.branchesOf(repo.fullName).onSuccess { list ->
            branches = (listOf(repo.defaultBranch) + list.filter { it != repo.defaultBranch })
        }.onFailure { error = it.message }
    }
    Dialog(onDismissRequest = onDismiss) {
        Panel(Modifier.fillMaxWidth(), accent = true, fill = C.Panel) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Label("Start session")
                Text(repo.fullName, style = T.Heading)
                Text("Base branch", style = T.Small)
                LazyColumn(Modifier.heightIn(max = 220.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(branches, key = { it }) { b ->
                        Row(
                            Modifier.fillMaxWidth().pressable(true, { chosen = b }).padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Box(Modifier.size(10.dp).let { if (chosen == b) it.neonPulse(true) else it })
                            Chip(b, active = chosen == b)
                            if (b == repo.defaultBranch) Text("default", style = T.Small.copy(color = C.Faint))
                        }
                    }
                }
                error?.let { Text("Couldn't list branches: $it", style = T.Small.copy(color = C.Crimson)) }
                Text("Your edits go to a new branch. Nothing is pushed until you commit.", style = T.Small)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    NeonButton("Cancel", onDismiss, primary = false)
                    NeonButton("Start", { vm.open(repo.fullName, chosen); onDismiss() }, icon = Ico.Right)
                }
            }
        }
    }
}
