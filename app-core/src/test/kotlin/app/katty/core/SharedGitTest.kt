package app.katty.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SharedGitTest {
    private var counter = 0
    private val store = KattyStore(newId = { "id${counter++}" })
    private val workspace get() = store.state.value.selectedWorkspace!!

    private val main = GitInfo("main", GitKind.BRANCH)

    // A workspace with three terminals.
    private fun built(): List<TerminalId> {
        store.addProject("/dev/api")
        val first = store.state.value.selection.terminal!!
        val second = store.split(first, SplitDirection.ROW)!!
        val third = store.split(second, SplitDirection.COLUMN)!!
        return listOf(first, second, third)
    }

    @Test
    fun theBranchThatEveryTerminalIsInIsTheWorkspaces() {
        val ids = built()

        assertEquals(main, workspace.sharedGit(ids.associateWith { main }))
    }

    @Test
    fun aWorkspaceWithOneTerminalHasItsBranch() {
        store.addProject("/dev/api")
        val only = store.state.value.selection.terminal!!

        assertEquals(main, workspace.sharedGit(mapOf(only to main)))
    }

    @Test
    fun terminalsInDifferentBranchesHaveNoSharedBranch() {
        val ids = built()

        assertNull(workspace.sharedGit(mapOf(ids[0] to main, ids[1] to main, ids[2] to GitInfo("feature", GitKind.BRANCH))))
    }

    @Test
    fun aBranchAndAWorktreeOfTheSameNameAreNotTheSame() {
        val ids = built()

        assertNull(workspace.sharedGit(mapOf(ids[0] to main, ids[1] to main, ids[2] to GitInfo("main", GitKind.WORKTREE))))
    }

    @Test
    fun aTerminalThatIsNotInARepositoryOrNotKnownYetMeansThereIsNoSharedBranch() {
        val ids = built()

        assertNull(workspace.sharedGit(mapOf(ids[0] to main, ids[1] to main, ids[2] to null)), "In no repository")
        assertNull(workspace.sharedGit(mapOf(ids[0] to main, ids[1] to main)), "Not read yet")
        assertNull(workspace.sharedGit(emptyMap()))
    }

    @Test
    fun theCommitOfADetachedHeadIsSharedLikeABranch() {
        val ids = built()
        val detached = GitInfo("a1b2c3d", GitKind.DETACHED)

        assertEquals(detached, workspace.sharedGit(ids.associateWith { detached }))
    }
}
