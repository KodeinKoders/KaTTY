@file:OptIn(ExperimentalTestApi::class)

package app.katty.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import app.katty.core.AgentState
import app.katty.core.AgentStates
import app.katty.core.KattyStore
import app.katty.core.TerminalId
import app.katty.core.terminals
import app.katty.ui.KattyTheme
import app.katty.ui.render
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LayoutsTest {
    private var counter = 0
    private val store = KattyStore(newId = { "id${counter++}" })
    private val state get() = store.state.value

    private fun populate() {
        store.addProject("/Users/me/dev/web-client")
        val gateway = store.addProject("/Users/me/dev/api-gateway")
        store.renameProject(gateway, "api-gateway")
        store.renameWorkspace(state.selection.workspace!!, "refactor-auth")
        store.addWorkspace(gateway, "rate-limiter")
        store.selectWorkspace(state.projects.last().workspaces.first().id)
    }

    private val working = object : AgentStates {
        override fun stateOf(id: TerminalId) = AgentState.WORKING
    }

    @androidx.compose.runtime.Composable
    private fun Tabs(states: AgentStates = app.katty.core.NoAgentStates, onAdd: () -> Unit = {}, onNew: () -> Unit = {}) {
        val current by store.state.collectAsState()
        Column {
            ProjectTabs(current, store, onAddProject = onAdd, onRemoveProject = {}, onReveal = {}, renamingProject = null, onRenamingProject = {}, onNewWorkspace = {}, states = states)
            WorkspaceTabs(
                current.selectedProject, current.selection.workspace, current.selection.terminal, store,
                renamingWorkspace = null, onRenamingWorkspace = {}, onCloseWorkspace = {}, onNewWorkspace = onNew, states = states,
            )
        }
    }

    @Test
    fun theProjectsAreTabsAndTheSelectedOneShowsItsWorkspacesAsTabs() = runComposeUiTest {
        populate()
        setContent { KattyTheme(false) { Tabs() } }

        onNodeWithContentDescription("api-gateway").assertIsSelected()
        onNodeWithContentDescription("web-client").assertExists()
        onNodeWithContentDescription("refactor-auth").assertIsSelected()
        onNodeWithContentDescription("rate-limiter").assertExists()
    }

    @Test
    fun clickingATabSelectsIt() = runComposeUiTest {
        populate()
        setContent { KattyTheme(false) { Tabs() } }

        onNodeWithContentDescription("web-client").performClick()
        assertEquals(state.projects.first().id, state.selection.project)
        waitForIdle()
        onNodeWithContentDescription("workspace").assertIsSelected()
    }

    @Test
    fun theWorkspaceTabsSelectAndAddAndSplit() = runComposeUiTest {
        populate()
        var added = 0
        setContent { KattyTheme(false) { Tabs(onNew = { added++ }) } }

        onNodeWithContentDescription("rate-limiter").performClick()
        assertEquals("rate-limiter", state.selectedWorkspace!!.name)
        onNodeWithContentDescription("New workspace").performClick()
        assertEquals(1, added)
        onNodeWithContentDescription("Split right").performClick()
        waitForIdle()
        assertEquals(2, state.selectedWorkspace!!.layout.terminals().size)
        onNodeWithContentDescription("Split down").performClick()
        waitForIdle()
        assertEquals(3, state.selectedWorkspace!!.layout.terminals().size)
    }

    @Test
    fun theAddButtonOfTheProjectBarAsksForAFolder() = runComposeUiTest {
        populate()
        var asked = 0
        setContent { KattyTheme(false) { Tabs(onAdd = { asked++ }) } }

        onNodeWithContentDescription("Add project").performClick()

        assertEquals(1, asked)
    }

    @Test
    fun theRailHasTheInitialsOfEachProjectAndOpensItsWorkspacesOnAClick() = runComposeUiTest {
        populate()
        setContent {
            KattyTheme(false) {
                val current by store.state.collectAsState()
                Rail(current, store, onAddProject = {})
            }
        }

        onNodeWithText("AG").assertExists()
        onNodeWithText("WC").assertExists()
        onNodeWithContentDescription("web-client").performClick()
        waitForIdle()
        assertEquals(state.projects.first().id, state.selection.project)
        onNodeWithContentDescription("Workspaces of web-client").assertExists()
        onNodeWithText("New workspace").performClick()
        waitForIdle()
        assertEquals(2, state.projects.first().workspaces.size)
    }

    @Test
    fun aWorkspaceOfTheFlyoutIsSelectedAndClosesIt() = runComposeUiTest {
        populate()
        setContent {
            KattyTheme(false) {
                val current by store.state.collectAsState()
                Rail(current, store, onAddProject = {})
            }
        }

        onNodeWithContentDescription("api-gateway").performClick()
        waitForIdle()
        onNodeWithContentDescription("rate-limiter").performClick()
        waitForIdle()

        assertEquals("rate-limiter", state.selectedWorkspace!!.name)
        assertEquals(0, onAllNodesWithContentDescription("Workspaces of api-gateway").fetchSemanticsNodes().size)
    }

    @Test
    fun theRailsAddTileAsksForAFolder() = runComposeUiTest {
        populate()
        var asked = 0
        setContent { KattyTheme(false) { val current by store.state.collectAsState(); Rail(current, store, onAddProject = { asked++ }) } }

        onNodeWithContentDescription("Add project").performClick()

        assertEquals(1, asked)
    }

    @Test
    fun drawsTheVerticalBarsAndTheRailInBothThemes() {
        populate()
        for (dark in listOf(false, true)) {
            val suffix = if (dark) "dark" else "light"
            val tabs = render("vertical-tabs-$suffix", 1620, 200, dark = dark) { Tabs(working) }
            val rail = render("rail-$suffix", 140, 900, dark = dark) { val current by store.state.collectAsState(); Rail(current, store, onAddProject = {}, states = working) }

            // The color of what is working is in the badges and the counts.
            assertTrue(tabs.count(if (dark) 0x6B9BFA else 0x2F6BE0) > 50)
            assertTrue(rail.count(if (dark) 0x6B9BFA else 0x2F6BE0) > 20)
        }
    }

}
