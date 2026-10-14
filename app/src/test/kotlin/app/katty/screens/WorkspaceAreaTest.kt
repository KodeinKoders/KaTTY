@file:OptIn(ExperimentalTestApi::class)

package app.katty.screens

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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

class WorkspaceAreaTest {
    private var counter = 0
    private val store = KattyStore(newId = { "id${counter++}" })
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val registry = SessionRegistry(
        store, scope, loadTheme = { null },
        start = { spec, _ -> TerminalSession.start(80, 10, command = listOf("/bin/sh", "-c", "sleep 60"), directory = spec.directory) },
    )

    @AfterTest
    fun close() {
        registry.close()
        scope.cancel()
    }

    private fun androidx.compose.ui.test.ComposeUiTest.show() {
        registry.sync(store.state.value)
        setContent {
            KattyTheme(dark = false) {
                val state by store.state.collectAsState()
                val handles by registry.handles.collectAsState()
                androidx.compose.runtime.LaunchedEffect(state.projects) { registry.sync(state) }
                val workspace = state.selectedWorkspace
                if (workspace != null) {
                    WorkspaceArea(workspace, state.selection.terminal, handles, state.settings, store, flipped = false, focusTerminals = true)
                }
            }
        }
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
