package dev.strix.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

sealed interface MdBlock {
    data class Para(val text: String) : MdBlock
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Bullet(val marker: String, val text: String) : MdBlock
    data class Code(val lang: String, val code: String) : MdBlock
}

fun parseMarkdown(src: String): List<MdBlock> {
    val out = ArrayList<MdBlock>()
    val lines = src.replace("\r\n", "\n").split("\n")
    var i = 0
    val para = StringBuilder()
    fun flush() { if (para.isNotBlank()) out += MdBlock.Para(para.toString().trim()); para.clear() }
    while (i < lines.size) {
        val line = lines[i]
        val t = line.trimStart()
        when {
            t.startsWith("```") -> {
                flush()
                val lang = t.removePrefix("```").trim()
                val code = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith("```")) { code.append(lines[i]).append('\n'); i++ }
                out += MdBlock.Code(lang, code.toString().trimEnd('\n'))
            }
            Regex("^#{1,4}\\s+.+").matches(t) -> { flush(); out += MdBlock.Heading(t.takeWhile { it == '#' }.length, t.trimStart('#').trim()) }
            Regex("^[-*•]\\s+.+").matches(t) -> { flush(); out += MdBlock.Bullet("•", t.drop(1).trim()) }
            Regex("^\\d+[.)]\\s+.+").matches(t) -> { flush(); out += MdBlock.Bullet(t.takeWhile { it.isDigit() } + ".", t.dropWhile { it.isDigit() }.drop(1).trim()) }
            line.isBlank() -> flush()
            else -> { if (para.isNotEmpty()) para.append(' '); para.append(line.trim()) }
        }
        i++
    }
    flush()
    return out
}

/** `code`, **bold** and [label](url) (label only). */
fun inlineMarkdown(text: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < text.length) {
        val c = text[i]
        when {
            c == '`' -> {
                val end = text.indexOf('`', i + 1)
                if (end > i) {
                    withStyle(SpanStyle(fontFamily = Mono, background = C.Panel2, color = C.White)) { append(" " + text.substring(i + 1, end) + " ") }
                    i = end + 1; continue
                }
            }
            c == '*' && text.startsWith("**", i) -> {
                val end = text.indexOf("**", i + 2)
                if (end > i + 2) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(text.substring(i + 2, end)) }
                    i = end + 2; continue
                }
            }
            c == '[' -> {
                val m = Regex("^\\[([^\\]]+)]\\(([^)]+)\\)").find(text.substring(i))
                if (m != null) {
                    withStyle(SpanStyle(color = C.Crimson)) { append(m.groupValues[1]) }
                    i += m.value.length; continue
                }
            }
        }
        append(c); i++
    }
}

@Composable
fun Markdown(text: String, modifier: Modifier = Modifier) {
    val blocks = remember(text) { parseMarkdown(text) }
    SelectionContainer {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            blocks.forEach { b ->
                when (b) {
                    is MdBlock.Para -> Text(inlineMarkdown(b.text), style = T.Body)
                    is MdBlock.Heading -> Text(inlineMarkdown(b.text), style = T.Heading.copy(color = if (b.level == 1) C.Crimson else C.White))
                    is MdBlock.Bullet -> Row(Modifier.padding(start = 4.dp)) {
                        Text(b.marker, style = T.Body.copy(color = C.Crimson), modifier = Modifier.width(22.dp))
                        Text(inlineMarkdown(b.text), style = T.Body)
                    }
                    is MdBlock.Code -> CodeBlock(b.lang, b.code)
                }
            }
        }
    }
}

@Composable
fun CodeBlock(lang: String, code: String) {
    val clip = LocalClipboardManager.current
    Column(
        Modifier.fillMaxWidth().clip(cutTiny()).background(C.Bg2).border(1.dp, C.Line, cutTiny()),
    ) {
        Row(Modifier.fillMaxWidth().background(C.Panel2).padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(lang.ifBlank { "code" }.uppercase(), style = T.Label)
            Box(Modifier.weight(1f))
            Chip("COPY", onClick = { clip.setText(AnnotatedString(code)) })
        }
        Box(Modifier.horizontalScroll(rememberScrollState()).padding(10.dp)) { Text(code, style = T.Code) }
    }
}
