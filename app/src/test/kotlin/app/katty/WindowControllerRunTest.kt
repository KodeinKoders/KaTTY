package app.katty

import app.katty.core.KattyStore
import app.katty.core.terminals
import app.katty.screens.paletteCommands
import app.katty.terminal.TerminalSession
import app.katty.terminal.sh
import app.katty.terminals.RunTerminals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The run terminal of a workspace, as the shortcuts, the menus and the palette drive it. */
class WindowControllerRunTest {
    @kotlin.test.BeforeTest
    fun useAMacKeyboard() {
        Shortcuts.map = MAC_SHORTCUTS
    }

    @kotlin.test.AfterTest
    fun putTheKeyboardBack() {
        Shortcuts.map = ShortcutMap.DEFAULT
    }

    private var counter = 0
    private val store = KattyStore(newId = { "id${counter++}" })
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val runs = RunTerminals(scope, Dispatchers.Unconfined) { _, command, directory ->
        TerminalSession.start(80, 5, command = sh(command), directory = directory, shellIntegration = false)
    }
    private val platform = object : WindowPlatform {
        override fun chooseFolder(title: String, initial: String?): String? = null
        override fun chooseSaveFile(title: String, directory: String?, name: String): String? = null
        override fun copy(text: String) = Unit
        override fun readClipboard(): String? = null
        override fun reveal(directory: String) = Unit
        override fun openIn(bundleId: String, directory: String) = Unit
        override fun openFile(ide: app.katty.core.Ide, bundleId: String, file: app.katty.terminal.TerminalFilePosition) = Unit
        override fun openBoardWindow() = Unit
        override fun openAbout() = Unit
    }
    private val controller = WindowController(store, CoroutineScope(Dispatchers.Unconfined), platform, {}, {}, {}, runs = runs)

    private val state get() = store.state.value
    private val workspace get() = state.selection.workspace!!

    init {
        store.addProject(tmpDirectory)
    }

    @AfterTest
    fun close() {
        runs.close().forEach { it.close() }
        scope.cancel()
    }

    private fun run(command: String) {
        assertTrue(controller.handle(AppShortcut.RUN, null))
        controller.answerRun(command)
    }

    // A run that replaces another starts once the other has ended: its session comes then.
    private fun ended(workspace: app.katty.core.WorkspaceId) =
        runBlocking { withTimeout(15_000) { runs.terminals.first { it[workspace]?.session != null }.getValue(workspace).session!!.exited.first { it != null } } }

    @Test
    fun commandRAsksWhatToRunInTheDirectoryOfTheSelectedTerminalStartingWithTheLastCommand() {
        store.setDirectory(state.selection.terminal!!, "/private/tmp")
        store.setRunCommand(workspace, "./gradlew :app:run")

        assertTrue(controller.handle(AppShortcut.RUN, null))

        val request = assertNotNull(controller.runRequest)
        assertEquals("./gradlew :app:run", request.initial)
        assertEquals("/private/tmp", request.directory)
        assertFalse(request.interrupts)
        assertTrue(controller.blockingDialog, "The question has the keys")
    }

    @Test
    fun theAnswerRunsTheCommandOverThePanesAndIsWhatTheNextRunIsOffered() {
        run("sleep 30")

        assertNull(controller.runRequest)
        val run = assertNotNull(runs.terminals.value[workspace])
        assertEquals("sleep 30", run.command)
        assertTrue(run.shown)
        assertEquals("sleep 30", state.selectedWorkspace!!.runCommand)
        controller.handle(AppShortcut.RUN, null)
        assertEquals("sleep 30", controller.runRequest!!.initial)
    }

    @Test
    fun commandRInTheQuestionAsksTheAiInsteadOfOpeningItAgain() {
        controller.handle(AppShortcut.RUN, null)
        val request = controller.runRequest
        val tick = controller.askRunTick

        assertTrue(controller.handle(AppShortcut.RUN, null))

        assertEquals(tick + 1, controller.askRunTick)
        assertEquals(request, controller.runRequest, "The question stays as it is")
    }

    @Test
    fun aBlankAnswerRunsNothing() {
        controller.handle(AppShortcut.RUN, null)

        controller.answerRun("   ")

        assertNotNull(controller.runRequest, "The question stays")
        assertTrue(runs.terminals.value.isEmpty())
    }

    @Test
    fun aRunWhileACommandRunsWarnsThenAsksBeforeItStopsIt() {
        run("sleep 30")
        val first = runs.terminals.value.getValue(workspace)

        controller.handle(AppShortcut.RUN, null)
        assertTrue(controller.runRequest!!.interrupts, "The question says that the command that runs will be stopped")
        controller.answerRun("sleep 31")

        assertEquals("sleep 31", controller.confirmingRun?.command, "A second question confirms it")
        assertEquals(first.generation, runs.terminals.value.getValue(workspace).generation, "Nothing is stopped before it is confirmed")
        controller.answerRunConfirmation(false)
        assertEquals("sleep 30", runs.terminals.value.getValue(workspace).command)

        controller.handle(AppShortcut.RUN, null)
        controller.answerRun("sleep 31")
        controller.answerRunConfirmation(true)
        assertEquals("sleep 31", runs.terminals.value.getValue(workspace).command)
    }

    @Test
    fun aRunWhoseCommandEndedByItselfIsReplacedWithoutAQuestion() {
        run("exit 2")
        ended(workspace)

        controller.handle(AppShortcut.RUN, null)
        assertFalse(controller.runRequest!!.interrupts)
        controller.answerRun("sleep 30")

        assertNull(controller.confirmingRun)
        assertEquals("sleep 30", runs.terminals.value.getValue(workspace).command)
    }

    @Test
    fun commandShiftRShowsOrMinimizesTheRunAndCommandShiftEnterMinimizesIt() {
        assertTrue(controller.handle(AppShortcut.RUN_TOGGLE, null), "Nothing to show: nothing happens")
        run("sleep 30")
        val tick = controller.focusTick

        controller.handle(AppShortcut.RUN_TOGGLE, null)
        assertFalse(runs.terminals.value.getValue(workspace).shown)
        assertTrue(controller.focusTick > tick, "The panes take the keyboard back")
        controller.handle(AppShortcut.RUN_TOGGLE, null)
        assertTrue(runs.terminals.value.getValue(workspace).shown)
        controller.handle(AppShortcut.MAXIMIZE, null)
        assertFalse(runs.terminals.value.getValue(workspace).shown)
        assertNull(state.selection.maximized, "No pane was maximized")
    }

    @Test
    fun commandWStopsTheRunThatIsShownAndClosesThePaneWhenItIsMinimized() {
        store.split(state.selection.terminal!!, app.katty.core.SplitDirection.ROW)
        run("sleep 30")

        controller.handle(AppShortcut.CLOSE_TERMINAL, null)
        assertEquals(workspace, controller.stoppingRun, "A command that runs is asked about first")
        assertTrue(controller.blockingDialog)
        controller.answerStopRun(true)
        assertNull(runs.terminals.value[workspace], "The run that is shown is stopped")
        assertEquals(2, state.selectedWorkspace!!.layout.terminals().size, "Not the pane under it")

        run("sleep 30")
        controller.handle(AppShortcut.RUN_TOGGLE, null)
        controller.handle(AppShortcut.CLOSE_TERMINAL, null)
        assertNotNull(runs.terminals.value[workspace], "A run that is minimized is not stopped by the keys")
        assertEquals(1, state.selectedWorkspace!!.layout.terminals().size, "The selected pane is closed, as always")
    }

    @Test
    fun commandShiftLRunsTheCommandAgain() {
        store.split(state.selection.terminal!!, app.katty.core.SplitDirection.ROW)
        val restarted = mutableListOf<app.katty.core.TerminalId>()
        val controller = WindowController(store, CoroutineScope(Dispatchers.Unconfined), platform, { restarted += it }, {}, {}, runs = runs)
        controller.handle(AppShortcut.RUN, null)
        controller.answerRun("sleep 30")
        val first = runs.terminals.value.getValue(workspace)

        controller.handle(AppShortcut.RESTART_TERMINAL, null)
        assertEquals(workspace, controller.restartingRun, "A command that runs is asked about first")
        assertTrue(controller.blockingDialog)
        controller.answerRestartRun(false)
        assertEquals(first.generation, runs.terminals.value.getValue(workspace).generation, "Cancelled: it still runs")

        controller.handle(AppShortcut.RESTART_TERMINAL, null)
        controller.answerRestartRun(true)

        val second = runs.terminals.value.getValue(workspace)
        assertEquals("sleep 30", second.command)
        assertEquals(first.directory, second.directory)
        assertTrue(second.generation > first.generation, "Another session")
        runBlocking { withTimeout(15_000) { first.session!!.exited.first { it != null } } }
        assertEquals(emptyList(), restarted, "Not the pane under it")
    }

    @Test
    fun aCommandThatEndedRunsAgainAtOnce() {
        run("exit 3")
        ended(workspace)
        val first = runs.terminals.value.getValue(workspace)

        controller.handle(AppShortcut.RESTART_TERMINAL, null)

        assertNull(controller.restartingRun, "A command that ended is not asked about")
        assertTrue(runs.terminals.value.getValue(workspace).generation > first.generation)
    }

    @Test
    fun whileTheRunIsShownWhatActsOnATerminalActsOnItAndWhatActsOnThePanesDoesNothing() {
        store.split(state.selection.terminal!!, app.katty.core.SplitDirection.ROW)
        val pane = state.selection.terminal!!
        val paneSession = TerminalSession.start(80, 5, command = sh("echo pane; sleep 30"), directory = tmpDirectory, shellIntegration = false)
        try {
            controller.handles = mapOf(pane to app.katty.terminals.TerminalHandle(pane, paneSession))
            runs.setVisible(workspace)
            run("echo run; sleep 30")
            val runSession = runs.terminals.value.getValue(workspace).session!!
            fun text(session: TerminalSession) = session.frames.value.rows.joinToString("\n") { row -> row.cells.joinToString("") { it.text } }
            runBlocking { withTimeout(15_000) { runSession.frames.first { "run" in text(runSession) } } }
            runBlocking { withTimeout(15_000) { paneSession.frames.first { "pane" in text(paneSession) } } }
            assertEquals(runSession, controller.selectedSession(), "The terminal's own commands of the palette act on the run")

            controller.handle(AppShortcut.CLEAR_SCREEN, null)
            controller.handle(AppShortcut.SPLIT_MAIN, null)
            controller.handle(AppShortcut.FOCUS_LEFT, null)
            controller.handle(AppShortcut.RENAME_TERMINAL, null)

            runBlocking { withTimeout(15_000) { runSession.frames.first { "run" !in text(runSession) } } }
            assertTrue("pane" in text(paneSession), "The pane under it is not cleared")
            assertEquals(2, state.selectedWorkspace!!.layout.terminals().size, "Nothing is split")
            assertEquals(pane, state.selection.terminal)
            assertNull(controller.renamingTerminal)

            controller.handle(AppShortcut.FIND, null)
            assertEquals(workspace, controller.findingRun, "The find bar of the run opens")
            assertNull(controller.findingTerminal)
            controller.handle(AppShortcut.RUN_TOGGLE, null)
            assertNull(controller.findingRun, "It closes with the run")
            assertEquals(paneSession, controller.selectedSession())
        } finally {
            paneSession.close()
        }
    }

    @Test
    fun theMenuOfAnotherWorkspaceRunsInItsTerminalAndGoesToIt() {
        val first = workspace
        val other = store.addWorkspace(state.selection.project!!, "other")!!
        val otherTerminal = state.projects.single().workspaces.first { it.id == other }.layout.terminals().single().id
        store.setDirectory(otherTerminal, "/usr")
        store.selectWorkspace(first)

        controller.openRun(other)

        assertEquals(other, workspace)
        assertEquals("/usr", controller.runRequest!!.directory)
    }

    @Test
    fun closingAWorkspaceWhoseCommandRunsAsksAndOneThatEndedDoesNot() {
        run("sleep 30")

        controller.closeWorkspace(workspace)
        assertEquals(workspace, controller.closingWorkspace)
        controller.closingWorkspace = null

        controller.handle(AppShortcut.CLOSE_TERMINAL, null)
        controller.answerStopRun(true)
        run("exit 0")
        ended(workspace)
        controller.closeWorkspace(workspace)
        assertNull(controller.closingWorkspace)
        assertTrue(state.projects.single().workspaces.isEmpty())
    }

    @Test
    fun quittingAsksWhileACommandRuns() {
        run("sleep 30")

        assertEquals(1, controller.activeRuns().size)
        controller.handle(AppShortcut.CLOSE_TERMINAL, null)
        controller.answerStopRun(true)
        assertTrue(controller.activeRuns().isEmpty())
    }

    @Test
    fun stoppingACommandThatRunsCanBeCancelledAndOneThatEndedClosesAtOnce() {
        run("sleep 30")
        controller.handle(AppShortcut.CLOSE_TERMINAL, null)

        controller.answerStopRun(false)

        assertNull(controller.stoppingRun)
        assertTrue(runs.active(workspace), "Cancelled: it still runs")
        controller.handle(AppShortcut.CLOSE_TERMINAL, null)
        controller.answerStopRun(true)

        run("exit 4")
        ended(workspace)
        controller.handle(AppShortcut.CLOSE_TERMINAL, null)
        assertNull(controller.stoppingRun, "A command that ended is not asked about")
        assertNull(runs.terminals.value[workspace])
    }

    @Test
    fun commandEnterRunsTheCommandMinimized() {
        val tick = controller.focusTick
        controller.handle(AppShortcut.RUN, null)

        controller.answerRun("sleep 30", minimized = true)

        val run = runs.terminals.value.getValue(workspace)
        assertFalse(run.shown, "Minimized into the header of the workspace")
        assertTrue(controller.focusTick > tick, "The panes keep the keyboard")
        assertEquals("sleep 30", state.selectedWorkspace!!.runCommand)
        // And over a command that runs, after the same question.
        controller.handle(AppShortcut.RUN, null)
        controller.answerRun("sleep 31", minimized = true)
        controller.answerRunConfirmation(true)
        assertFalse(runs.terminals.value.getValue(workspace).shown)
        assertEquals("sleep 31", runs.terminals.value.getValue(workspace).command)
    }

    @Test
    fun thePaletteHasTheRunAndWhatActsOnIt() {
        val labels = { paletteCommands(state, emptyMap(), titleOf = { "zsh" }, handlers = controller.paletteHandlers(worktree = null, closed = null)).entries.map { it.label } }
        assertTrue("Run a command…" in labels())
        assertFalse("Stop run" in labels(), "Nothing to stop")

        run("sleep 30")

        assertTrue("Stop run" in labels() && "Minimize run terminal" in labels() && "Run the command again" in labels())
        assertFalse("Split right" in labels(), "What acts on the panes is not offered while the run is shown")
        assertTrue("Clear screen and scrollback" in labels())
        controller.handle(AppShortcut.RUN_TOGGLE, null)
        assertTrue("Show run terminal" in labels())
        assertFalse("Stop run" in labels(), "Only the run that is shown is stopped")
        assertTrue("Split right" in labels())
    }
}
