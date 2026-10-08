package dev.strix.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.strix.app.AppViewModel
import dev.strix.app.Screen

private fun rank(s: Screen) = when (s) {
    Screen.Home -> 0
    Screen.Settings, Screen.Models, Screen.Session -> 1
    Screen.Diff -> 2
}

@Composable
fun StrixApp(vm: AppViewModel, showSplash: Boolean = true) {
    var splash by rememberSaveable { mutableStateOf(showSplash) }
    StrixBackground {
        if (splash) {
            SplashScreen { splash = false }
        } else {
            BackHandler(enabled = vm.stack.size > 1) { vm.back() }
            AnimatedContent(
                targetState = vm.screen,
                modifier = Modifier.fillMaxSize(),
                transitionSpec = {
                    val forward = rank(targetState) > rank(initialState)
                    val spec = tween<androidx.compose.ui.unit.IntOffset>(220, easing = Snap)
                    (slideInHorizontally(spec) { if (forward) it / 5 else -it / 5 } + fadeIn(tween(200))) togetherWith
                        (slideOutHorizontally(spec) { if (forward) -it / 5 else it / 5 } + fadeOut(tween(120)))
                },
                label = "nav",
            ) { s ->
                when (s) {
                    Screen.Home -> HomeScreen(vm)
                    Screen.Settings -> SettingsScreen(vm)
                    Screen.Models -> ModelsScreen(vm)
                    Screen.Session -> vm.session?.let { SessionScreen(vm, it) }
                    Screen.Diff -> vm.session?.let { DiffScreen(vm, it) }
                }
            }
        }
    }
}
