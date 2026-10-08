package dev.strix.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.ui.graphics.toArgb
import dev.strix.app.ui.C
import dev.strix.app.ui.StrixApp
import dev.strix.app.ui.StrixTheme
import androidx.activity.SystemBarStyle

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(SystemBarStyle.dark(C.Bg.toArgb()), SystemBarStyle.dark(C.Bg.toArgb()))
        super.onCreate(savedInstanceState)
        setContent {
            StrixTheme {
                StrixApp(vm)
            }
        }
    }

    override fun onStop() {
        vm.session?.persist()
        super.onStop()
    }
}
