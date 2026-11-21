@file:OptIn(ExperimentalTestApi::class)

package app.katty.screens

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.runComposeUiTest
import app.katty.core.KattyStore
import app.katty.ui.KattyTheme
import kotlin.test.Test

class SidebarEnvironmentsTest {
    @Test
    fun theBadgesSayTheEnvironmentOfAProjectAndOfAWorkspaceThatDiffers() = runComposeUiTest {
        var n = 0
        val store = KattyStore(newId = { "id${n++}" })
        store.updateSettings { it.copy(defaultEnvironment = "kodein") }
        // A project on the default, with its workspace on it too: one badge, the project's.
        store.addProject("/dev/web")
        // A project moved to sunday, whose first workspace kept kodein and whose second has none.
        val sunday = store.addProject("/dev/sunday")
        store.addWorkspace(sunday, "none")
        store.setProjectEnvironment(sunday, "sunday")
        store.setWorkspacesEnvironment(setOf(store.state.value.projects.last().workspaces.last().id), null)
        // A project whose environment has no file.
        val gone = store.addProject("/dev/gone")
        store.setProjectEnvironment(gone, "gone")
        store.setWorkspacesEnvironment(store.state.value.projects.last().workspaces.map { it.id }.toSet(), "gone")
        // A project with none: no badge.
        store.updateSettings { it.copy(defaultEnvironment = null) }
        store.addProject("/dev/plain")

        setContent {
            KattyTheme(dark = false) {
                val state by store.state.collectAsState()
                Sidebar(
                    state, store, onAddProject = {}, onRemoveProject = {}, onCloseWorkspace = {}, onReveal = {}, renamingProject = null,
                    onRenamingProject = {}, renamingWorkspace = null, onRenamingWorkspace = {}, modifier = Modifier,
                    environments = EnvironmentControls(available = setOf("kodein", "sunday")),
                )
            }
        }

        onAllNodesWithContentDescription("Environment kodein", useUnmergedTree = true).assertCountEquals(2)
        onAllNodesWithContentDescription("Environment sunday", useUnmergedTree = true).assertCountEquals(1)
        onAllNodesWithContentDescription("Environment standard", useUnmergedTree = true).assertCountEquals(1)
        onAllNodesWithContentDescription("Environment gone, not found", useUnmergedTree = true).assertCountEquals(1)
    }
}
