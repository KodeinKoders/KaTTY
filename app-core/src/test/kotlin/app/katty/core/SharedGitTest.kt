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

    // One tree is said as it is, however many terminals are in it: its changes, its upstream and what git is in the middle of.
    @Test
    fun aWorkspaceInOneTreeSaysWhatItsTreeSays() {
        val ids = built()
        val status = GitStatusReading(GitChanges(staged = 1, modified = 2), ahead = 3, behind = 1)
        val info = GitInfo("main", GitKind.BRANCH, GitOperation.REBASE, tree = "/dev/api", status = status)

        assertEquals(info, workspace.sharedGit(ids.associateWith { info }))
    }

    // Two clones on one branch: their changes add up, each once, and what may differ between them is not said.
    @Test
    fun aWorkspaceInTwoTreesOfOneBranchAddsTheirChangesUp() {
        val ids = built()
        val first = GitInfo("main", GitKind.BRANCH, GitOperation.MERGE, tree = "/dev/api", status = GitStatusReading(GitChanges(modified = 2), ahead = 1, behind = 0))
        val second = GitInfo("main", GitKind.BRANCH, tree = "/dev/api-copy", status = GitStatusReading(GitChanges(untracked = 1)))

        val shared = workspace.sharedGit(mapOf(ids[0] to first, ids[1] to first, ids[2] to second))!!

        assertEquals(GitInfo("main", GitKind.BRANCH, status = GitStatusReading(GitChanges(modified = 2, untracked = 1))), shared)
    }

    @Test
    fun aWorkspaceWhoseTreesAreNotReadYetHasNoChanges() {
        val ids = built()
        val first = GitInfo("main", GitKind.BRANCH, tree = "/dev/api")
        val second = GitInfo("main", GitKind.BRANCH, tree = "/dev/api-copy")

        assertEquals(GitInfo("main", GitKind.BRANCH), workspace.sharedGit(mapOf(ids[0] to first, ids[1] to first, ids[2] to second)))
    }
}
