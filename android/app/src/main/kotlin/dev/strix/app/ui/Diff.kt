package dev.strix.app.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.strix.app.AppViewModel
import dev.strix.app.ChangeKindLabel
import dev.strix.app.CommitOutcome
import dev.strix.app.SessionController
import dev.strix.app.core.ChangeKind
import dev.strix.app.core.DiffKind
import dev.strix.app.core.StagedChange
import kotlinx.coroutines.launch

@Composable
fun DiffScreen(vm: AppViewModel, c: SessionController) {
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    var committing by remember { mutableStateOf(false) }
    var confirmRevertAll by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            IconBtn(Ico.Back, "Back", { vm.back() })
            Column(Modifier.weight(1f)) {
                Text("REVIEW CHANGES", style = T.Heading)
                Text("${c.changes.size} files  ·  +${c.changes.sumOf { it.diff.added }}  −${c.changes.sumOf { it.diff.removed }}", style = T.CodeSmall)
            }
        }
        if (c.changes.isEmpty()) {
            Column(Modifier.weight(1f).fillMaxWidth(), Arrangement.Center, Alignment.CenterHorizontally) {
                OwlEmblem(Modifier.size(72.dp))
                Text("NOTHING STAGED", style = T.Label, modifier = Modifier.padding(top = 12.dp))
            }
        } else LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(12.dp, 0.dp, 12.dp, 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(c.changes, key = { it.path }) { ch -> FileCard(ch, expanded[ch.path] == true, { expanded[ch.path] = expanded[ch.path] != true }, { c.revert(ch.path) }, !c.running) }
        }
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            NeonButton("Revert all", { confirmRevertAll = true }, primary = false, enabled = c.changes.isNotEmpty() && !c.running, icon = Ico.Trash)
            NeonButton(if (c.running) "Agent busy…" else "Commit", { committing = true }, Modifier.weight(1f), enabled = c.changes.isNotEmpty() && !c.running, icon = Ico.Branch, pulse = c.changes.isNotEmpty() && !c.running)
        }
    }
    if (committing) CommitDialog(c) { committing = false }
    if (confirmRevertAll) Dialog(onDismissRequest = { confirmRevertAll = false }) {
        Panel(Modifier.fillMaxWidth(), accent = true, fill = C.Panel) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Label("Revert everything?")
                Text("All ${c.changes.size} staged files go back to the repo version. This can't be undone.", style = T.BodyMute)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    NeonButton("Keep", { confirmRevertAll = false }, primary = false)
                    NeonButton("Revert all", { c.revertAll(); confirmRevertAll = false }, icon = Ico.Trash)
                }
            }
        }
    }
}

@Composable
private fun FileCard(ch: StagedChange, open: Boolean, toggle: () -> Unit, revert: () -> Unit, canRevert: Boolean) {
    val d = ch.diff
    Panel(Modifier.fillMaxWidth()) {
        Column {
            Row(Modifier.fillMaxWidth().pressable(true, toggle).padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip(ChangeKindLabel.of(ch.kind), active = ch.kind != ChangeKind.MODIFIED)
                Text(ch.path, style = T.Code, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text("+${d.added}", style = T.CodeSmall.copy(color = C.Add))
                Text("−${d.removed}", style = T.CodeSmall.copy(color = C.Crimson))
                Icon(if (open) Ico.Up else Ico.Down, null, Modifier.size(16.dp), tint = C.Mute)
            }
            if (open) {
                Divider()
                val shown = d.lines.take(MAX_LINES)
                Column(Modifier.fillMaxWidth().background(C.Bg2)) {
                    shown.forEach { l ->
                        val bg = when (l.kind) { DiffKind.ADD -> C.AddBg; DiffKind.DEL -> C.DelBg; DiffKind.HUNK -> C.Panel2; else -> androidx.compose.ui.graphics.Color.Transparent }
                        val fg = when (l.kind) { DiffKind.ADD -> C.Add; DiffKind.DEL -> C.Crimson; DiffKind.HUNK -> C.Faint; else -> C.Mute }
                        Row(Modifier.fillMaxWidth().background(bg).padding(horizontal = 6.dp)) {
                            Text(((l.newNo ?: l.oldNo)?.toString() ?: "").padStart(4), style = T.CodeSmall.copy(color = C.Faint), modifier = Modifier.width(34.dp))
                            Text(when (l.kind) { DiffKind.ADD -> "+ "; DiffKind.DEL -> "− "; DiffKind.CTX -> "  "; DiffKind.HUNK -> "" } + l.text, style = T.CodeSmall.copy(color = fg))
                        }
                    }
                    if (d.lines.size > MAX_LINES) Text("… ${d.lines.size - MAX_LINES} more lines", style = T.CodeSmall, modifier = Modifier.padding(8.dp))
                }
                Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.End) {
                    NeonButton("Revert file", revert, primary = false, enabled = canRevert, icon = Ico.Trash)
                }
            }
        }
    }
}

private const val MAX_LINES = 400

@Composable
private fun CommitDialog(c: SessionController, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf("") }
    var suggesting by remember { mutableStateOf(true) }
    val fixedBranch = c.workBranch
    var branch by remember { mutableStateOf(fixedBranch ?: c.defaultBranchName()) }
    var direct by remember { mutableStateOf(false) }
    var openPr by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<CommitOutcome?>(null) }
    val uri = LocalUriHandler.current

    LaunchedEffect(Unit) {
        val m = c.suggestCommitMessage()
        if (message.isBlank()) message = m
        suggesting = false
    }

    Dialog(onDismissRequest = { if (!c.committing) onClose() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Panel(Modifier.fillMaxWidth().padding(16.dp), accent = true, fill = C.Panel) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val r = result
                if (r != null) {
                    Label("Committed")
                    Text("Pushed to ${r.branch}.", style = T.Body)
                    NeonButton("View commit", { uri.openUri(r.commitUrl) }, primary = false, icon = Ico.Open)
                    r.prUrl?.let { NeonButton("Open pull request", { uri.openUri(it) }, icon = Ico.Open) }
                    r.prError?.let { Text("The pull request couldn't be opened: $it", style = T.Small.copy(color = C.Crimson)) }
                    NeonButton("Done", onClose, primary = false)
                } else {
                    Label("Commit ${c.changes.size} files")
                    StrixField(message, { message = it }, label = "Message", placeholder = if (suggesting) "Writing a message…" else "Commit message", singleLine = false, maxLines = 6)
                    if (fixedBranch != null) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Ico.Branch, null, Modifier.size(16.dp), tint = C.Crimson)
                            Text("Continuing on ", style = T.Small); Text(fixedBranch, style = T.Code)
                        }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Chip("NEW BRANCH", active = !direct, onClick = { direct = false })
                            Chip("COMMIT TO ${c.baseBranch.uppercase()}", active = direct, onClick = { direct = true; openPr = false })
                        }
                        if (!direct) StrixField(branch, { branch = it.replace(' ', '-') }, label = "Branch", mono = true)
                        else Text("Pushes straight to ${c.baseBranch}. No pull request.", style = T.Small.copy(color = C.Crimson))
                    }
                    if (!direct || fixedBranch != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        StrixSwitch(openPr, { openPr = it })
                        Text("Open a pull request", style = T.Body)
                    }
                    error?.let { Text(it, style = T.Small.copy(color = C.Crimson)) }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        NeonButton("Cancel", onClose, primary = false, enabled = !c.committing)
                        NeonButton(if (c.committing) "Pushing…" else "Commit & push", {
                            error = null
                            scope.launch {
                                c.commit(message, if (direct) c.baseBranch else branch, openPr)
                                    .onSuccess { result = it }
                                    .onFailure { error = it.message ?: "Commit failed" }
                            }
                        }, enabled = !c.committing && message.isNotBlank() && (fixedBranch != null || direct || branch.isNotBlank()), icon = Ico.Branch, pulse = c.committing)
                    }
                }
            }
        }
    }
}
