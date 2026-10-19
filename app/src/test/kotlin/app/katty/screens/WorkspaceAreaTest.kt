@file:OptIn(ExperimentalTestApi::class)

package app.katty.screens

import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
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
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.input.key.Key
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.runComposeUiTest
import app.katty.core.KattyStore
import app.katty.core.SplitDirection
import app.katty.core.terminals
import app.katty.terminal.TerminalSession
import app.katty.terminals.SessionRegistry
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
    private var counter = 0
    private val store = KattyStore(newId = { "id${counter++}" })
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val registry = SessionRegistry(
        store, scope, loadTheme = { null },
        // An interactive zsh with no startup files: what a user has, so that its name is the terminal's.
        start = { spec, _ -> TerminalSession.start(80, 10, command = listOf("/bin/zsh", "-f", "-i"), directory = spec.directory, shellIntegration = false) },
    )

    @AfterTest
    fun close() {
        registry.close()
        scope.cancel()
    }

    private val closed = mutableListOf<app.katty.core.TerminalId>()
    private var infos by androidx.compose.runtime.mutableStateOf(emptyMap<app.katty.core.TerminalId, app.katty.core.AgentInfo>())
    private var renaming by androidx.compose.runtime.mutableStateOf<app.katty.core.TerminalId?>(null)

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
                        workspace, state.selection.terminal, handles, state.settings, store, flipped = false, focusTerminals = true, maximized = state.selection.maximized, infos = infos,
                        actions = PaneActions(
                            renaming = renaming,
                            onRename = { renaming = it },
                            onRenamed = { id, name -> renaming = null; if (name != null) store.renameTerminal(id, name) },
                            onClose = { closed += it },
                            destinations = emptyList(),
                            onMove = { _, _ -> },
                        ),
                    )
                }
            }
        }
    }

    @Test
    fun theFocusedTerminalTakesTheKeyboardBackWhenTheFocusTickChanges() = runComposeUiTest {
        store.addProject("/tmp")
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

    @Test
    fun aTerminalIsNotAgainstTheBorderOfItsPane() = runComposeUiTest {
        store.addProject("/tmp")
        show()
        val session = registry.handles.value.values.single().session!!
        // A line of full blocks, which fill their cells to the edge: the first of them is where the grid starts.
        session.sendText("printf '\\u2588\\u2588\\u2588\\u2588\\u2588\\u2588\\u2588\\u2588'\r")
        waitUntil(timeoutMillis = 10_000) { session.frames.value.rows.any { row -> row.cells.count { it.text == "█" } >= 8 } }
        waitForIdle()

        val image = onRoot().captureToImage().toPixelMap()
        val headerBottom = (onNodeWithContentDescription("Terminal zsh").getBoundsInRoot().bottom.value * density.density).toInt()
        fun rgb(x: Int, y: Int) = image[x, y].let { (it.red * 255 + .5f).toInt() shl 16 or ((it.green * 255 + .5f).toInt() shl 8) or (it.blue * 255 + .5f).toInt() }
        val foreground = session.frames.value.foreground.rgb
        val accent = (app.katty.ui.KattyColors.Light.accent.let { (it.red * 255 + .5f).toInt() shl 16 or ((it.green * 255 + .5f).toInt() shl 8) or (it.blue * 255 + .5f).toInt() })
        // Under the header: the terminal and the border of the pane, which is the accent's, as the pane has the focus.
        val rows = (headerBottom until image.height)
        val block = rows.mapNotNull { y -> (0 until image.width).firstOrNull { rgb(it, y) == foreground } }.minOrNull() ?: error("No block was drawn")
        val border = rows.mapNotNull { y -> (0 until image.width).firstOrNull { rgb(it, y) == accent } }.minOrNull() ?: error("No border was drawn")

        // The border is 2 dp, over the edge of the pane: the grid starts the padding after the edge, and not the border's width.
        assertEquals((TERMINAL_PADDING.value * density.density).toInt(), block - border, "The grid starts 4 dp from the edge of the pane")
    }

    @Test
    fun theHeaderSaysHowManyTerminalsThereAre() = runComposeUiTest {
        store.addProject("/tmp")
        show()

        onNodeWithText("1 terminal").assertExists()
    }

    @Test
    fun theSplitButtonsSplitTheFocusedTerminal() = runComposeUiTest {
        store.addProject("/tmp")
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
        store.addProject("/tmp")
        val terminal = store.state.value.selection.terminal!!
        show()
        onNodeWithContentDescription("Terminal zsh").assertExists()
        onNodeWithText("Idle").assertExists()

        infos = mapOf(terminal to app.katty.core.AgentInfo(app.katty.core.AgentReport(app.katty.core.AgentState.NEEDS_INPUT, "codex", "Apply this patch?"), 0))
        waitForIdle()

        onNodeWithContentDescription("Terminal Codex").assertExists()
        onNodeWithText("Needs input").assertExists()
    }

    @Test
    fun doubleClickingTheNameOfATerminalRenamesIt() = runComposeUiTest {
        store.addProject("/tmp")
        val terminal = store.state.value.selection.terminal!!
        show()

        onNodeWithContentDescription("Terminal zsh").performMouseInput { doubleClick() }
        waitForIdle()
        onNodeWithText("zsh").performTextInput("build")
        onNodeWithText("build").performKeyInput { pressKey(Key.Enter) }
        waitForIdle()

        assertEquals("build", store.state.value.locate(terminal)!!.terminal.name)
    }

    @Test
    fun aTerminalThatTheUserNamedKeepsItsNameWhateverRuns() = runComposeUiTest {
        store.addProject("/tmp")
        val terminal = store.state.value.selection.terminal!!
        store.renameTerminal(terminal, "build")
        infos = mapOf(terminal to app.katty.core.AgentInfo(app.katty.core.AgentReport(app.katty.core.AgentState.WORKING, "claude-code"), 0))
        show()

        onNodeWithContentDescription("Terminal build").assertExists()
        onNodeWithText("Working").assertExists()
    }

    @Test
    fun theMenuOfAPaneSplitsRenamesAndClosesItsTerminal() = runComposeUiTest {
        store.addProject("/tmp")
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
        store.addProject("/tmp")
        val first = store.state.value.selection.terminal!!
        val second = store.split(first, SplitDirection.ROW)!!
        show()
        assertEquals(second, store.state.value.selection.terminal)

        // On the title of the first pane, which is not the focused one.
        onAllNodesWithContentDescription("Terminal zsh")[0].performMouseInput { rightClick() }
        waitForIdle()

        onNodeWithText("Rename terminal").assertExists()
        onNodeWithText("Split right").assertExists()
        onNodeWithText("Close terminal").assertExists()
        assertEquals(first, store.state.value.selection.terminal)
    }

    @Test
    fun aTerminalIsCalledAfterTheProgramThatRunsInItUntilItEnds() = runComposeUiTest {
        store.addProject("/tmp")
        val terminal = store.state.value.selection.terminal!!
        show()
        onNodeWithContentDescription("Terminal zsh").assertExists()

        registry.handles.value.getValue(terminal).session!!.sendText("sleep 30\r")
        waitUntil(timeoutMillis = 15_000) { onAllNodesWithContentDescription("Terminal sleep").fetchSemanticsNodes().isNotEmpty() }
        registry.handles.value.getValue(terminal).session!!.sendText("\u0003")

        waitUntil(timeoutMillis = 15_000) { onAllNodesWithContentDescription("Terminal zsh").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun aNameThatTheUserGaveStaysWhateverRuns() = runComposeUiTest {
        store.addProject("/tmp")
        val terminal = store.state.value.selection.terminal!!
        store.renameTerminal(terminal, "build")
        show()

        registry.handles.value.getValue(terminal).session!!.sendText("sleep 30\r")
        waitUntil(timeoutMillis = 15_000) { registry.handles.value.getValue(terminal).processName.value == "sleep" }
        waitForIdle()

        onNodeWithContentDescription("Terminal build").assertExists()
        registry.handles.value.getValue(terminal).session!!.sendText("\u0003")
    }

    @Test
    fun theMenuOffersToGoBackToTheProcessNameOnlyForATerminalThatHasANameOfItsOwn() = runComposeUiTest {
        store.addProject("/tmp")
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
        onAllNodesWithContentDescription("Terminal zsh")[0].assertExists()
    }

    @Test
    fun theNameOfATerminalIsEditedInItsHeader() = runComposeUiTest {
        store.addProject("/tmp")
        val terminal = store.state.value.selection.terminal!!
        show()

        onNodeWithContentDescription("Pane menu").performClick()
        onNodeWithText("Rename terminal").performClick()
        waitForIdle()
        onNodeWithText("zsh").performTextInput("build")
        onNodeWithText("build").performKeyInput { pressKey(Key.Enter) }
        waitForIdle()

        assertEquals("build", store.state.value.locate(terminal)!!.terminal.name)
        onNodeWithContentDescription("Terminal build").assertExists()
    }

    @Test
    fun aWorkspaceWithOnePaneHasNothingToMaximizeAPaneOver() = runComposeUiTest {
        store.addProject("/tmp")
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
        store.addProject("/tmp")
        val first = store.state.value.selection.terminal!!
        store.split(first, SplitDirection.ROW)
        show()
        mainClock.autoAdvance = false
        val start = onAllNodesWithContentDescription("Maximize pane")[0].fetchSemanticsNode().boundsInRoot.left
        val columns = { registry.handles.value[first]!!.session!!.frames.value.cols }
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
        store.addProject("/tmp")
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
        store.addProject("/tmp")
        store.split(store.state.value.selection.terminal!!, SplitDirection.ROW)
        show()
        waitForIdle()

        val handles = registry.handles.value
        assertEquals(2, handles.size)
        handles.values.forEach { assertNotNull(it.session) }
    }

    @Test
    fun theTerminalThatTheStoreFocusesKeepsTheFocusWhenTheOthersAreShown() = runComposeUiTest {
        store.addProject("/tmp")
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
