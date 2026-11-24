@file:OptIn(ExperimentalTestApi::class)

package app.katty.screens

import app.katty.ShortcutMap
import app.katty.Shortcuts
import app.katty.MAC_SHORTCUTS
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.requestFocus
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.input.key.Key
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.runComposeUiTest
import app.katty.core.KattyStore
import app.katty.core.SplitDirection
import app.katty.core.terminals
import app.katty.interactiveShell
import app.katty.interactiveShellName
import app.katty.longCommand
import app.katty.longCommandName
import app.katty.printfLine
import app.katty.terminal.TerminalSession
import app.katty.terminals.SessionRegistry
import app.katty.tmpDirectory
import app.katty.ui.KattyTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WorkspaceAreaTest {
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
    private val registry = SessionRegistry(
        store, scope, starter = kotlinx.coroutines.Dispatchers.Unconfined, loadTheme = { null },
        // An interactive shell with no startup files (zsh, PowerShell on Windows): what a user has, so that its name is the terminal's.
        start = { spec, _, _ -> TerminalSession.start(80, 10, command = interactiveShell, directory = spec.directory, shellIntegration = false) },
    )

    @AfterTest
    fun close() {
        registry.close()
        scope.cancel()
    }

    private val closed = mutableListOf<app.katty.core.TerminalId>()
    private var lastQuestion: app.katty.core.CommandQuestion? = null
    private val explained = mutableListOf<app.katty.core.TerminalId>()
    private val askedAbout = mutableListOf<Long>()
    private var infos by androidx.compose.runtime.mutableStateOf(emptyMap<app.katty.core.TerminalId, app.katty.core.AgentInfo>())
    private var renaming by androidx.compose.runtime.mutableStateOf<app.katty.core.TerminalId?>(null)
    // Something open over the terminals (a dialog, the palette): they do not take the keyboard then.
    private var overlay by androidx.compose.runtime.mutableStateOf(false)

    private fun androidx.compose.ui.test.ComposeUiTest.show() {
        registry.sync(store.state.value)
        setContent {
            KattyTheme(dark = false) {
                val state by store.state.collectAsState()
                val handles by registry.handles.collectAsState()
                androidx.compose.runtime.LaunchedEffect(state.projects) { registry.sync(state) }
                val workspace = state.selectedWorkspace
                if (workspace != null) {
                    WorkspaceArea(
                        workspace, state.selection.terminal, handles, state.settings, store, flipped = false, focusTerminals = !overlay, maximized = state.selection.maximized, infos = infos,
                        actions = PaneActions(
                            renaming = renaming,
                            onRename = { renaming = it },
                            onRenamed = { id, name -> renaming = null; if (name != null) store.renameTerminal(id, name) },
                            onClose = { closed += it },
                            destinations = emptyList(),
                            onMove = { _, _ -> },
                            lastCommandQuestion = { lastQuestion },
                            onAskAboutLastCommand = { explained += it },
                            onAskAboutCommand = { _, mark -> askedAbout += mark },
                        ),
                    )
                }
            }
        }
    }

    @Test
    fun theFocusedTerminalTakesTheKeyboardBackWhenTheFocusTickChanges() = runComposeUiTest {
        store.addProject(tmpDirectory)
        registry.sync(store.state.value)
        var tick by androidx.compose.runtime.mutableStateOf(0)
        setContent {
            KattyTheme(dark = false) {
                val state by store.state.collectAsState()
                val handles by registry.handles.collectAsState()
                val workspace = state.selectedWorkspace
                androidx.compose.foundation.layout.Column {
                    // What had the keyboard: a palette's field, a rename.
                    androidx.compose.foundation.text.BasicTextField("", {}, Modifier.testTag("other"))
                    if (workspace != null) {
                        WorkspaceArea(
                            workspace, state.selection.terminal, handles, state.settings, store, flipped = false, focusTerminals = true, focusTick = tick,
                            actions = PaneActions(renaming = null, onRename = {}, onRenamed = { _, _ -> }, onClose = {}, destinations = emptyList(), onMove = { _, _ -> }),
                        )
                    }
                }
            }
        }
        waitUntil { runCatching { onNode(isFocused()).assertExists() }.isSuccess && runCatching { onNodeWithTag("other").assertIsNotFocused() }.isSuccess }
        onNodeWithTag("other").requestFocus()
        onNodeWithTag("other").assertIsFocused()

        // The field closes, or the palette: nothing about the terminal changed, and it is the focused one all the same.
        tick++
        waitForIdle()

        waitUntil { runCatching { onNodeWithTag("other").assertIsNotFocused() }.isSuccess }
    }

    // How far the grid of the terminal starts from the edge of the pane, in pixels.
    private fun ComposeUiTest.gridOffsetFromTheEdge(commandMarks: Boolean, reporting: Boolean = false): Int {
        store.addProject(tmpDirectory)
        if (reporting) {
            val terminal = store.state.value.selection.terminal!!
            infos = mapOf(terminal to app.katty.core.AgentInfo(app.katty.core.AgentReport(app.katty.core.AgentState.WORKING, "claude-code"), 0))
        }
        store.updateSettings { it.copy(commandMarks = commandMarks) }
        show()
        val session = registry.handles.value.values.single().session!!
        // A line of full blocks, which fill their cells to the edge: the first of them is where the grid starts.
        session.sendText(printfLine("\\u2588\\u2588\\u2588\\u2588\\u2588\\u2588\\u2588\\u2588"))
        waitUntil(timeoutMillis = 10_000) { session.frames.value.rows.any { row -> row.cells.count { it.text == "█" } >= 8 } }
        waitForIdle()

        val image = onRoot().captureToImage().toPixelMap()
        // The header says what runs in the terminal: the shell, or the agent that reports.
        val headerBottom = (onAllNodes(hasContentDescription("Terminal ", substring = true)).onFirst().getBoundsInRoot().bottom.value * density.density).toInt()
        fun rgb(x: Int, y: Int) = image[x, y].let { (it.red * 255 + .5f).toInt() shl 16 or ((it.green * 255 + .5f).toInt() shl 8) or (it.blue * 255 + .5f).toInt() }
        val foreground = session.frames.value.foreground.rgb
        val accent = (app.katty.ui.KattyColors.Light.accent.let { (it.red * 255 + .5f).toInt() shl 16 or ((it.green * 255 + .5f).toInt() shl 8) or (it.blue * 255 + .5f).toInt() })
        // Under the header: the terminal and the border of the pane, which is the accent's, as the pane has the focus.
        val rows = (headerBottom until image.height)
        val block = rows.mapNotNull { y -> (0 until image.width).firstOrNull { rgb(it, y) == foreground } }.minOrNull() ?: error("No block was drawn")
        val border = rows.mapNotNull { y -> (0 until image.width).firstOrNull { rgb(it, y) == accent } }.minOrNull() ?: error("No border was drawn")

        return block - border
    }

    @Test
    fun aTerminalIsNotAgainstTheBorderOfItsPane() = runComposeUiTest {
        // The border is 2 dp, over the edge of the pane: the grid starts the padding after the edge, and not the border's width.
        assertEquals((TERMINAL_PADDING.value * density.density).toInt(), gridOffsetFromTheEdge(commandMarks = false), "The grid starts 4 dp from the edge of the pane")
    }

    @Test
    fun theGutterOfTheCommandMarksIsAtTheLeftOfThePaddingAndPushesTheGridByFiveDp() = runComposeUiTest {
        assertEquals(((TERMINAL_PADDING.value + 5) * density.density).toInt(), gridOffsetFromTheEdge(commandMarks = true), "The grid starts 5 dp further")
    }

    @Test
    fun aProgramThatReportsItsStateHasTheOrdinaryMarginAndNoGutter() = runComposeUiTest {
        // An agent fills the terminal: its room is the ordinary margin, with the marks on.
        assertEquals((TERMINAL_PADDING.value * density.density).toInt(), gridOffsetFromTheEdge(commandMarks = true, reporting = true), "The grid starts 4 dp from the edge")
    }

    @Test
    fun theHeaderSaysHowManyTerminalsThereAre() = runComposeUiTest {
        store.addProject(tmpDirectory)
        show()

        onNodeWithText("1 terminal").assertExists()
    }

    @Test
    fun theSplitButtonsSplitTheFocusedTerminal() = runComposeUiTest {
        store.addProject(tmpDirectory)
        show()

        onNodeWithContentDescription("Split right").performClick()
        waitForIdle()
        onNodeWithText("2 terminals").assertExists()
        onNodeWithContentDescription("Split down").performClick()
        waitForIdle()

        onNodeWithText("3 terminals").assertExists()
        val tree = store.state.value.selectedWorkspace!!.layout
        assertEquals(3, tree.terminals().size)
        assertEquals(SplitDirection.ROW, (tree as app.katty.core.PaneNode.Split).direction)
    }

    @Test
    fun theTitleOfATerminalIsTheNameOfTheAgentThatReportsAndTheShellsOtherwise() = runComposeUiTest {
        store.addProject(tmpDirectory)
        val terminal = store.state.value.selection.terminal!!
        show()
        onNodeWithContentDescription("Terminal $interactiveShellName").assertExists()
        onNodeWithText("Idle").assertExists()

        infos = mapOf(terminal to app.katty.core.AgentInfo(app.katty.core.AgentReport(app.katty.core.AgentState.NEEDS_INPUT, "codex", "Apply this patch?"), 0))
        waitForIdle()

        onNodeWithContentDescription("Terminal Codex").assertExists()
        onNodeWithText("Needs input").assertExists()
    }

    @Test
    fun doubleClickingTheNameOfATerminalRenamesIt() = runComposeUiTest {
        store.addProject(tmpDirectory)
        val terminal = store.state.value.selection.terminal!!
        show()

        onNodeWithContentDescription("Terminal $interactiveShellName").performMouseInput { doubleClick() }
        waitForIdle()
        onNodeWithText(interactiveShellName).performTextInput("build")
        onNodeWithText("build").performKeyInput { pressKey(Key.Enter) }
        waitForIdle()

        assertEquals("build", store.state.value.locate(terminal)!!.terminal.name)
    }

    @Test
    fun aTerminalThatTheUserNamedKeepsItsNameWhateverRuns() = runComposeUiTest {
        store.addProject(tmpDirectory)
        val terminal = store.state.value.selection.terminal!!
        store.renameTerminal(terminal, "build")
        infos = mapOf(terminal to app.katty.core.AgentInfo(app.katty.core.AgentReport(app.katty.core.AgentState.WORKING, "claude-code"), 0))
        show()

        onNodeWithContentDescription("Terminal build").assertExists()
        onNodeWithText("Working").assertExists()
    }

    @Test
    fun theMenuOfAPaneSplitsRenamesAndClosesItsTerminal() = runComposeUiTest {
        store.addProject(tmpDirectory)
        val terminal = store.state.value.selection.terminal!!
        show()

        onNodeWithContentDescription("Pane menu").performClick()
        onNodeWithText("Split down").performClick()
        waitForIdle()
        assertEquals(2, store.state.value.selectedWorkspace!!.layout.terminals().size)

        // Two panes now, each with its menu: the first one is the terminal that was there.
        onAllNodesWithContentDescription("Pane menu")[0].performClick()
        onNodeWithText("Close terminal").performClick()
        waitForIdle()
        assertEquals(listOf(terminal), closed)
    }

    @Test
    fun aRightClickOnTheHeaderOpensTheMenuOfTheTerminalAndFocusesIt() = runComposeUiTest {
        store.addProject(tmpDirectory)
        val first = store.state.value.selection.terminal!!
        val second = store.split(first, SplitDirection.ROW)!!
        show()
        assertEquals(second, store.state.value.selection.terminal)

        // On the title of the first pane, which is not the focused one.
        onAllNodesWithContentDescription("Terminal $interactiveShellName")[0].performMouseInput { rightClick() }
        waitForIdle()

        onNodeWithText("Rename terminal").assertExists()
        onNodeWithText("Split right").assertExists()
        onNodeWithText("Close terminal").assertExists()
        assertEquals(first, store.state.value.selection.terminal)
    }

    // The AI is asked about the last command once it has ended, why it failed or what its result says, and the menu asks which when it opens.
    @Test
    fun aRightClickOffersToAskTheAiAboutTheLastCommand() = runComposeUiTest {
        store.addProject(tmpDirectory)
        val terminal = store.state.value.selection.terminal!!
        show()
        waitUntil(timeoutMillis = 5_000) { registry.handles.value[terminal]?.session != null }
        waitForIdle()

        onRoot().performMouseInput { rightClick(center) }
        waitForIdle()
        onNodeWithText("Copy").assertExists()
        onAllNodesWithText("Ask AI why it failed").assertCountEquals(0)
        onAllNodesWithText("Explain result with AI").assertCountEquals(0)
        // The menu is a root of its own: Escape goes to it.
        onAllNodes(androidx.compose.ui.test.isRoot()).onLast().performKeyInput { pressKey(Key.Escape) }
        waitForIdle()
        onAllNodes(androidx.compose.ui.test.isRoot()).assertCountEquals(1)

        lastQuestion = app.katty.core.CommandQuestion.EXPLAIN
        onRoot().performMouseInput { rightClick(center) }
        waitForIdle()
        onAllNodesWithText("Ask AI why it failed").assertCountEquals(0)
        onNodeWithText("Explain result with AI").performClick()
        waitForIdle()
        assertEquals(listOf(terminal), explained)

        lastQuestion = app.katty.core.CommandQuestion.WHY_FAILED
        onRoot().performMouseInput { rightClick(center) }
        waitForIdle()
        onAllNodesWithText("Explain result with AI").assertCountEquals(0)
        onNodeWithText("Ask AI why it failed").performClick()
        waitForIdle()
        assertEquals(listOf(terminal, terminal), explained)
    }

    // The menu of the bar of a command is not under the bar's tooltip, which goes while it is open; it selects the command's output, as a click on
    // the bar does, and asks the AI about it.
    /**
     * A shell's command that succeeded, in the only pane, and where its bar is: what a shell with the integration sends around a command (this one has
     * none) and at the next prompt, and its output: blocks, which show where the grid is.
     */
    private fun ComposeUiTest.aCommandAndItsBar(): Pair<TerminalSession, androidx.compose.ui.geometry.Offset> {
        store.addProject(tmpDirectory)
        val terminal = store.state.value.selection.terminal!!
        show()
        waitUntil(timeoutMillis = 5_000) { registry.handles.value[terminal]?.session != null }
        val session = registry.handles.value[terminal]!!.session!!
        session.sendText(printfLine("\\033]133;A\\007$ ls\\r\\n\\033]133;B\\007\\033]133;C\\007\\u2588\\u2588\\u2588\\u2588\\r\\n\\033]133;D;0\\007\\033]133;A\\007"))
        waitUntil(timeoutMillis = 10_000) {
            val frame = session.frames.value
            frame.commandMarks.any { it.finishedAt != null } && frame.rows.any { row -> row.cells.count { it.text == "█" } == 4 }
        }
        waitForIdle()

        // The blocks are the longest run of the foreground on a line of pixels: the bar is in the gutter at their left, 5 dp wide, which is at the left
        // of the padding.
        val image = onRoot().captureToImage().toPixelMap()
        val foreground = session.frames.value.foreground.rgb
        fun rgb(x: Int, y: Int) = image[x, y].let { (it.red * 255 + .5f).toInt() shl 16 or ((it.green * 255 + .5f).toInt() shl 8) or (it.blue * 255 + .5f).toInt() }
        fun longestRun(y: Int): Pair<Int, Int> {
            var best = 0 to 0
            var start = -1
            for (x in 0..image.width) {
                if (x < image.width && rgb(x, y) == foreground) {
                    if (start < 0) start = x
                } else if (start >= 0) {
                    if (x - start > best.second) best = start to x - start
                    start = -1
                }
            }
            return best
        }
        // Under the header, whose lines are of that color across the pane.
        val headerBottom = (onAllNodes(hasContentDescription("Terminal ", substring = true)).onFirst().getBoundsInRoot().bottom.value * density.density).toInt()
        val runs = (0 until image.height).map { if (it < headerBottom) 0 to 0 else longestRun(it).takeIf { run -> run.second < image.width / 4 } ?: (0 to 0) }
        val longest = runs.maxOf { it.second }
        val blockRows = runs.indices.filter { runs[it].second == longest }
        val bar = androidx.compose.ui.geometry.Offset(runs[blockRows.first()].first - (TERMINAL_PADDING.value + 2.5f) * density.density, blockRows.average().toFloat())

        return session to bar
    }

    private fun ComposeUiTest.hover(at: androidx.compose.ui.geometry.Offset) {
        onAllNodes(androidx.compose.ui.test.isRoot()).onFirst().performMouseInput {
            moveTo(at + androidx.compose.ui.geometry.Offset(0f, 1f))
            moveTo(at)
        }
        waitForIdle()
    }

    @Test
    fun theMenuOfTheBarOfACommandHidesItsTooltipAndSelectsItsOutput() = runComposeUiTest {
        val (session, bar) = aCommandAndItsBar()
        onRoot().performMouseInput {
            moveTo(bar + androidx.compose.ui.geometry.Offset(0f, 1f))
            moveTo(bar)
        }
        waitForIdle()
        onNodeWithText("Succeeded").assertExists()

        // The tooltip is a root of its own: the window's is the first.
        onAllNodes(androidx.compose.ui.test.isRoot()).onFirst().performMouseInput { rightClick(bar) }
        waitForIdle()
        onNodeWithText("Select text").assertExists()
        onNodeWithText("Explain result with AI").assertExists()
        onAllNodesWithText("Succeeded").assertCountEquals(0)

        onNodeWithText("Select text").performClick()
        waitUntil(timeoutMillis = 5_000) { session.selectedText() == "████" }
    }

    // The tooltip of the bar is a popup over the window: a dialog that opens while the pointer is on the bar (the bar's menu asks the AI, and its
    // answer opens over the pane) had it over it.
    @Test
    fun theTooltipOfTheBarGoesWhileSomethingIsOpenOverTheTerminals() = runComposeUiTest {
        val (_, bar) = aCommandAndItsBar()
        hover(bar)
        onNodeWithText("Succeeded").assertExists()

        overlay = true
        waitForIdle()
        onAllNodesWithText("Succeeded").assertCountEquals(0)

        overlay = false
        hover(bar)
        onNodeWithText("Succeeded").assertExists()
    }

    @Test
    fun aRightClickInTheTerminalOffersItsTextAndEveryItemOfTheMenuOfThePane() = runComposeUiTest {
        store.addProject(tmpDirectory)
        val terminal = store.state.value.selection.terminal!!
        show()
        waitUntil(timeoutMillis = 5_000) { registry.handles.value[terminal]?.session != null }
        waitForIdle()

        // In the middle of the only pane: its terminal, whose shell did not ask for the mouse.
        onRoot().performMouseInput { rightClick(center) }
        waitForIdle()

        // Nothing is selected: Copy is there, and cannot be chosen.
        onNodeWithText("Copy").assertExists().assertIsNotEnabled()
        onNodeWithText("Paste").assertExists()
        listOf("Rename terminal", "Split right", "Split down", "Color scheme", "Copy working directory", "Export history…", "Restart terminal", "Close terminal").forEach {
            onNodeWithText(it).assertExists()
        }
        onNodeWithText("Rename terminal").performClick()
        waitForIdle()
        assertEquals(terminal, renaming)
    }

    @Test
    fun aTerminalIsCalledAfterTheProgramThatRunsInItUntilItEnds() = runComposeUiTest {
        store.addProject(tmpDirectory)
        val terminal = store.state.value.selection.terminal!!
        show()
        onNodeWithContentDescription("Terminal $interactiveShellName").assertExists()

        registry.handles.value.getValue(terminal).session!!.sendText("$longCommand\r")
        waitUntil(timeoutMillis = 15_000) { onAllNodesWithContentDescription("Terminal $longCommandName").fetchSemanticsNodes().isNotEmpty() }
        registry.handles.value.getValue(terminal).session!!.sendText("\u0003")

        waitUntil(timeoutMillis = 15_000) { onAllNodesWithContentDescription("Terminal $interactiveShellName").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun aNameThatTheUserGaveStaysWhateverRuns() = runComposeUiTest {
        store.addProject(tmpDirectory)
        val terminal = store.state.value.selection.terminal!!
        store.renameTerminal(terminal, "build")
        show()

        registry.handles.value.getValue(terminal).session!!.sendText("$longCommand\r")
        waitUntil(timeoutMillis = 15_000) { registry.handles.value.getValue(terminal).processName.value == longCommandName }
        waitForIdle()

        onNodeWithContentDescription("Terminal build").assertExists()
        registry.handles.value.getValue(terminal).session!!.sendText("\u0003")
    }

    @Test
    fun theMenuOffersToGoBackToTheProcessNameOnlyForATerminalThatHasANameOfItsOwn() = runComposeUiTest {
        store.addProject(tmpDirectory)
        val terminal = store.state.value.selection.terminal!!
        show()

        onNodeWithContentDescription("Pane menu").performClick()
        assertEquals(0, onAllNodesWithText("Use process name").fetchSemanticsNodes().size)
        onNodeWithText("Rename terminal").assertExists()
        onNodeWithText("Split right").performClick()
        waitForIdle()
        store.renameTerminal(terminal, "build")
        waitForIdle()

        onAllNodesWithContentDescription("Pane menu")[0].performClick()
        onNodeWithText("Use process name").performClick()
        waitForIdle()

        assertEquals(null, store.state.value.locate(terminal)!!.terminal.name)
        onAllNodesWithContentDescription("Terminal $interactiveShellName")[0].assertExists()
    }

    @Test
    fun theNameOfATerminalIsEditedInItsHeader() = runComposeUiTest {
        store.addProject(tmpDirectory)
        val terminal = store.state.value.selection.terminal!!
        show()

        onNodeWithContentDescription("Pane menu").performClick()
        onNodeWithText("Rename terminal").performClick()
        waitForIdle()
        onNodeWithText(interactiveShellName).performTextInput("build")
        onNodeWithText("build").performKeyInput { pressKey(Key.Enter) }
        waitForIdle()

        assertEquals("build", store.state.value.locate(terminal)!!.terminal.name)
        onNodeWithContentDescription("Terminal build").assertExists()
    }

    @Test
    fun aWorkspaceWithOnePaneHasNothingToMaximizeAPaneOver() = runComposeUiTest {
        store.addProject(tmpDirectory)
        val first = store.state.value.selection.terminal!!
        show()

        onAllNodesWithContentDescription("Maximize pane").assertCountEquals(0)
        // The menu of the pane is still there: only the button goes.
        onNodeWithContentDescription("Pane menu").assertExists()

        store.split(first, SplitDirection.ROW)
        waitForIdle()
        onAllNodesWithContentDescription("Maximize pane").assertCountEquals(2)
    }

    @Test
    fun aPaneGrowsToItsMaximizedPlaceAndGoesBackToItsOwn() = runComposeUiTest {
        store.addProject(tmpDirectory)
        val first = store.state.value.selection.terminal!!
        store.split(first, SplitDirection.ROW)
        show()
        val columns = { registry.handles.value[first]!!.session!!.frames.value.cols }
        // The pane gives its session its size at its first layout, and a frame has it a moment later: the width to start from is that one, not
        // the 80 columns that a session starts with.
        waitForIdle()
        val sized = System.nanoTime() + 5_000_000_000L
        while (columns() == 80 && System.nanoTime() < sized) Thread.sleep(20)
        mainClock.autoAdvance = false
        val start = onAllNodesWithContentDescription("Maximize pane")[0].fetchSemanticsNode().boundsInRoot.left
        val startColumns = columns()

        onAllNodesWithContentDescription("Maximize pane")[0].performClick()
        mainClock.advanceTimeBy(100)
        val midwayButton = onNodeWithContentDescription("Restore pane").fetchSemanticsNode().boundsInRoot
        val midway = midwayButton.left
        // The terminal has its final size from the first frame of the way: it is not resized as the pane grows.
        val deadline = System.nanoTime() + 5_000_000_000L
        while (columns() == startColumns && System.nanoTime() < deadline) Thread.sleep(20)
        val midwayColumns = columns()
        mainClock.advanceTimeBy(400)
        val endButton = onNodeWithContentDescription("Restore pane").fetchSemanticsNode().boundsInRoot
        val end = endButton.left
        assertTrue(start < midway && midway < end, "$start < $midway < $end")
        assertTrue(midwayColumns != startColumns, "$startColumns, $midwayColumns")
        Thread.sleep(200)
        assertEquals(midwayColumns, columns())
        // What is drawn is not scaled: the buttons of the header have their size all the way.
        assertEquals(endButton.height, midwayButton.height)

        onNodeWithContentDescription("Restore pane").performClick()
        mainClock.advanceTimeBy(100)
        val back = onNodeWithContentDescription("Restore pane").fetchSemanticsNode().boundsInRoot.left
        // Still floating, and not yet at its place: the pane of the tree is hollow until it is there.
        assertTrue(back < end && back > start, "$start < $back < $end")
        mainClock.advanceTimeBy(400)
        assertEquals(2, onAllNodesWithContentDescription("Maximize pane").fetchSemanticsNodes().size)
    }

    @Test
    fun aMaximizedTerminalFloatsOverTheOthersAndCanBePutBack() = runComposeUiTest {
        store.addProject(tmpDirectory)
        val first = store.state.value.selection.terminal!!
        store.split(first, SplitDirection.ROW)
        show()

        // Two panes, each with the button to maximize it.
        onAllNodesWithContentDescription("Maximize pane")[0].performClick()
        waitForIdle()

        assertEquals(first, store.state.value.selection.maximized)
        onNodeWithContentDescription("Restore pane").performClick()
        waitForIdle()
        assertEquals(null, store.state.value.selection.maximized)
        assertEquals(2, onAllNodesWithContentDescription("Maximize pane").fetchSemanticsNodes().size)
    }

    @Test
    fun everyTerminalOfTheWorkspaceHasItsShell() = runComposeUiTest {
        store.addProject(tmpDirectory)
        store.split(store.state.value.selection.terminal!!, SplitDirection.ROW)
        show()
        waitForIdle()

        val handles = registry.handles.value
        assertEquals(2, handles.size)
        handles.values.forEach { assertNotNull(it.session) }
    }

    @Test
    fun theTerminalThatTheStoreFocusesKeepsTheFocusWhenTheOthersAreShown() = runComposeUiTest {
        store.addProject(tmpDirectory)
        val first = store.state.value.selection.terminal!!
        val second = store.split(first, SplitDirection.ROW)!!
        val third = store.split(second, SplitDirection.COLUMN)!!
        show()
        waitForIdle()
        mainClock.advanceTimeBy(500)
        waitForIdle()

        // A terminal that took the focus when it was shown would have made itself the selected one.
        assertEquals(third, store.state.value.selection.terminal)
    }
}
