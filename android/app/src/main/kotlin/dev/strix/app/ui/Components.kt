package dev.strix.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val Snap = CubicBezierEasing(0.2f, 0.9f, 0.1f, 1f)

/** Obsidian backdrop: faint tech grid, laser-cut diagonals, crimson corner glow and a slow scan line. */
@Composable
fun StrixBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val t = rememberInfiniteTransition(label = "bg")
    val scan by t.animateFloat(0f, 1f, infiniteRepeatable(tween(9000, easing = LinearEasing)), label = "scan")
    Box(
        modifier
            .fillMaxSize()
            .background(C.Bg)
            .drawBehind {
                val step = 28.dp.toPx()
                val thin = 1f
                var x = 0f
                var i = 0
                while (x < size.width) {
                    drawLine(Color.White.copy(alpha = if (i % 4 == 0) 0.07f else 0.035f), Offset(x, 0f), Offset(x, size.height), thin)
                    x += step; i++
                }
                var y = 0f
                i = 0
                while (y < size.height) {
                    drawLine(Color.White.copy(alpha = if (i % 4 == 0) 0.07f else 0.035f), Offset(0f, y), Offset(size.width, y), thin)
                    y += step; i++
                }
                drawRect(Brush.radialGradient(listOf(C.Crimson.copy(alpha = 0.20f), Color.Transparent), Offset(0f, 0f), size.width * 0.9f))
                drawRect(Brush.radialGradient(listOf(C.Crimson.copy(alpha = 0.10f), Color.Transparent), Offset(size.width, size.height), size.width * 0.8f))
                // laser-cut diagonals
                val d = size.height
                drawLine(C.Crimson.copy(alpha = 0.22f), Offset(size.width * 0.55f, 0f), Offset(size.width * 0.55f + d * 0.6f, d), 1.2f)
                drawLine(C.Crimson.copy(alpha = 0.12f), Offset(size.width * 0.30f, 0f), Offset(size.width * 0.30f + d * 0.6f, d), 1f)
                drawLine(Color.White.copy(alpha = 0.06f), Offset(size.width * 0.85f, 0f), Offset(size.width * 0.85f + d * 0.6f, d), 1f)
                // scan line
                val sy = size.height * scan
                drawRect(Brush.verticalGradient(listOf(Color.Transparent, C.Crimson.copy(alpha = 0.10f), Color.Transparent), sy - 60f, sy + 60f), Offset(0f, sy - 60f), Size(size.width, 120f))
            },
        content = content,
    )
}

/** Neon pulse: a breathing crimson outline, used on whatever is active. */
fun Modifier.neonPulse(active: Boolean, shape: Shape = cut(), width: Dp = 1.5.dp): Modifier =
    if (!active) this else composed {
        val t = rememberInfiniteTransition(label = "pulse")
        val a by t.animateFloat(0.35f, 1f, infiniteRepeatable(tween(1000, easing = LinearEasing), RepeatMode.Reverse), label = "a")
        drawBehind {
            val outline = shape.createOutline(size, layoutDirection, this)
            if (outline is Outline.Generic) {
                drawPath(outline.path, C.Crimson.copy(alpha = 0.14f * a), style = Stroke(width.toPx() * 5))
                drawPath(outline.path, C.Crimson.copy(alpha = 0.30f * a), style = Stroke(width.toPx() * 2.6f))
                drawPath(outline.path, C.Crimson.copy(alpha = a), style = Stroke(width.toPx()))
            }
        }
    }

/** Press feedback: instant scale-down, spring back. */
fun Modifier.pressable(enabled: Boolean = true, onClick: () -> Unit, onLongClick: (() -> Unit)? = null): Modifier = composed {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed) 0.965f else 1f, spring(Spring.DampingRatioNoBouncy, Spring.StiffnessHigh), label = "press")
    this
        .scale(s)
        .clickable(src, null, enabled = enabled, role = Role.Button, onClick = onClick)
}

@Composable
fun Panel(
    modifier: Modifier = Modifier,
    accent: Boolean = false,
    fill: Color = C.Panel.copy(alpha = 0.92f),
    shape: Shape = cut(),
    pulse: Boolean = false,
    content: @Composable () -> Unit,
) {
    Box(
        modifier
            .neonPulse(pulse, shape)
            .clip(shape)
            .background(fill)
            .border(BorderStroke(1.dp, if (accent) C.Crimson else C.Line), shape),
    ) { content() }
}

@Composable
fun NeonButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = true,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    pulse: Boolean = false,
) {
    val shape = cut(9.dp)
    val bg by animateColorAsState(if (!enabled) C.Panel2 else if (primary) C.Crimson else Color.Transparent, tween(120, easing = Snap), label = "bg")
    Row(
        modifier
            .pressable(enabled, onClick)
            .neonPulse(pulse && enabled, shape)
            .clip(shape)
            .background(bg)
            .border(1.dp, if (!enabled) C.Line else if (primary) C.Crimson else C.Line2, shape)
            .defaultMinSize(minHeight = 44.dp)
            .padding(horizontal = 18.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        if (icon != null) Icon(icon, null, Modifier.size(16.dp), tint = if (enabled) C.White else C.Faint)
        androidx.compose.material3.Text(label.uppercase(), style = T.Button.copy(color = if (enabled) C.White else C.Faint))
    }
}

@Composable
fun IconBtn(icon: ImageVector, desc: String, onClick: () -> Unit, modifier: Modifier = Modifier, tint: Color = C.White, enabled: Boolean = true, accent: Boolean = false) {
    Box(
        modifier
            .size(40.dp)
            .pressable(enabled, onClick)
            .clip(cutTiny())
            .background(if (accent) C.Crimson else Color.Transparent)
            .border(1.dp, if (accent) C.Crimson else C.Line, cutTiny()),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, desc, Modifier.size(20.dp), tint = if (enabled) tint else C.Faint) }
}

@Composable
fun Label(text: String, modifier: Modifier = Modifier, accent: Boolean = true) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (accent) Box(Modifier.size(7.dp).background(C.Crimson))
        androidx.compose.material3.Text(text.uppercase(), style = T.Label)
    }
}

@Composable
fun Chip(text: String, modifier: Modifier = Modifier, active: Boolean = false, onClick: (() -> Unit)? = null, color: Color = C.Mute) {
    val shape = cutTiny()
    Box(
        modifier
            .then(if (onClick != null) Modifier.pressable(true, onClick) else Modifier)
            .clip(shape)
            .background(if (active) C.Crimson.copy(alpha = 0.18f) else Color.Transparent)
            .border(1.dp, if (active) C.Crimson else C.Line2, shape)
            .padding(horizontal = 9.dp, vertical = 4.dp),
    ) { androidx.compose.material3.Text(text, style = T.Label.copy(color = if (active) C.White else color, letterSpacing = 0.8.sp)) }
}

@Composable
fun StrixField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    label: String? = null,
    secret: Boolean = false,
    singleLine: Boolean = true,
    mono: Boolean = false,
    minHeight: Dp = 48.dp,
    maxLines: Int = if (singleLine) 1 else 8,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    trailing: @Composable (() -> Unit)? = null,
) {
    val src = remember { MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    val line by animateFloatAsState(if (focused) 1f else 0f, tween(180, easing = Snap), label = "focus")
    val style = (if (mono) T.Code else T.Body).copy(color = C.White)
    Column(modifier) {
        if (label != null) { Label(label); Box(Modifier.height(6.dp)) }
        Box(
            Modifier
                .fillMaxWidth()
                .clip(cutTiny())
                .background(C.Panel2.copy(alpha = 0.9f))
                .border(1.dp, if (focused) C.Crimson.copy(alpha = 0.6f) else C.Line, cutTiny())
                .drawBehind { drawRect(C.Crimson, Offset(0f, size.height - 2.dp.toPx()), Size(size.width * line, 2.dp.toPx())) }
                .heightIn(min = minHeight)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    BasicTextField(
                        value, onValueChange,
                        Modifier.fillMaxWidth(),
                        textStyle = style,
                        singleLine = singleLine,
                        maxLines = maxLines,
                        cursorBrush = SolidColor(C.Crimson),
                        visualTransformation = if (secret) PasswordVisualTransformation('•') else VisualTransformation.None,
                        keyboardOptions = keyboardOptions,
                        keyboardActions = keyboardActions,
                        interactionSource = src,
                        decorationBox = { inner ->
                            if (value.isEmpty()) androidx.compose.material3.Text(placeholder, style = style.copy(color = C.Faint))
                            inner()
                        },
                    )
                }
                trailing?.invoke()
            }
        }
    }
}

@Composable
fun StrixSwitch(checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val x by animateFloatAsState(if (checked) 1f else 0f, tween(140, easing = Snap), label = "sw")
    val track by animateColorAsState(if (checked) C.Crimson.copy(alpha = 0.25f) else Color.Transparent, tween(140), label = "tr")
    Box(
        modifier
            .width(46.dp).height(24.dp)
            .pressable(true, { onChange(!checked) })
            .clip(cutTiny())
            .background(track)
            .border(1.dp, if (checked) C.Crimson else C.Line2, cutTiny()),
    ) {
        Box(
            Modifier
                .padding(start = 3.dp + 22.dp * x, top = 3.dp)
                .size(width = 18.dp, height = 18.dp)
                .background(if (checked) C.Crimson else C.Mute),
        )
    }
}

@Composable
fun Divider(modifier: Modifier = Modifier) = Box(modifier.fillMaxWidth().height(1.dp).background(C.Line))

@Composable
fun AppText(text: String, style: TextStyle = T.Body, modifier: Modifier = Modifier, color: Color = Color.Unspecified, maxLines: Int = Int.MAX_VALUE) =
    androidx.compose.material3.Text(text, modifier, color = color, style = style, maxLines = maxLines, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
