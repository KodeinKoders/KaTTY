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
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import app.katty.core.CursorShape
import app.katty.core.KattyDirectories
import app.katty.core.KattyStore
import app.katty.core.terminals
import app.katty.terminal.TerminalCursorShape
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
import kotlinx.coroutines.launch
import org.jetbrains.jewel.window.TitleBarScope
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    // What the settings say of the terminals reaches the sessions that are running, from the window: nothing but a change of the settings makes it.
    @Test
    fun aChangeOfTheCursorSettingsReachesTheRunningTerminals() = runComposeUiTest {
        store.addProject(directories.support.toString())
        showWindow()
        val session = registry.handles.value.values.single().session!!
        assertEquals(TerminalCursorShape.BLOCK, session.frames.value.cursor.shape)

        store.updateSettings { it.copy(cursorShape = CursorShape.BAR, cursorBlink = false) }
        waitForIdle()

        val deadline = System.nanoTime() + 15_000_000_000L
        while (session.frames.value.cursor.shape != TerminalCursorShape.BAR) {
            check(System.nanoTime() < deadline) { "The terminal still has a ${session.frames.value.cursor.shape} cursor" }
            Thread.sleep(20)
        }
        assertFalse(session.frames.value.cursor.blinking)
    }

    // Coming back to the window shows the terminal that finished: the bell must still say it, until the user does something with it.
    @Test
    fun aTerminalThatFinishedInFrontOfTheUserStaysInTheBellUntilTheyTypeInIt() = runComposeUiTest {
        command = listOf("/bin/sh", "-c", """printf '\033]7501;state=done:app=claude\033\\'; sleep 30""")
        store.addProject(directories.support.toString())
        showWindow()
        val id = state.selection.terminal!!
        waitUntil(timeoutMillis = 15_000) { tracker.infos.value[id]?.state == app.katty.core.AgentState.DONE }
        // What the window does when it is drawn again with it.
        repeat(3) { waitForIdle(); Thread.sleep(100) }

        assertEquals(app.katty.core.AgentState.DONE, tracker.infos.value[id]?.state, "Selected in a window that has the focus is not seen")
        onAllNodesWithContentDescription("Terminals needing attention: 1").assertCountEquals(1)
    }

    // Two terminals, the first one selected, that both run `make build` (as the marks of a shell's integration say it), which fails a moment after
    // the window is there. Gives both, once both commands ended.
    private fun androidx.compose.ui.test.ComposeUiTest.twoTerminalsWhereACommandEnds(): Pair<app.katty.core.TerminalId, app.katty.core.TerminalId> {
        command = listOf(
            "/bin/sh", "-c",
            """sleep 2; printf '\033]133;A\007$ \033]133;B\007make build\r\n\033]133;C\007'; sleep 0.5; printf '\033]133;D;2\007\033]133;A\007$ '; sleep 30""",
        )
        store.addProject(directories.support.toString())
        val selected = state.selection.terminal!!
        val other = store.split(selected, app.katty.core.SplitDirection.ROW)!!
        store.focus(selected)
        val ends = java.util.concurrent.CopyOnWriteArrayList<app.katty.terminals.CommandEnd>()
        scope.launch { tracker.commandEnds.collect { ends += it } }
        showWindow()
        waitUntil(timeoutMillis = 15_000) { ends.size >= 2 }
        waitForIdle()
        return selected to other
    }

    // A command that ends where the user is not looking is in the bell, and told in a notice; the one in the terminal they are looking at is not.
    @Test
    fun aCommandThatEndsInATerminalThatIsNotSelectedIsInTheBellUntilItIsSeen() = runComposeUiTest {
        val (_, other) = twoTerminalsWhereACommandEnds()

        assertEquals(setOf(other), tracker.finishedCommands.value.keys, "The selected terminal, in a window that has the focus, is looked at")
        onAllNodesWithContentDescription("Terminals needing attention: 1").assertCountEquals(1)
        onAllNodesWithContentDescription("Failed: make build").assertCountEquals(1)

        // Going to it from the bell is having seen it.
        onNodeWithContentDescription("Terminals needing attention: 1").performClick()
        waitForIdle()
        onNodeWithContentDescription("make build in", substring = true).performClick()
        waitForIdle()

        assertTrue(tracker.finishedCommands.value.isEmpty())
        assertEquals(other, state.selection.terminal)
        onAllNodesWithContentDescription("Terminals needing attention").assertCountEquals(1)
    }

    @Test
    fun aClickInTheTerminalWhereACommandEndedIsHavingSeenIt() = runComposeUiTest {
        val (_, other) = twoTerminalsWhereACommandEnds()
        assertEquals(setOf(other), tracker.finishedCommands.value.keys)

        // The title of each pane: a click in the one of the terminal that is selected sees nothing, the other's sees the command.
        val titles = androidx.compose.ui.test.SemanticsMatcher("the title of a pane") { node ->
            node.config.getOrElseNullable(androidx.compose.ui.semantics.SemanticsProperties.ContentDescription) { null }.orEmpty().any { it.startsWith("Terminal ") }
        }
        onAllNodes(titles).assertCountEquals(2)
        onAllNodes(titles)[0].performClick()
        onAllNodes(titles)[1].performClick()
        waitForIdle()

        assertTrue(tracker.finishedCommands.value.isEmpty())
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
    fun theUsageIsInThePanelUnlessItIsHiddenOrInAWindow() = runComposeUiTest {
        store.addProject(directories.support.toString())
        showWindow()
        onAllNodesWithText("AGENT USAGE").assertCountEquals(1)

        store.updateSettings { it.copy(showUsage = false) }
        waitForIdle()
        onAllNodesWithText("AGENT USAGE").assertCountEquals(0)

        // Back to shown, in the panel. In its own window it is not in the panel, but that composes a real window, which this test has none of.
        store.updateSettings { it.copy(showUsage = true, usageInWindow = false) }
        waitForIdle()
        onAllNodesWithText("AGENT USAGE").assertCountEquals(1)
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

    // What the export says follows the terminal while it is asked: a program on the alternate screen has no history in it, and once it leaves it
    // the shell's history is there again.
    @Test
    fun theExportSaysWhetherAProgramHasTheAlternateScreen() = runComposeUiTest {
        command = listOf("/bin/sh", "-c", """printf '\033[?1049hfull screen'; read line; printf '\033[?1049l'; sleep 30""")
        store.addProject(directories.support.toString())
        showWindow()
        val session = registry.handles.value.values.single().session!!
        waitUntil(timeoutMillis = 15_000) { session.frames.value.alternateScreen }

        assertTrue(shortcut(Key.K))
        waitForIdle()
        onNode(hasSetTextAction()).performTextInput("Export history")
        waitForIdle()
        onNodeWithContentDescription("Export history…", substring = true, useUnmergedTree = true).performClick()
        waitForIdle()
        onNodeWithText("Only what the program shows now", substring = true, useUnmergedTree = true).assertExists()

        session.sendText("\r")
        waitUntil(timeoutMillis = 15_000) {
            onAllNodesWithText("Everything the terminal keeps: its history and its screen.", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithText("Only what the program shows now", substring = true, useUnmergedTree = true).assertDoesNotExist()
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
