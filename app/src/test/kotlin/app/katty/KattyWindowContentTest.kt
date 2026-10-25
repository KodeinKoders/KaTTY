@file:OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class, InternalComposeUiApi::class)

package app.katty

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import app.katty.core.KattyDirectories
import app.katty.core.KattyStore
import app.katty.core.terminals
import app.katty.terminal.TerminalSession
import app.katty.terminals.AgentTracker
import app.katty.terminals.SessionRegistry
import app.katty.terminals.SessionUsageTracker
import app.katty.ui.KattyTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import org.jetbrains.jewel.window.TitleBarScope
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The main window as it is composed, with real shells, but no window around it: its title bar is a row. */
class KattyWindowContentTest {
    private var counter = 0
    private val store = KattyStore(newId = { "id${counter++}" })
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var command = listOf("/bin/sh")
    private val registry = SessionRegistry(
        store, scope, starter = Dispatchers.Unconfined,
        loadTheme = { null },
        start = { spec, _ -> TerminalSession.start(80, 5, command = command, directory = spec.directory) },
    )
    private val tracker = AgentTracker(scope)
    private val usage = SessionUsageTracker(scope, emptyMap(), { emptyMap() }, { null }, { null })
    private val directories = KattyDirectories(Files.createTempDirectory("katty-window"))
    private val keys = WindowKeys()
    private val bridge = WindowBridge()

    private val state get() = store.state.value

    @AfterTest
    fun close() {
        registry.close()
        scope.cancel()
    }

    private object PlainTitleBar : TitleBarScope {
        override val title = "KaTTY"
        override val icon: Painter? = null
        override fun Modifier.align(alignment: Alignment.Horizontal): Modifier = this
    }

    private fun shortcut(key: Key, shift: Boolean = false) =
        keys.handle(KeyEvent(key = key, type = KeyEventType.KeyDown, isMetaPressed = true, isShiftPressed = shift))

    private fun androidx.compose.ui.test.ComposeUiTest.showWindow() {
        setContent {
            KattyTheme(false) {
                Column {
                    KattyWindowContent(store, registry, tracker, usage, directories, bridge, {}, {}, keys, window = null) { content ->
                        Row { with(PlainTitleBar) { content() } }
                    }
                }
            }
        }
        waitForIdle()
    }

    @Test
    fun thePaletteOpensAndClosesWithItsShortcutAndADialogKeepsTheOthers() = runComposeUiTest {
        store.addProject(directories.support.toString())
        showWindow()

        assertTrue(shortcut(Key.K))
        waitForIdle()
        // The title bar's button says it too: the palette's field is the second.
        onAllNodesWithText("Search or run a command", substring = true, useUnmergedTree = true).assertCountEquals(2)
        // While it is open the other shortcuts do nothing, and are not the terminal's either.
        assertTrue(shortcut(Key.D))
        assertEquals(1, state.selectedWorkspace!!.layout.terminals().size)

        assertTrue(shortcut(Key.K))
        waitForIdle()
        onAllNodesWithText("Search or run a command", substring = true, useUnmergedTree = true).assertCountEquals(1)
        assertTrue(shortcut(Key.D))
        waitForIdle()
        assertEquals(2, state.selectedWorkspace!!.layout.terminals().size, "The shortcuts act again once it is closed")
    }

    @Test
    fun closingATerminalWhereAProgramRunsAsksFirst() = runComposeUiTest {
        command = listOf("/bin/sh", "-c", "sleep 30")
        store.addProject(directories.support.toString())
        showWindow()
        val terminal = state.selection.terminal!!
        // The poll has seen the program (the shell is `sh`, and the program `sleep`).
        waitUntil(timeoutMillis = 5_000) { registry.processNames.value[terminal] == "sleep" }

        assertTrue(shortcut(Key.W))
        waitForIdle()

        onNodeWithText("Close the last terminal?", useUnmergedTree = true).assertExists()
        assertEquals(terminal, state.selection.terminal, "It is not closed before the user says so")
    }

    @Test
    fun quittingWhileAProgramRunsAsksAndCancelKeepsTheApplication() = runComposeUiTest {
        command = listOf("/bin/sh", "-c", "sleep 30")
        store.addProject(directories.support.toString())
        showWindow()
        val terminal = state.selection.terminal!!
        waitUntil(timeoutMillis = 5_000) { registry.processNames.value[terminal] == "sleep" }

        val answer = CoroutineScope(Dispatchers.Default).async { bridge.confirmQuit!!.invoke() }

        // The question is asked from another thread: it is there once the window has drawn it.
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Quit KaTTY?", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Cancel", useUnmergedTree = true).performClick()
        waitUntil(timeoutMillis = 5_000) { answer.isCompleted }
        assertEquals(false, answer.getCompleted())
        onNodeWithText("Quit KaTTY?", useUnmergedTree = true).assertDoesNotExist()
    }
}
