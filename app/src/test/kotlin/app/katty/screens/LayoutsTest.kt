@file:OptIn(ExperimentalTestApi::class)

package app.katty.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
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

    // A project whose first workspace is called "workspace", which is not what a new project's is: that has its project's name.
    private fun plainProject(directory: String): app.katty.core.ProjectId {
        val id = store.addProject(directory)
        store.renameWorkspace(store.state.value.selection.workspace!!, "workspace")
        return id
    }
    private val state get() = store.state.value

    private fun populate() {
        plainProject("/Users/me/dev/web-client")
        val gateway = plainProject("/Users/me/dev/api-gateway")
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
    fun doubleClickingATabRenamesIt() = runComposeUiTest {
        populate()
        var renamed: String? = null
        setContent {
            KattyTheme(false) {
                val current by store.state.collectAsState()
                var projectRenaming by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<app.katty.core.ProjectId?>(null) }
                var workspaceRenaming by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<app.katty.core.WorkspaceId?>(null) }
                Column {
                    ProjectTabs(current, store, onAddProject = {}, onRemoveProject = {}, onReveal = {}, renamingProject = projectRenaming, onRenamingProject = { projectRenaming = it; renamed = "project ${it?.value}" }, onNewWorkspace = {})
                    WorkspaceTabs(
                        current.selectedProject, current.selection.workspace, current.selection.terminal, store,
                        renamingWorkspace = workspaceRenaming, onRenamingWorkspace = { workspaceRenaming = it; renamed = "workspace ${it?.value}" },
                        onCloseWorkspace = {}, onNewWorkspace = {},
                    )
                }
            }
        }

        onNodeWithContentDescription("rate-limiter").performMouseInput { doubleClick() }
        assertEquals("workspace ${state.selectedProject!!.workspaces.last().id.value}", renamed)
        onNodeWithContentDescription("web-client").performMouseInput { doubleClick() }
        assertEquals("project ${state.projects.first().id.value}", renamed)
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
