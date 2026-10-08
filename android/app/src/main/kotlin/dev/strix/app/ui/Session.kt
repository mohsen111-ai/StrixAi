package dev.strix.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.strix.app.AppViewModel
import dev.strix.app.Phase
import dev.strix.app.Screen
import dev.strix.app.SessionController
import dev.strix.app.data.TranscriptItem

fun shortModel(ref: String?): String {
    if (ref == null) return "no model"
    val m = ref.substringAfter(':').substringAfterLast('/')
    return m.removeSuffix(":free").let { if (ref.endsWith(":free")) "$it·free" else it }
}

@Composable
fun SessionScreen(vm: AppViewModel, c: SessionController) {
    val ctx = LocalContext.current
    var draft by rememberSaveable { mutableStateOf("") }
    var askedNotif by rememberSaveable { mutableStateOf(false) }
    val notif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val listState = rememberLazyListState()

    fun send() {
        if (draft.isBlank() || c.running || c.phase != Phase.Ready) return
        if (Build.VERSION.SDK_INT >= 33 && !askedNotif &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) { askedNotif = true; notif.launch(Manifest.permission.POST_NOTIFICATIONS) }
        c.send(draft)
        draft = ""
    }

    LaunchedEffect(c.items.size, c.running) {
        if (c.items.isNotEmpty()) listState.animateScrollToItem(c.items.lastIndex + 1)
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
        TopBar(vm, c)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (val p = c.phase) {
                Phase.Loading -> Column(Modifier.fillMaxSize(), Arrangement.Center, Alignment.CenterHorizontally) {
                    OwlEmblem(Modifier.size(84.dp), pulse = true, look = 0f)
                    Text("DOWNLOADING ${c.repo.uppercase()}", style = T.Label, modifier = Modifier.padding(top = 16.dp))
                }
                is Phase.Failed -> Column(Modifier.fillMaxSize().padding(24.dp), Arrangement.Center, Alignment.CenterHorizontally, ) {
                    Panel(Modifier.fillMaxWidth(), accent = true) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Label("Couldn't open the repo")
                            Text(p.message, style = T.Body)
                            NeonButton("Retry", { vm.retryLoad() }, icon = Ico.Refresh)
                        }
                    }
                }
                Phase.Ready -> Transcript(c, listState) { draft = it }
            }
        }
        if (c.changes.isNotEmpty()) ChangesStrip(c) { vm.go(Screen.Diff) }
        InputBar(draft, { draft = it }, c.running, c.phase == Phase.Ready, ::send, c::stop)
    }
}

@Composable
private fun TopBar(vm: AppViewModel, c: SessionController) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        IconBtn(Ico.Back, "Back", { vm.back() })
        Column(Modifier.weight(1f)) {
            Text(c.repo, style = T.Heading, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Ico.Branch, null, Modifier.size(13.dp), tint = C.Crimson)
                Text(c.workBranch ?: c.baseBranch, style = T.CodeSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Box(Modifier.neonPulse(c.running, cutTiny(), 1.dp)) { Chip(shortModel(c.coderModel), active = c.running, onClick = { vm.go(Screen.Models) }) }
    }
}

@Composable
private fun Transcript(c: SessionController, state: LazyListState, onSuggest: (String) -> Unit) {
    LazyColumn(
        Modifier.fillMaxSize(),
        state = state,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp, 4.dp, 16.dp, 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (c.items.isEmpty()) item("empty") { EmptyState(onSuggest) }
        if (c.todos.isNotEmpty()) item("todos") { TodoCard(c) }
        itemsIndexed(c.items, key = { i, it -> if (it.id.isNotEmpty()) it.id else "$i" }) { _, it -> Entry(it) }
        if (c.running) item("working") { WorkingRow() }
    }
}

@Composable
private fun EmptyState(onSuggest: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 36.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        OwlEmblem(Modifier.size(96.dp), pulse = true, strokeUnits = 3.4f)
        Text("WHAT SHOULD WE BUILD?", style = T.Heading)
        Text("I read the repo, edit across files, and stage everything for your review. Nothing is pushed until you commit.", style = T.BodyMute, modifier = Modifier.padding(horizontal = 12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            listOf("Explain how this project is structured", "Find and fix bugs in the main module", "Add a README section explaining how to run it", "Write tests for the core logic").forEach { s ->
                Chip(s, onClick = { onSuggest(s) })
            }
        }
    }
}

@Composable
private fun TodoCard(c: SessionController) {
    var open by remember { mutableStateOf(true) }
    Panel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth().pressable(true, { open = !open }), verticalAlignment = Alignment.CenterVertically) {
                Label("Checklist  ${c.todos.count { it.done }}/${c.todos.size}", Modifier.weight(1f))
                Icon(if (open) Ico.Up else Ico.Down, null, Modifier.size(18.dp), tint = C.Mute)
            }
            AnimatedVisibility(open, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    c.todos.forEach { t ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                            Box(Modifier.padding(top = 3.dp).size(14.dp).clip(cutTiny()).background(if (t.done) C.Crimson else C.Line)) {
                                if (t.done) Icon(Ico.Check, null, Modifier.size(14.dp), tint = C.White)
                            }
                            Text(t.text, style = T.Small.copy(color = if (t.done) C.Faint else C.White))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Entry(it: TranscriptItem) {
    val uri = LocalUriHandler.current
    when (it.kind) {
        "user" -> Row(Modifier.fillMaxWidth().height(androidx.compose.foundation.layout.IntrinsicSize.Min)) {
            Box(Modifier.width(3.dp).fillMaxSize().background(C.Crimson))
            Column(Modifier.padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("YOU", style = T.Label.copy(color = C.Crimson))
                Text(it.text, style = T.Body)
            }
        }
        "say" -> Row(Modifier.fillMaxWidth().height(androidx.compose.foundation.layout.IntrinsicSize.Min)) {
            Box(Modifier.width(3.dp).fillMaxSize().background(C.Line2))
            Column(Modifier.padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("STRIX  ·  ${shortModel(it.extra)}", style = T.Label)
                Markdown(it.text)
            }
        }
        "plan" -> Panel(Modifier.fillMaxWidth(), accent = true) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Label("Plan  ·  ${shortModel(it.extra)}")
                Markdown(it.text)
            }
        }
        "tool" -> ToolRow(it)
        "error" -> Panel(Modifier.fillMaxWidth(), accent = true, fill = C.CrimsonDim.copy(alpha = 0.25f)) {
            Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Ico.Close, null, Modifier.size(16.dp), tint = C.Crimson)
                Text(it.text, style = T.Small.copy(color = C.White))
            }
        }
        "link" -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
            NeonButton(it.text, { uri.openUri(it.extra) }, primary = false, icon = Ico.Open)
        }
        else -> Text("//  ${it.text}", style = T.CodeSmall.copy(color = C.Faint))
    }
}

@Composable
private fun ToolRow(it: TranscriptItem) {
    var open by remember { mutableStateOf(false) }
    val tint = when { it.running -> C.Crimson; it.ok -> C.Mute; else -> C.Crimson }
    Column(Modifier.fillMaxWidth().clip(cutTiny()).background(C.Panel.copy(alpha = 0.8f)).pressable(it.detail.isNotEmpty(), { open = !open })) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(14.dp).neonPulse(it.running, androidx.compose.ui.graphics.RectangleShape, 1.dp), contentAlignment = Alignment.Center) {
                when {
                    it.running -> Box(Modifier.size(6.dp).background(C.Crimson))
                    it.ok -> Icon(Ico.Check, null, Modifier.size(14.dp), tint = tint)
                    else -> Icon(Ico.Close, null, Modifier.size(14.dp), tint = tint)
                }
            }
            Text(it.extra.uppercase(), style = T.Label.copy(color = if (it.ok) C.White else C.Crimson))
            Text(it.text, style = T.CodeSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (it.summary.isNotEmpty() && !it.running) Text(it.summary.take(28), style = T.CodeSmall.copy(color = C.Faint), maxLines = 1)
        }
        AnimatedVisibility(open && it.detail.isNotEmpty()) {
            Box(Modifier.fillMaxWidth().background(C.Bg2).padding(10.dp)) { Text(it.detail, style = T.CodeSmall.copy(color = C.Mute), maxLines = 24, overflow = TextOverflow.Ellipsis) }
        }
    }
}

@Composable
private fun WorkingRow() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OwlEmblem(Modifier.size(22.dp), pulse = true, strokeUnits = 5f)
        Text("WORKING", style = T.Label.copy(color = C.Crimson))
    }
}

@Composable
private fun ChangesStrip(c: SessionController, onReview: () -> Unit) {
    val add = c.changes.sumOf { it.diff.added }
    val del = c.changes.sumOf { it.diff.removed }
    Panel(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 6.dp).pressable(true, onReview), accent = true, fill = C.Panel2) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Ico.Diff, null, Modifier.size(18.dp), tint = C.Crimson)
            Text("${c.changes.size} file${if (c.changes.size == 1) "" else "s"} staged", style = T.Body.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold))
            Text("+$add", style = T.Code.copy(color = C.Add))
            Text("−$del", style = T.Code.copy(color = C.Crimson))
            Box(Modifier.weight(1f))
            Text("REVIEW", style = T.Label.copy(color = C.White))
            Icon(Ico.Right, null, Modifier.size(16.dp), tint = C.Crimson)
        }
    }
}

@Composable
private fun InputBar(text: String, onText: (String) -> Unit, running: Boolean, ready: Boolean, onSend: () -> Unit, onStop: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.weight(1f).neonPulse(running, cutTiny(), 1.dp)) {
            StrixField(
                text, onText, Modifier.fillMaxWidth(),
                placeholder = if (running) "Working… tap stop to interrupt" else "Tell Strix what to build or fix",
                singleLine = false, maxLines = 5,
            )
        }
        if (running) IconBtn(Ico.Stop, "Stop", onStop, accent = true, modifier = Modifier.size(48.dp))
        else IconBtn(Ico.Send, "Send", onSend, accent = text.isNotBlank() && ready, enabled = text.isNotBlank() && ready, modifier = Modifier.size(48.dp))
    }
}
