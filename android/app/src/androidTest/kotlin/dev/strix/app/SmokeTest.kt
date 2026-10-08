package dev.strix.app

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.assertIsDisplayed
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.strix.app.data.PrefsStore
import dev.strix.app.data.SettingsStore
import dev.strix.app.data.secretStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Runs on a real emulator in CI: the things Robolectric can't prove (Keystore, services, real rendering). */
@RunWith(AndroidJUnit4::class)
class SmokeTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun shot(name: String) {
        rule.waitForIdle()
        val dir = File(ctx.getExternalFilesDir(null), "screens").also { it.mkdirs() }
        File(dir, "$name.png").outputStream().use { rule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun waitForHome() = rule.waitUntil(15_000) { rule.onAllNodesWithText("YOUR REPOSITORIES").fetchSemanticsNodes().isNotEmpty() }

    @Test fun launchesPastSplashToHome() {
        waitForHome()
        rule.onNodeWithText("STRIX").assertIsDisplayed()
        shot("emu-home")
    }

    @Test fun settingsScreenOpensAndKeepsTheToken() {
        waitForHome()
        rule.onNodeWithContentDescription("Settings").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("ghp_… or github_pat_…").performScrollTo().performClick()
        rule.onNodeWithText("ghp_… or github_pat_…").performTextInput("ghp_smoke_test")
        rule.waitForIdle()
        shot("emu-settings")
        val s = SettingsStore(PrefsStore(ctx.getSharedPreferences("strix", 0)), secretStore(ctx))
        assertEquals("ghp_smoke_test", s.githubToken)
        s.githubToken = ""
    }

    @Test fun secretsAreEncryptedAtRest() {
        val store = secretStore(ctx)
        store.put("probe", "super-secret-value-123")
        assertEquals("super-secret-value-123", store.get("probe"))
        val dir = File(ctx.applicationInfo.dataDir, "shared_prefs")
        val onDisk = dir.listFiles().orEmpty().filter { it.name.contains("strix_secrets") }.joinToString { it.readText() }
        assertTrue("encrypted prefs file should exist", onDisk.isNotEmpty())
        assertFalse("secret must not be stored in plain text", onDisk.contains("super-secret-value-123"))
        store.put("probe", null)
    }

    @Test fun foregroundServiceStartsAndStopsWithoutCrashing() {
        waitForHome()
        AgentService.setRunning(ctx, true)
        Thread.sleep(1500)
        AgentService.setRunning(ctx, false)
        Thread.sleep(500)
        rule.onNodeWithText("STRIX").assertIsDisplayed()
    }

    @Test fun rotationKeepsTheApp() {
        waitForHome()
        rule.activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        rule.waitForIdle()
        rule.onNodeWithText("STRIX").assertIsDisplayed()
        shot("emu-landscape")
        rule.activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        rule.waitForIdle()
        rule.onNodeWithText("STRIX").assertIsDisplayed()
    }
}
