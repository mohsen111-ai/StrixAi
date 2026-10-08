package dev.strix.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** Hand-drawn geometric icons (24x24, mitered strokes) so the app needs no icon library. */
object Ico {
    private fun stroke(name: String, d: String, fill: Boolean = false): ImageVector = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        addPath(
            pathData = addPathNodes(d),
            fill = if (fill) SolidColor(Color.White) else null,
            stroke = if (fill) null else SolidColor(Color.White),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Butt,
            strokeLineJoin = StrokeJoin.Miter,
        )
    }.build()

    val Back = stroke("back", "M15 5 L8 12 L15 19")
    val Gear = stroke("gear", "M12 3 L19.8 7.5 L19.8 16.5 L12 21 L4.2 16.5 L4.2 7.5 Z M9.5 9.5 H14.5 V14.5 H9.5 Z")
    val Send = stroke("send", "M4 12 L20 4 L14 20 L11 13 Z M11 13 L20 4")
    val Stop = stroke("stop", "M7 7 H17 V17 H7 Z", fill = true)
    val Plus = stroke("plus", "M12 5 V19 M5 12 H19")
    val Close = stroke("close", "M6 6 L18 18 M18 6 L6 18")
    val Check = stroke("check", "M5 12 L10 17 L19 7")
    val Down = stroke("down", "M6 9 L12 15 L18 9")
    val Up = stroke("up", "M6 15 L12 9 L18 15")
    val Right = stroke("right", "M9 6 L15 12 L9 18")
    val Branch = stroke("branch", "M7 4 V20 M7 14 H11 L17 8 V4")
    val Diff = stroke("diff", "M12 4 V12 M8 8 H16 M8 18 H16")
    val Lock = stroke("lock", "M6 11 H18 V20 H6 Z M9 11 V8 L12 5 L15 8 V11")
    val Trash = stroke("trash", "M5 7 H19 M9 7 V4 H15 V7 M7 7 V20 H17 V7")
    val Open = stroke("open", "M10 5 H5 V19 H19 V14 M13 5 H19 V11 M19 5 L11 13")
    val Refresh = stroke("refresh", "M19 8 A8 8 0 1 0 20 13 M19 3 V8 H14")
    val Eye = stroke("eye", "M2 12 L7 6 H17 L22 12 L17 18 H7 Z M10 10 H14 V14 H10 Z")
    val Search = stroke("search", "M4 4 H15 V15 H4 Z M15 15 L21 21")
}
