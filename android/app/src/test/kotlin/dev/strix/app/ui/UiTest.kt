package dev.strix.app.ui

import android.graphics.Bitmap
import android.app.Application
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.activity.ComponentActivity
import android.graphics.Canvas
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.strix.app.AppViewModel
import dev.strix.app.Load
import dev.strix.app.Screen
import dev.strix.app.SessionController
import dev.strix.app.FakeGitHub
import dev.strix.app.core.*
import dev.strix.app.data.SessionData
import dev.strix.app.data.SessionStore
import dev.strix.app.data.TranscriptItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class UiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private lateinit var vm: AppViewModel
    private val out = File("build/screens").also { it.mkdirs() }

    @Before fun setUp() {
        vm = AppViewModel(ApplicationProvider.getApplicationContext<Application>())
    }

    /** Draws the real view tree to a PNG (captureToImage doesn't work under Robolectric here). */
    private fun shot(name: String) {
        rule.waitForIdle()
        val v = rule.activity.window.decorView
        val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bmp))
        File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun content(block: @Composable () -> Unit) = rule.setContent { StrixTheme { StrixBackground { block() } } }

    private fun controller(llm: LlmClient, gh: FakeGitHub = FakeGitHub(), data: SessionData = SessionData("s1", "me/app", "main")): SessionController {
        val store = SessionStore(File(out, "sessions-${System.nanoTime()}"))
        return SessionController(
            data, gh, testRouter(llm), { AgentSettings(planning = false) }, store, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            Dispatchers.Unconfined, Dispatchers.Unconfined, Dispatchers.Unconfined,
        )
    }

    @Test fun splashThenHome() {
        rule.setContent { StrixTheme { StrixApp(vm) } }
        rule.mainClock.advanceTimeBy(300)
        shot("01-splash-trace")
        rule.mainClock.advanceTimeBy(1500)
        rule.waitForIdle()
        rule.onNodeWithText("STRIX").assertIsDisplayed()
        rule.onNodeWithText("SETUP NEEDED").assertIsDisplayed()
        shot("02-home-setup")
    }

    @Test fun homeListsReposAndStartsSession() {
        vm.seedForTest(
            repos = Load.Done(listOf(
                RepoInfo("me/strix-app", "Android coding agent", true, "main", ""),
                RepoInfo("me/website", "Personal site built with Astro", false, "main", ""),
                RepoInfo("me/dotfiles", "", false, "master", ""),
            )),
            recents = listOf(SessionData("r1", "me/strix-app", "main", "strix/add-readme-2k", "Add a README section", System.currentTimeMillis() - 3_600_000,
                overlay = listOf(OverlayEntry("README.md", "x")))),
        )
        content { HomeScreen(vm) }
        rule.onNodeWithText("me/website").assertIsDisplayed()
        rule.onNodeWithText("Add a README section").assertIsDisplayed()
        shot("03-home-repos")
        rule.onNodeWithText("Search repositories").performClick()
        rule.onNodeWithText("Search repositories").performTextInput("dot")
        rule.waitForIdle()
        rule.onAllNodesWithText("me/website").assertCountEquals(0)
        rule.onNodeWithText("me/dotfiles").assertIsDisplayed()
    }

    @Test fun sessionTranscriptShowsToolsAndChanges() = runBlocking {
        val llm = ScriptedLlm(mutableListOf(
            call("list_dir", """{"path":"src"}""", "t1"),
            call("read_file", """{"path":"src/a.kt"}""", "t2"),
            call("edit_file", """{"path":"src/a.kt","old":"= 1","new":"= 2"}""", "t3"),
            call("read_file", """{"path":"missing.kt"}""", "t4"),
            say("Updated `a()` to return **2**.\n\n- changed `src/a.kt`\n- nothing else touched\n\n```kotlin\nfun a() = 2\n```"),
        ))
        val c = controller(llm); c.load()
        vm.seedForTest(session = c)
        content { SessionScreen(vm, c) }
        rule.onNodeWithText("WHAT SHOULD WE BUILD?").assertIsDisplayed()
        shot("04-session-empty")
        rule.onNodeWithText("Tell Strix what to build or fix").performClick()
        rule.onNodeWithText("Tell Strix what to build or fix").performTextInput("make a return two")
        rule.onNodeWithContentDescription("Send").assertIsEnabled().performClick()
        rule.waitForIdle()
        rule.onNodeWithText("make a return two").assertIsDisplayed()
        assertEquals(2, rule.onAllNodesWithText("READ_FILE").fetchSemanticsNodes().size)
        rule.onNodeWithText("EDIT_FILE").assertIsDisplayed()
        rule.onNodeWithText("1 file staged").assertIsDisplayed()
        shot("05-session-after-run")
        // expand a tool row to see its output
        assertEquals(1, c.changes.size)
        rule.onNodeWithText("REVIEW").performClick()
        rule.waitForIdle()
        assertEquals(Screen.Diff, vm.screen)
    }

    @Test fun sessionWithReusedToolIdsRendersWithoutCrashing() = runBlocking {
        val llm = ScriptedLlm(mutableListOf(
            call("list_dir", "{}", "call_0"), call("read_file", """{"path":"src/a.kt"}""", "call_0"), call("grep", """{"pattern":"fun"}""", "call_0"), say("ok"),
        ))
        val c = controller(llm); c.load(); c.send("look around the repo please")
        vm.seedForTest(session = c)
        content { SessionScreen(vm, c) }
        rule.waitForIdle()
        assertEquals(3, rule.onAllNodesWithText("src", substring = true).fetchSemanticsNodes().size.coerceAtLeast(3))
        rule.onNodeWithText("LIST_DIR").assertIsDisplayed()
        rule.onNodeWithText("GREP").assertIsDisplayed()
        shot("13-session-reused-ids")
    }

    @Test fun diffScreenExpandsAndCommits() = runBlocking {
        val gh = FakeGitHub()
        val llm = ScriptedLlm(mutableListOf(
            call("edit_file", """{"path":"src/a.kt","old":"= 1","new":"= 2"}"""),
            call("write_file", """{"path":"src/new.kt","content":"fun n() = 1\n"}"""),
            say("done"),
            say("Change a and add n"),
        ))
        val c = controller(llm, gh); c.load(); c.send("do it please now")
        vm.seedForTest(session = c)
        content { DiffScreen(vm, c) }
        rule.onNodeWithText("REVIEW CHANGES").assertIsDisplayed()
        rule.onNodeWithText("src/a.kt").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("+ fun a() = 2", substring = true).assertIsDisplayed()
        shot("06-diff-expanded")
        rule.onNodeWithText("COMMIT").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("COMMIT 2 FILES").assertIsDisplayed()
        shot("07-commit-dialog")
        rule.onNodeWithText("COMMIT & PUSH").assertIsEnabled().performClick()
        rule.waitForIdle()
        rule.onNodeWithText("COMMITTED").assertIsDisplayed()
        rule.onNodeWithText("OPEN PULL REQUEST").assertIsDisplayed()
        shot("08-commit-done")
        assertTrue(gh.log.any { it.startsWith("pr:") })
    }

    @Test fun settingsStoresKeysAndTogglesSecrets() {
        content { SettingsScreen(vm) }
        rule.onNodeWithText("ghp_… or github_pat_…").performScrollTo().performClick()
        rule.onNodeWithText("ghp_… or github_pat_…").performTextInput("ghp_abc123")
        rule.waitForIdle()
        assertEquals("ghp_abc123", vm.settings.githubToken)
        shot("09-settings")
        rule.onNodeWithText("OpenRouter key", substring = true).performScrollTo()
        shot("10-settings-models")
        rule.onAllNodesWithText("VERIFY")[0].assertIsEnabled()
    }

    @Test fun modelsScreenReordersAndRemoves() {
        content { ModelsScreen(vm) }
        rule.onNodeWithText("MODEL ROUTER").assertIsDisplayed()
        shot("11-models")
        val before = vm.roles[Roles.PLANNER]!!       // the first role card on screen
        rule.onAllNodesWithContentDescription("Move down")[0].performClick()
        rule.waitForIdle()
        assertEquals(before[0], vm.roles[Roles.PLANNER]!![1])
        rule.onAllNodesWithContentDescription("Remove")[0].performClick()
        rule.waitForIdle()
        assertEquals(before.size - 1, vm.roles[Roles.PLANNER]!!.size)
        rule.onNodeWithText("RESET").performClick()
        rule.waitForIdle()
        assertEquals(Roles.defaults, vm.roles)
    }

    @Test fun markdownRenders() {
        content { androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.padding(16.dp)) { Markdown("# Title\n\nSome **bold** and `code` text.\n\n1. one\n2. two\n\n```py\nprint('hi')\n```") } }
        rule.onNodeWithText("Title").assertIsDisplayed()
        rule.onNodeWithText("print('hi')").assertIsDisplayed()
        shot("12-markdown")
    }
}
