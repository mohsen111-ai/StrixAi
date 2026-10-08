package dev.strix.app.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.StrokeCap

/**
 * The Strix emblem: a geometric owl on a 100x100 grid.
 * [draw] traces the outline (0..1), [eyes] fades the eyes in, [look] shifts the pupils (-1..1), [pulse] makes the eyes breathe.
 */
@Composable
fun OwlEmblem(
    modifier: Modifier = Modifier,
    draw: Float = 1f,
    eyes: Float = 1f,
    look: Float = 0f,
    pulse: Boolean = false,
    strokeUnits: Float = 3f,
) {
    val glow = if (pulse) {
        val t = rememberInfiniteTransition(label = "owl")
        t.animateFloat(0.55f, 1f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse), label = "eyes")
    } else null
    Canvas(modifier) {
        val s = size.minDimension / 100f
        val eyeAlpha = eyes * (glow?.value ?: 1f)
        drawOwl(s, draw, eyeAlpha, look, strokeUnits)
    }
}

private fun head(s: Float) = Path().apply {
    moveTo(18 * s, 30 * s); lineTo(28 * s, 12 * s); lineTo(42 * s, 26 * s); lineTo(58 * s, 26 * s)
    lineTo(72 * s, 12 * s); lineTo(82 * s, 30 * s); lineTo(82 * s, 72 * s); lineTo(50 * s, 94 * s); lineTo(18 * s, 72 * s); close()
}

private fun eyeL(s: Float) = Path().apply {
    moveTo(24 * s, 42 * s); lineTo(46 * s, 42 * s); lineTo(46 * s, 58 * s); lineTo(35 * s, 65 * s); lineTo(24 * s, 58 * s); close()
}

private fun eyeR(s: Float) = Path().apply {
    moveTo(76 * s, 42 * s); lineTo(54 * s, 42 * s); lineTo(54 * s, 58 * s); lineTo(65 * s, 65 * s); lineTo(76 * s, 58 * s); close()
}

internal fun DrawScope.drawOwl(s: Float, draw: Float, eyeAlpha: Float, look: Float, strokeUnits: Float) {
    val white = C.White
    val sw = strokeUnits * s
    // outline, traced progressively
    val outline = head(s)
    if (draw >= 0.999f) {
        drawPath(outline, white, style = Stroke(sw, join = StrokeJoin.Miter, miter = 10f))
    } else if (draw > 0f) {
        val pm = PathMeasure().also { it.setPath(outline, true) }
        val seg = Path()
        pm.getSegment(0f, pm.length * draw, seg, true)
        drawPath(seg, white, style = Stroke(sw, join = StrokeJoin.Miter, cap = StrokeCap.Butt))
    }
    if (draw < 0.6f) return
    val a = ((draw - 0.6f) / 0.4f).coerceIn(0f, 1f)
    // brow and chin chevrons
    val brow = Path().apply { moveTo(42 * s, 26 * s); lineTo(50 * s, 37 * s); lineTo(58 * s, 26 * s) }
    val chin = Path().apply { moveTo(38 * s, 81 * s); lineTo(50 * s, 88 * s); lineTo(62 * s, 81 * s) }
    drawPath(brow, C.Crimson.copy(alpha = a), style = Stroke(sw * 0.9f, join = StrokeJoin.Miter))
    drawPath(chin, C.Crimson.copy(alpha = a), style = Stroke(sw * 0.9f, join = StrokeJoin.Miter))
    // eyes: soft halo, then solid
    val e = eyeAlpha * a
    if (e > 0f) {
        for ((p, w) in listOf(eyeL(s) to 1f, eyeR(s) to 1f)) {
            drawPath(p, C.Crimson.copy(alpha = 0.16f * e), style = Stroke(11 * s * w, join = StrokeJoin.Miter))
            drawPath(p, C.Crimson.copy(alpha = 0.30f * e), style = Stroke(5 * s * w, join = StrokeJoin.Miter))
            drawPath(p, C.Crimson.copy(alpha = e))
        }
        val dx = look * 2.4f * s
        drawRect(white.copy(alpha = e), Offset(32 * s + dx, 47 * s), Size(6 * s, 10 * s))
        drawRect(white.copy(alpha = e), Offset(62 * s + dx, 47 * s), Size(6 * s, 10 * s))
    }
    // beak
    val beak = Path().apply { moveTo(50 * s, 60 * s); lineTo(45 * s, 71 * s); lineTo(50 * s, 78 * s); lineTo(55 * s, 71 * s); close() }
    drawPath(beak, white.copy(alpha = a))
}
