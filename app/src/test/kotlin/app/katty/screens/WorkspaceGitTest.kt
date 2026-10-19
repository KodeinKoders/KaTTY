@file:OptIn(ExperimentalTestApi::class)

package app.katty.screens

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.runComposeUiTest
import app.katty.core.GitInfo
import app.katty.core.GitKind
import app.katty.core.KattyStore
import app.katty.core.SplitDirection
import app.katty.core.TerminalId
import app.katty.ui.KattyTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/** A workspace whose terminals are all in the same branch says which, in the sidebar and in the tabs. */
class WorkspaceGitTest {
    private var counter = 0
    private val store = KattyStore(newId = { "id${counter++}" })
    private val main = GitInfo("main", GitKind.BRANCH)

    private fun terminals(): List<TerminalId> {
        store.addProject("/dev/api")
        val first = store.state.value.selection.terminal!!
        return listOf(first, store.split(first, SplitDirection.ROW)!!)
    }

    private fun androidx.compose.ui.test.ComposeUiTest.sidebar(gits: Map<TerminalId, GitInfo?>) =
        setContent {
            KattyTheme(dark = false) {
                val state by store.state.collectAsState()
                Sidebar(
                    state, store, onAddProject = {}, onRemoveProject = {}, onCloseWorkspace = {}, onReveal = {},
                    renamingProject = null, onRenamingProject = {}, renamingWorkspace = null, onRenamingWorkspace = {}, gits = gits,
                )
            }
        }

    private fun androidx.compose.ui.test.ComposeUiTest.tabs(gits: Map<TerminalId, GitInfo?>) =
        setContent {
            KattyTheme(dark = false) {
                val state by store.state.collectAsState()
                WorkspaceTabs(
                    state.selectedProject, state.selection.workspace, state.selection.terminal, store, renamingWorkspace = null,
                    onRenamingWorkspace = {}, onCloseWorkspace = {}, onNewWorkspace = {}, gits = gits,
                )
            }
        }

    @Test
    fun theSidebarSaysTheBranchThatTheTerminalsOfAWorkspaceShare() = runComposeUiTest {
        val ids = terminals()
        sidebar(ids.associateWith { main })

        onNodeWithContentDescription("Branch main").assertExists()
    }

    @Test
    fun theSidebarSaysNothingWhenTheyAreInDifferentBranchesOrOneIsInNoRepository() = runComposeUiTest {
        val ids = terminals()
        sidebar(mapOf(ids[0] to main, ids[1] to GitInfo("feature", GitKind.BRANCH)))
        assertEquals(0, onAllNodesWithContentDescription("Branch main").fetchSemanticsNodes().size)
        assertEquals(0, onAllNodesWithContentDescription("Branch feature").fetchSemanticsNodes().size)
    }

    @Test
    fun theSidebarSaysNothingWhenOneTerminalIsInNoRepository() = runComposeUiTest {
        val ids = terminals()
        sidebar(mapOf(ids[0] to main, ids[1] to null))

        assertEquals(0, onAllNodesWithContentDescription("Branch main").fetchSemanticsNodes().size)
    }

    @Test
    fun aWorktreeIsSaidAsOne() = runComposeUiTest {
        val ids = terminals()
        sidebar(ids.associateWith { GitInfo("release", GitKind.WORKTREE) })

        onNodeWithContentDescription("Worktree release").assertExists()
    }

    @Test
    fun theTabsSayTheSharedBranchToo() = runComposeUiTest {
        val ids = terminals()
        tabs(ids.associateWith { main })

        onNodeWithContentDescription("Branch main").assertExists()
    }

    @Test
    fun theTabsSayNothingWhenTheBranchesDiffer() = runComposeUiTest {
        val ids = terminals()
        tabs(mapOf(ids[0] to main, ids[1] to GitInfo("feature", GitKind.BRANCH)))

        assertEquals(0, onAllNodesWithContentDescription("Branch main").fetchSemanticsNodes().size)
    }
}
