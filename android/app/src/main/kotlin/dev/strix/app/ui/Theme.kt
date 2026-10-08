@file:OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)

package dev.strix.app.ui

import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.Typography
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.strix.app.R

/** Strix palette: obsidian surfaces, electric crimson accents, stark white. */
object C {
    val Bg = Color(0xFF05060A)
    val Bg2 = Color(0xFF0A0C14)
    val Panel = Color(0xFF0C0F18)
    val Panel2 = Color(0xFF111522)
    val Crimson = Color(0xFFFF1744)
    val CrimsonDim = Color(0xFF7A0C22)
    val White = Color(0xFFFFFFFF)
    val Mute = Color(0x9EFFFFFF)
    val Faint = Color(0x61FFFFFF)
    val Line = Color(0x1AFFFFFF)
    val Line2 = Color(0x38FFFFFF)
    val Add = Color(0xFF2BD98A)
    val AddBg = Color(0x1A2BD98A)
    val DelBg = Color(0x1FFF1744)
}

val Display = FontFamily(
    Font(R.font.chakra_petch_regular, FontWeight.Normal),
    Font(R.font.chakra_petch_medium, FontWeight.Medium),
    Font(R.font.chakra_petch_semibold, FontWeight.SemiBold),
    Font(R.font.chakra_petch_bold, FontWeight.Bold),
)

val Mono = FontFamily(
    Font(R.font.jetbrains_mono, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.jetbrains_mono, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.jetbrains_mono, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
)

object T {
    val Body = TextStyle(fontFamily = Display, fontSize = 15.sp, lineHeight = 22.sp, color = C.White)
    val BodyMute = Body.copy(color = C.Mute)
    val Small = TextStyle(fontFamily = Display, fontSize = 13.sp, lineHeight = 18.sp, color = C.Mute)
    val Label = TextStyle(fontFamily = Display, fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.6.sp, color = C.Mute)
    val Title = TextStyle(fontFamily = Display, fontSize = 22.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp, color = C.White)
    val Heading = TextStyle(fontFamily = Display, fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold, color = C.White)
    val Button = TextStyle(fontFamily = Display, fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp, color = C.White)
    val Code = TextStyle(fontFamily = Mono, fontSize = 12.sp, lineHeight = 18.sp, color = C.White)
    val CodeSmall = TextStyle(fontFamily = Mono, fontSize = 11.sp, lineHeight = 16.sp, color = C.Mute)
}

fun cut(size: Dp = 10.dp) = CutCornerShape(topStart = 0.dp, topEnd = size, bottomEnd = 0.dp, bottomStart = size)
fun cutTiny() = cut(6.dp)

private val scheme = darkColorScheme(
    primary = C.Crimson, onPrimary = C.White, secondary = C.Crimson, background = C.Bg, onBackground = C.White,
    surface = C.Panel, onSurface = C.White, surfaceVariant = C.Panel2, onSurfaceVariant = C.Mute, outline = C.Line2, error = C.Crimson,
)

@Composable
fun StrixTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = Typography()) {
        CompositionLocalProvider(
            LocalTextSelectionColors provides TextSelectionColors(C.Crimson, C.Crimson.copy(alpha = 0.35f)),
            content = content,
        )
    }
}
