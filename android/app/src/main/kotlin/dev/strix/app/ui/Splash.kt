package dev.strix.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.animation.core.tween
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

/** The owl traces itself, the eyes ignite, the wordmark cuts in. About a second, then it hands over. */
@Composable
fun SplashScreen(onDone: () -> Unit) {
    val trace = remember { Animatable(0f) }
    val eyes = remember { Animatable(0f) }
    val word = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        val scope = this
        scope.launch { trace.animateTo(1f, tween(650, easing = Snap)) }
        delay(520)
        scope.launch { eyes.animateTo(1f, tween(220)) }
        scope.launch { word.animateTo(1f, tween(380, easing = Snap)) }
        delay(760)
        onDone()
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
            OwlEmblem(Modifier.size(150.dp), draw = trace.value, eyes = eyes.value, pulse = eyes.value >= 1f, strokeUnits = 2.6f)
            Text(
                "STRIX",
                style = T.Title.copy(fontSize = 34.sp, letterSpacing = (10 - 6 * word.value).sp),
                modifier = Modifier.alpha(word.value).graphicsLayer { translationY = (1f - word.value) * 24f },
            )
            Text("AUTONOMOUS CODING AGENT", style = T.Label, modifier = Modifier.alpha(word.value))
        }
    }
}
