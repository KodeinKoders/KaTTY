@file:OptIn(ExperimentalTestApi::class)

package app.katty.screens

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import app.katty.core.EnvironmentFilter
import app.katty.core.KattyStore
import app.katty.ui.KattyTheme
import kotlin.test.Test
import kotlin.test.assertEquals

class SidebarEnvironmentFilterTest {
    private fun store(): KattyStore {
        var n = 0
        val store = KattyStore(newId = { "id${n++}" })
        val kodein = store.addProject("/dev/api")
        store.setWorkspacesEnvironment(store.state.value.projects.last().workspaces.map { it.id }.toSet(), "kodein")
        store.setProjectEnvironment(kodein, "kodein")
        val sunday = store.addProject("/dev/web")
        store.setWorkspacesEnvironment(store.state.value.projects.last().workspaces.map { it.id }.toSet(), "sunday")
        store.setProjectEnvironment(sunday, "sunday")
        // Select the first: the project that is hidden is not the selected one. Their workspaces are named as them (2 nodes each when open).
        store.selectProject(kodein)
        return store
    }

    private fun androidx.compose.ui.test.ComposeUiTest.show(store: KattyStore) = setContent {
        KattyTheme(dark = false) {
            val state by store.state.collectAsState()
            Sidebar(
                state, store, onAddProject = {}, onRemoveProject = {}, onCloseWorkspace = {}, onReveal = {}, renamingProject = null,
                onRenamingProject = {}, renamingWorkspace = null, onRenamingWorkspace = {}, modifier = Modifier,
                environments = EnvironmentControls(available = setOf("kodein", "sunday")),
            )
        }
    }

    @Test
    fun uncheckingAnEnvironmentHidesItsProjectsAndPutsADotOnTheButton() = runComposeUiTest {
        val store = store()
        show(store)
        onAllNodesWithContentDescription("web", useUnmergedTree = true).assertCountEquals(2)

        onNodeWithContentDescription("Filter environments").performClick()
        // The row of the project says its environment too: the menu is drawn over it, so its item is the last one.
        onAllNodesWithText("sunday").onLast().performClick()
        waitForIdle()

        assertEquals(EnvironmentFilter(hidden = setOf("sunday")), store.state.value.settings.environmentFilter)
        onAllNodesWithContentDescription("web", useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun hideAllEmptiesTheListWithAButtonThatShowsEverythingAgain() = runComposeUiTest {
        val store = store()
        show(store)
        // Nothing is said while something is listed.
        onAllNodesWithContentDescription("Show all environments", useUnmergedTree = true).assertCountEquals(0)

        onNodeWithContentDescription("Filter environments").performClick()
        onNodeWithText("Hide all").performClick()
        waitForIdle()

        assertEquals(EnvironmentFilter(hidden = setOf("kodein", "sunday"), standardHidden = true), store.state.value.settings.environmentFilter)
        onAllNodesWithContentDescription("web", useUnmergedTree = true).assertCountEquals(0)
        onAllNodesWithText("No environment is shown").assertCountEquals(1)

        onNodeWithContentDescription("Show all environments").performClick()
        waitForIdle()

        assertEquals(EnvironmentFilter.NONE, store.state.value.settings.environmentFilter)
        onAllNodesWithContentDescription("web", useUnmergedTree = true).assertCountEquals(2)
    }

    @Test
    fun uncheckingTheEnvironmentOfTheSelectedWorkspaceSelectsTheNearestOne() = runComposeUiTest {
        val store = store()
        show(store)
        val api = store.state.value.projects.first()
        val web = store.state.value.projects.last()
        assertEquals(api.workspaces.single().id, store.state.value.selection.workspace)

        onNodeWithContentDescription("Filter environments").performClick()
        // The row of the project says its environment too: the menu is drawn over it, so its item is the last one.
        onAllNodesWithText("kodein").onLast().performClick()
        waitForIdle()

        assertEquals(web.workspaces.single().id, store.state.value.selection.workspace)
        assertEquals(web.id, store.state.value.selection.project)
        onAllNodesWithContentDescription("api", useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun withNoEnvironmentThereIsNoButton() = runComposeUiTest {
        var n = 0
        val store = KattyStore(newId = { "id${n++}" })
        store.addProject("/dev/plain")
        setContent {
            KattyTheme(dark = false) {
                val state by store.state.collectAsState()
                Sidebar(
                    state, store, onAddProject = {}, onRemoveProject = {}, onCloseWorkspace = {}, onReveal = {}, renamingProject = null,
                    onRenamingProject = {}, renamingWorkspace = null, onRenamingWorkspace = {}, modifier = Modifier,
                    environments = EnvironmentControls(),
                )
            }
        }

        onAllNodesWithContentDescription("Filter environments", useUnmergedTree = true).assertCountEquals(0)
    }
}
