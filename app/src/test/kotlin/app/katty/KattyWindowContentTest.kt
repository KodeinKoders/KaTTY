@file:OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class, InternalComposeUiApi::class)

package app.katty

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asSkiaBitmap
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
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.performKeyInput
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
import kotlin.test.assertNotNull
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
        // Not the user's shell, which reads the user's files.
        startRun = { run, directory, _ -> TerminalSession.start(80, 5, command = listOf("/bin/sh", "-c", run), directory = directory, shellIntegration = false) },
    )
    private val tracker = AgentTracker(scope)
    private val usage = SessionUsageTracker(scope, emptyMap(), { emptyMap() }, { null }, { null })
    private val directories = KattyDirectories(Files.createTempDirectory("katty-window"))
    private val keys = WindowKeys()
    private val bridge = WindowBridge()

    // The sounds that the window made. The clock moves by 10 s each time it is read: no sound is held back for coming too soon after another.
    private var soundTime = 0L
    private val sounds = java.util.concurrent.atomic.AtomicInteger()
    private val attentionSound = AttentionSound(clock = { soundTime += 10_000_000_000L; soundTime }, sound = { sounds.incrementAndGet() })

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
                    KattyWindowContent(store, registry, tracker, usage, directories, attentionSound, bridge, {}, {}, keys, window = null) { content ->
                        Row { with(PlainTitleBar) { content() } }
                    }
                }
            }
        }
        waitForIdle()
    }

    // The button of the header asks what to run, Enter runs it over the panes, which is minimized into the header and shown again from there, and Stop
    // ends it.
    @Test
    fun aCommandIsRunOverThePanesMinimizedShownAgainAndStopped() = runComposeUiTest {
        store.addProject(directories.support.toString())
        showWindow()

        onNodeWithContentDescription("Run a command").performClick()
        waitForIdle()
        onNodeWithContentDescription("Command").performTextInput("echo running; sleep 30")
        onNodeWithText("Run").performClick()
        waitForIdle()

        onAllNodesWithContentDescription("Run a command", substring = false).assertCountEquals(1)
        onAllNodesWithContentDescription("Run terminal").assertCountEquals(1)
        val workspace = state.selection.workspace!!
        assertEquals("echo running; sleep 30", state.selectedWorkspace!!.runCommand)
        waitUntil(timeoutMillis = 15_000) { registry.runs.terminals.value[workspace]?.session != null }

        onNodeWithContentDescription("Minimize the run terminal").performClick()
        waitForIdle()
        onAllNodesWithContentDescription("Run terminal").assertCountEquals(0)
        onNodeWithContentDescription("Run terminal: echo running; sleep 30", substring = true).performClick()
        waitForIdle()
        onAllNodesWithContentDescription("Run terminal").assertCountEquals(1)

        val session = registry.runs.terminals.value.getValue(workspace).session!!
        onNodeWithContentDescription("Stop the run").performClick()
        waitForIdle()
        // As for a program in a terminal, stopping it asks.
        onNodeWithText("Stop run").performClick()
        waitForIdle()
        onAllNodesWithContentDescription("Run terminal").assertCountEquals(0)
        onAllNodesWithContentDescription("Run terminal: ", substring = true).assertCountEquals(0)
        waitUntil(timeoutMillis = 15_000) { session.exited.value != null }
    }

    // The button of its header runs the command again, in a new session: asked about while the command runs, at once once it has ended.
    @Test
    fun theRunIsRestartedFromItsHeader() = runComposeUiTest {
        store.addProject(directories.support.toString())
        showWindow()
        onNodeWithContentDescription("Run a command").performClick()
        waitForIdle()
        onNodeWithContentDescription("Command").performTextInput("sleep 30")
        onNodeWithText("Run").performClick()
        val workspace = state.selection.workspace!!
        waitUntil(timeoutMillis = 15_000) { registry.runs.terminals.value[workspace]?.session != null }
        val first = registry.runs.terminals.value.getValue(workspace)

        onNodeWithContentDescription("Run the command again").performClick()
        waitForIdle()
        onNodeWithText("Restart run").performClick()
        waitUntil(timeoutMillis = 15_000) {
            registry.runs.terminals.value[workspace]?.let { it.generation != first.generation && it.session != null } == true
        }
        waitUntil(timeoutMillis = 15_000) { first.session!!.exited.value != null }
        assertEquals("sleep 30", registry.runs.terminals.value.getValue(workspace).command)
        onAllNodesWithContentDescription("Run terminal").assertCountEquals(1)
    }

    // The text of the field of the question of the run.
    private fun androidx.compose.ui.test.ComposeUiTest.commandText(): String =
        onNodeWithContentDescription("Command").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text

    // While the field is empty, the AI is asked for the command, by the button or by ⌘R, and its answer is put in the field, to be read before it runs.
    @Test
    fun theAiIsAskedForTheCommandWhileTheFieldIsEmpty() = runComposeUiTest {
        store.addProject(directories.support.toString())
        store.updateSettings { it.copy(aiCommand = "printf '\\n\\140./gradlew run --debug-jvm\\140\\n'") }
        showWindow()

        shortcut(Key.R)
        waitForIdle()
        onNodeWithContentDescription("Ask the AI for the command").performClick()
        waitUntil(timeoutMillis = 15_000) { commandText() == "./gradlew run --debug-jvm" }
        assertTrue(onNodeWithContentDescription("Ask the AI for the command").fetchSemanticsNode().config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled), "Not while the field has a command")

        // ⌘R does it too, once the field is empty again; and an AI command that fails says why.
        store.updateSettings { it.copy(aiCommand = "echo 'not logged in'; exit 2") }
        onNodeWithContentDescription("Command").performKeyInput {
            keyDown(Key.MetaLeft)
            pressKey(Key.Backspace)
            keyUp(Key.MetaLeft)
        }
        waitForIdle()
        assertEquals("", commandText())
        shortcut(Key.R)
        waitUntil(timeoutMillis = 15_000) { onAllNodesWithText("The AI command failed: it ended with the code 2: not logged in.").fetchSemanticsNodes().isNotEmpty() }
        assertEquals("", commandText())
        onAllNodesWithContentDescription("Run a command", substring = false).assertCountEquals(2)
    }

    // An empty AI command turns the AI off: the question has no button to ask it.
    @Test
    fun theQuestionHasNoAskAiWhileTheAiIsOff() = runComposeUiTest {
        store.addProject(directories.support.toString())
        store.updateSettings { it.copy(aiCommand = "printf 'make run\\n'") }
        showWindow()

        shortcut(Key.R)
        waitForIdle()
        onAllNodesWithContentDescription("Ask the AI for the command").assertCountEquals(1)

        store.updateSettings { it.copy(aiCommand = "") }
        waitForIdle()
        onAllNodesWithContentDescription("Ask the AI for the command").assertCountEquals(0)
    }

    // Command + Enter in the question runs the command minimized into the header: the panes keep the keyboard.
    @Test
    fun commandEnterRunsTheCommandMinimized() = runComposeUiTest {
        store.addProject(directories.support.toString())
        showWindow()

        shortcut(Key.R)
        waitForIdle()
        onNodeWithContentDescription("Command").performTextInput("sleep 30")
        onNodeWithContentDescription("Command").performKeyInput {
            keyDown(Key.MetaLeft)
            pressKey(Key.Enter)
            keyUp(Key.MetaLeft)
        }
        waitForIdle()

        onAllNodesWithContentDescription("Run a command", substring = false).assertCountEquals(1)
        onAllNodesWithContentDescription("Run terminal").assertCountEquals(0)
        onNodeWithContentDescription("Run terminal: sleep 30", substring = true).assertExists()
        assertFalse(registry.runs.terminals.value.getValue(state.selection.workspace!!).shown)
    }

    // The run over the panes has the keyboard, which the panes take back when it is minimized; drawn to build/renders/run-overlay-window.png.
    @Test
    fun theRunOverThePanesHasTheKeyboard() = runComposeUiTest {
        store.addProject(directories.support.toString())
        store.split(state.selection.terminal!!, app.katty.core.SplitDirection.ROW)
        showWindow()
        val workspace = state.selection.workspace!!

        shortcut(Key.R)
        waitForIdle()
        onNodeWithContentDescription("Command").performTextInput("printf 'BUILD SUCCESSFUL\\n'; exit 1")
        onNodeWithText("Run").performClick()
        waitUntil(timeoutMillis = 15_000) { registry.runs.terminals.value[workspace]?.session?.exited?.value != null }
        val session = registry.runs.terminals.value.getValue(workspace).session!!
        waitUntil(timeoutMillis = 15_000) { session.frames.value.rows.any { row -> "BUILD SUCCESSFUL" in row.cells.joinToString("") { it.text } } }
        waitForIdle()

        fun focusedInRun(): Boolean {
            val run = onNodeWithContentDescription("Run terminal").fetchSemanticsNode().boundsInRoot
            val focused = runCatching { onNode(androidx.compose.ui.test.isFocused()).fetchSemanticsNode().boundsInRoot }.getOrNull() ?: return false
            return run.contains(focused.center)
        }
        waitUntil(timeoutMillis = 5_000) { focusedInRun() }
        onNodeWithContentDescription("Run terminal: printf", substring = true).assertExists()
        onAllNodesWithText("Failed (1)", useUnmergedTree = true).assertCountEquals(2)
        val image = org.jetbrains.skia.Image.makeFromBitmap(onRoot().captureToImage().asSkiaBitmap())
        java.io.File("build/renders").apply { mkdirs() }.resolve("run-overlay-window.png").writeBytes(image.encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)!!.bytes)

        shortcut(Key.Enter, shift = true)
        waitForIdle()
        onAllNodesWithContentDescription("Run terminal").assertCountEquals(0)
        assertTrue(registry.runs.terminals.value.getValue(workspace).ended, "A command that ended by itself stays")
        val pane = registry.handles.value.getValue(state.selection.terminal!!)
        waitUntil(timeoutMillis = 5_000) { runCatching { onNode(androidx.compose.ui.test.isFocused()).assertExists() }.isSuccess }
        assertNotNull(pane.session)
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
    private fun androidx.compose.ui.test.ComposeUiTest.twoTerminalsWhereACommandEnds(
        beforeItEnds: androidx.compose.ui.test.ComposeUiTest.() -> Unit = {},
    ): Pair<app.katty.core.TerminalId, app.katty.core.TerminalId> {
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
        beforeItEnds()
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

    private fun press(key: Key, meta: Boolean = false, alt: Boolean = false, ctrl: Boolean = false) =
        keys.handle(KeyEvent(key = key, type = KeyEventType.KeyDown, isMetaPressed = meta, isAltPressed = alt, isCtrlPressed = ctrl))

    // Only the terminals, in a bigger font, and the keys that are pressed, never what is typed; a click on the title bar's button ends it.
    @Test
    fun presentationModeShowsOnlyTheTerminalsAndTheKeysThatArePressed() = runComposeUiTest {
        store.addProject(directories.support.toString())
        showWindow()
        val session = registry.handles.value.values.single().session!!
        waitUntil(timeoutMillis = 5_000) { session.frames.value.rows.size > 5 }
        val rows = session.frames.value.rows.size
        onAllNodesWithContentDescription("Add project").assertCountEquals(1)
        onAllNodesWithContentDescription("Pane menu").assertCountEquals(1)

        assertTrue(press(Key.P, meta = true, alt = true))
        waitForIdle()
        onAllNodesWithContentDescription("Add project").assertCountEquals(0)
        onAllNodesWithContentDescription("Pane menu").assertCountEquals(0)
        onNodeWithContentDescription("Stop presentation mode").assertExists()
        waitUntil(timeoutMillis = 5_000) { session.frames.value.rows.size < rows }

        assertTrue(press(Key.D, meta = true))
        assertFalse(press(Key.S))
        assertFalse(press(Key.C, ctrl = true))
        waitForIdle()
        assertEquals(2, state.selectedWorkspace!!.layout.terminals().size)
        onNodeWithText("⌘D").assertExists()
        onNodeWithText("Split right").assertExists()
        onNodeWithText("⌃C").assertExists()
        onAllNodesWithText("S").assertCountEquals(0)
        // To look at: the window as a demo shows it.
        session.sendText("echo 'Hello, presentation'\r")
        waitUntil(timeoutMillis = 5_000) { session.frames.value.rows.any { row -> row.cells.joinToString("") { it.text }.contains("Hello, presentation") } }
        waitForIdle()
        val image = org.jetbrains.skia.Image.makeFromBitmap(onRoot().captureToImage().asSkiaBitmap())
        java.io.File("build/renders").apply { mkdirs() }.resolve("presentation-window.png").writeBytes(image.encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)!!.bytes)

        onNodeWithContentDescription("Stop presentation mode").performClick()
        waitForIdle()
        onAllNodesWithContentDescription("Add project").assertCountEquals(1)
        onAllNodesWithContentDescription("Pane menu").assertCountEquals(2)
        onAllNodesWithText("⌘D").assertCountEquals(0)
    }

    // Teach mode shows the keys that are pressed, never what is typed, and the window stays as it is; the title bar's button ends it.
    @Test
    fun teachModeShowsTheKeysThatArePressedAndChangesNothingElse() = runComposeUiTest {
        store.addProject(directories.support.toString())
        showWindow()
        val session = registry.handles.value.values.single().session!!
        waitUntil(timeoutMillis = 5_000) { session.frames.value.rows.size > 5 }
        val rows = session.frames.value.rows.size

        assertTrue(press(Key.T, meta = true, alt = true))
        waitForIdle()
        onNodeWithContentDescription("Stop teach mode").assertExists()
        onAllNodesWithContentDescription("Stop presentation mode").assertCountEquals(0)
        onAllNodesWithContentDescription("Add project").assertCountEquals(1)
        onAllNodesWithContentDescription("Pane menu").assertCountEquals(1)

        assertTrue(press(Key.D, meta = true))
        assertFalse(press(Key.S))
        assertFalse(press(Key.C, ctrl = true))
        waitForIdle()
        onNodeWithText("⌘D").assertExists()
        onNodeWithText("Split right").assertExists()
        onNodeWithText("⌃C").assertExists()
        onAllNodesWithText("S").assertCountEquals(0)
        onAllNodesWithContentDescription("Pane menu").assertCountEquals(2)
        assertEquals(rows, session.frames.value.rows.size, "The font is the setting's: the terminal has the rows it had")
        val image = org.jetbrains.skia.Image.makeFromBitmap(onRoot().captureToImage().asSkiaBitmap())
        java.io.File("build/renders").apply { mkdirs() }.resolve("teach-window.png").writeBytes(image.encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)!!.bytes)

        onNodeWithContentDescription("Stop teach mode").performClick()
        waitForIdle()
        onAllNodesWithText("⌘D").assertCountEquals(0)
        onAllNodesWithContentDescription("Stop teach mode").assertCountEquals(0)
    }

    // Teach mode leaves the notices as they are.
    @Test
    fun aNoticeIsShownInTeachMode() = runComposeUiTest {
        twoTerminalsWhereACommandEnds { assertTrue(press(Key.T, meta = true, alt = true)) }
        waitForIdle()

        onAllNodesWithContentDescription("Failed: make build").assertCountEquals(1)
    }

    // Nothing of another terminal comes on the screen by itself during a demo: the bell still counts it.
    @Test
    fun aNoticeIsNotShownDuringAPresentation() = runComposeUiTest {
        twoTerminalsWhereACommandEnds { assertTrue(press(Key.P, meta = true, alt = true)) }
        waitForIdle()

        onAllNodesWithContentDescription("Failed: make build").assertCountEquals(0)
        onAllNodesWithContentDescription("Terminals needing attention: 1").assertCountEquals(1)
    }

    // Two terminals, the first one selected, whose programs both report [report] (OSC 7501) a moment after the window is there. Gives both, once
    // both are in [reported].
    private fun androidx.compose.ui.test.ComposeUiTest.twoTerminalsThatReport(
        report: String,
        reported: app.katty.core.AgentState,
        beforeTheyReport: androidx.compose.ui.test.ComposeUiTest.() -> Unit = {},
    ): Pair<app.katty.core.TerminalId, app.katty.core.TerminalId> {
        command = listOf("/bin/sh", "-c", """sleep 2; printf '\033]7501;$report\033\\'; sleep 30""")
        store.addProject(directories.support.toString())
        val selected = state.selection.terminal!!
        val other = store.split(selected, app.katty.core.SplitDirection.ROW)!!
        store.focus(selected)
        val transitions = java.util.concurrent.CopyOnWriteArrayList<app.katty.terminals.AgentTransition>()
        scope.launch { tracker.transitions.collect { transitions += it } }
        showWindow()
        beforeTheyReport()
        waitUntil(timeoutMillis = 15_000) { transitions.filter { it.info.state == reported }.map { it.terminal }.toSet() == setOf(selected, other) }
        waitForIdle()
        return selected to other
    }

    // The agent in the terminal that the user is looking at needs them too, and they see it: only the other one is heard.
    @Test
    fun anAgentThatNeedsTheUserWhereTheyAreNotLookingMakesASound() = runComposeUiTest {
        twoTerminalsThatReport("state=blocked:app=claude", app.katty.core.AgentState.NEEDS_INPUT)

        waitUntil(timeoutMillis = 5_000) { sounds.get() >= 1 }
        waitForIdle()
        assertEquals(1, sounds.get(), "The selected terminal, in a window that has the focus, is looked at")
    }

    @Test
    fun anAgentThatFailsWhereTheUserIsNotLookingMakesASound() = runComposeUiTest {
        twoTerminalsThatReport("state=error:app=claude", app.katty.core.AgentState.ERROR)

        waitUntil(timeoutMillis = 5_000) { sounds.get() >= 1 }
        waitForIdle()
        assertEquals(1, sounds.get())
    }

    @Test
    fun anAgentThatNeedsTheUserMakesNoSoundWhenTheSettingIsOff() = runComposeUiTest {
        store.updateSettings { it.copy(attentionSound = false) }
        twoTerminalsThatReport("state=blocked:app=claude", app.katty.core.AgentState.NEEDS_INPUT)

        // The notice says it still: the window was told, and made no sound.
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithContentDescription("needs input", substring = true).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(0, sounds.get())
    }

    // A demo, or a stream, is not interrupted: the bell still counts them.
    @Test
    fun anAgentThatNeedsTheUserMakesNoSoundDuringAPresentation() = runComposeUiTest {
        twoTerminalsThatReport("state=blocked:app=claude", app.katty.core.AgentState.NEEDS_INPUT) { assertTrue(press(Key.P, meta = true, alt = true)) }

        onAllNodesWithContentDescription("Terminals needing attention: 2").assertCountEquals(1)
        assertEquals(0, sounds.get())
    }

    @Test
    fun anAgentThatFinishesMakesNoSound() = runComposeUiTest {
        twoTerminalsThatReport("state=done:app=claude", app.katty.core.AgentState.DONE)

        // The notice says it: the window was told, and made no sound.
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithContentDescription("finished", substring = true).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(0, sounds.get())
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
