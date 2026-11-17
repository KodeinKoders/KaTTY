package app.katty.core

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GitReaderTest {
    private val root: Path = Files.createTempDirectory("katty-git").toRealPath()

    @AfterTest
    fun cleanUp() {
        root.toFile().deleteRecursively()
    }

    // The real git, so that what is read is what git writes.
    private fun git(directory: Path, vararg arguments: String) {
        val process = ProcessBuilder(listOf("git", "-c", "user.name=t", "-c", "user.email=t@t", "-c", "commit.gpgsign=false", "-c", "init.defaultBranch=main") + arguments)
            .directory(directory.toFile()).redirectErrorStream(true).start()
        val output = process.inputStream.readBytes().decodeToString()
        check(process.waitFor() == 0) { "git ${arguments.joinToString(" ")} failed: $output" }
    }

    // A git command that stops on a conflict, which is what is tested.
    private fun gitFailing(directory: Path, vararg arguments: String) {
        val process = ProcessBuilder(listOf("git", "-c", "user.name=t", "-c", "user.email=t@t", "-c", "commit.gpgsign=false", "-c", "core.editor=true") + arguments)
            .directory(directory.toFile()).redirectErrorStream(true).start()
        val output = process.inputStream.readBytes().decodeToString()
        check(process.waitFor() != 0) { "git ${arguments.joinToString(" ")} did not stop: $output" }
    }

    private fun repository(): Path {
        val repo = root.resolve("repo").createDirectories()
        git(repo, "init")
        repo.resolve("a.txt").writeText("a")
        git(repo, "add", ".")
        git(repo, "commit", "-m", "first")
        return repo
    }

    @Test
    fun readsTheBranchOfARepository() {
        val repo = repository()

        assertEquals(GitInfo("main", GitKind.BRANCH), GitReader.read(repo))
    }

    @Test
    fun aFolderInsideARepositoryIsInIt() {
        val repo = repository()
        val inner = repo.resolve("src/deep").createDirectories()

        assertEquals(GitInfo("main", GitKind.BRANCH), GitReader.read(inner))
    }

    @Test
    fun readsAnotherBranchWithASlashInItsName() {
        val repo = repository()
        git(repo, "checkout", "-b", "feature/rate-limit")

        assertEquals(GitInfo("feature/rate-limit", GitKind.BRANCH), GitReader.read(repo))
    }

    @Test
    fun aLinkedWorkingTreeIsAWorktree() {
        val repo = repository()
        val worktree = root.resolve("rate-limit-key")
        git(repo, "worktree", "add", "-b", "rate-limit-key", worktree.toString())

        assertEquals(GitInfo("rate-limit-key", GitKind.WORKTREE), GitReader.read(worktree))
        // The main working tree is still a plain branch.
        assertEquals(GitInfo("main", GitKind.BRANCH), GitReader.read(repo))
    }

    @Test
    fun aFolderIsInTheTreeOfItsRepositoryAndALinkedTreeHasItsGitDirectoryOutsideIt() {
        val repo = repository()
        val worktree = root.resolve("rate-limit-key")
        git(repo, "worktree", "add", "-b", "rate-limit-key", worktree.toString())

        val main = GitReader.locate(repo.resolve("src").createDirectories())!!
        assertEquals(GitTree(repo, repo.resolve(".git"), repo.resolve(".git")), main)
        assertFalse(main.gitDirOutside)
        // A linked tree has a HEAD and an index of its own, in the repository's git directory, which has the refs that they share.
        val linked = GitReader.locate(worktree)!!
        assertEquals(GitTree(worktree.toRealPath(), repo.resolve(".git/worktrees/rate-limit-key").toRealPath(), repo.resolve(".git")), linked)
        assertTrue(linked.gitDirOutside)
        assertNull(GitReader.locate(root.resolve("plain").createDirectories()))
    }

    // --- What git is in the middle of ---

    @Test
    fun aMergeThatStoppedOnAConflictIsMerging() {
        val repo = repository()
        git(repo, "checkout", "-b", "other")
        repo.resolve("a.txt").writeText("other")
        git(repo, "commit", "-am", "other")
        git(repo, "checkout", "main")
        repo.resolve("a.txt").writeText("main")
        git(repo, "commit", "-am", "main")
        gitFailing(repo, "merge", "other")

        assertEquals(GitInfo("main", GitKind.BRANCH, GitOperation.MERGE), GitReader.read(repo))
        git(repo, "merge", "--abort")
        assertEquals(GitInfo("main", GitKind.BRANCH), GitReader.read(repo))
    }

    @Test
    fun aRebaseThatStoppedIsOnTheBranchThatItRebases() {
        val repo = repository()
        git(repo, "checkout", "-b", "feature/x")
        repo.resolve("a.txt").writeText("feature")
        git(repo, "commit", "-am", "feature")
        git(repo, "checkout", "main")
        repo.resolve("a.txt").writeText("main")
        git(repo, "commit", "-am", "main")
        git(repo, "checkout", "feature/x")
        gitFailing(repo, "rebase", "main")

        // HEAD is detached on main's commit: the branch being rebased is what is said.
        assertEquals(GitInfo("feature/x", GitKind.BRANCH, GitOperation.REBASE), GitReader.read(repo))
    }

    @Test
    fun aCherryPickARevertAndABisectAreSaid() {
        val repo = repository()
        git(repo, "checkout", "-b", "other")
        repo.resolve("a.txt").writeText("other")
        git(repo, "commit", "-am", "other")
        git(repo, "checkout", "main")
        repo.resolve("a.txt").writeText("main")
        git(repo, "commit", "-am", "main")

        gitFailing(repo, "cherry-pick", "other")
        assertEquals(GitOperation.CHERRY_PICK, GitReader.read(repo)?.operation)
        git(repo, "cherry-pick", "--abort")

        repo.resolve("a.txt").writeText("again")
        git(repo, "commit", "-am", "again")
        gitFailing(repo, "revert", "HEAD~1")
        assertEquals(GitOperation.REVERT, GitReader.read(repo)?.operation)
        git(repo, "revert", "--abort")

        git(repo, "bisect", "start")
        assertEquals(GitOperation.BISECT, GitReader.read(repo)?.operation)
        git(repo, "bisect", "reset")
        assertNull(GitReader.read(repo)?.operation)
    }

    @Test
    fun aDetachedHeadIsTheStartOfTheCommit() {
        val repo = repository()
        git(repo, "checkout", "--detach")

        val info = GitReader.read(repo)!!

        assertEquals(GitKind.DETACHED, info.kind)
        assertEquals(7, info.name.length)
        assertEquals(info.name, ProcessBuilder("git", "rev-parse", "--short=7", "HEAD").directory(repo.toFile()).start().inputStream.readBytes().decodeToString().trim())
    }

    @Test
    fun aSubmoduleIsAPlainCheckout() {
        val repo = repository()
        val module = root.resolve("module-source").createDirectories()
        git(module, "init")
        module.resolve("m.txt").writeText("m")
        git(module, "add", ".")
        git(module, "commit", "-m", "m")
        git(repo, "-c", "protocol.file.allow=always", "submodule", "add", module.toString(), "vendor")

        assertEquals(GitInfo("main", GitKind.BRANCH), GitReader.read(repo.resolve("vendor")))
    }

    @Test
    fun aFolderOutsideAnyRepositoryHasNothing() {
        val plain = root.resolve("plain").createDirectories()

        assertNull(GitReader.read(plain))
    }

    @Test
    fun aFolderThatIsGoneHasNothing() {
        assertNull(GitReader.read(root.resolve("gone")))
    }

    @Test
    fun aRepositoryWithoutACommitYetIsOnItsBranch() {
        val repo = root.resolve("fresh").createDirectories()
        git(repo, "init")

        assertEquals(GitInfo("main", GitKind.BRANCH), GitReader.read(repo))
    }

    // --- The linked working tree of a project ---

    @Test
    fun aDirectoryInALinkedWorkingTreeOfTheProjectsRepositoryIsInThatTree() {
        val repo = repository()
        git(repo, "worktree", "add", "-b", "feature", root.resolve("feature").toString())
        val tree = root.resolve("feature")
        val deep = tree.resolve("src/deep").createDirectories()

        assertEquals(tree, GitReader.linkedWorktree(repo, tree))
        assertEquals(tree, GitReader.linkedWorktree(repo, deep), "A folder of it is in it")
    }

    @Test
    fun theMainWorkingTreeIsNoLinkedOne() {
        val repo = repository()
        git(repo, "worktree", "add", "-b", "feature", root.resolve("feature").toString())

        assertNull(GitReader.linkedWorktree(repo, repo))
        assertNull(GitReader.linkedWorktree(repo, repo.resolve("src").createDirectories()))
    }

    @Test
    fun aProjectThatIsAFolderOfTheRepositoryHasItsLinkedTreesToo() {
        val repo = repository()
        val project = repo.resolve("services/api").createDirectories()
        git(repo, "worktree", "add", "-b", "feature", root.resolve("feature").toString())

        assertEquals(root.resolve("feature"), GitReader.linkedWorktree(project, root.resolve("feature")))
    }

    @Test
    fun aLinkedTreeOfAnotherRepositoryIsNotTheProjects() {
        val repo = repository()
        val other = root.resolve("other").createDirectories()
        git(other, "init")
        other.resolve("b.txt").writeText("b")
        git(other, "add", ".")
        git(other, "commit", "-m", "first")
        git(other, "worktree", "add", "-b", "elsewhere", root.resolve("elsewhere").toString())

        assertNull(GitReader.linkedWorktree(repo, root.resolve("elsewhere")))
        assertNull(GitReader.linkedWorktree(repo, root), "Not in a repository at all")
    }

    @Test
    fun aProjectThatIsItselfALinkedTreeIsTheProjectNotAnotherTree() {
        val repo = repository()
        git(repo, "worktree", "add", "-b", "feature", root.resolve("feature").toString())
        git(repo, "worktree", "add", "-b", "other", root.resolve("other-tree").toString())
        val project = root.resolve("feature")

        assertNull(GitReader.linkedWorktree(project, project.resolve("src").createDirectories()), "The project's own tree")
        assertEquals(root.resolve("other-tree"), GitReader.linkedWorktree(project, root.resolve("other-tree")))
    }

    // --- Where a terminal that is restarted starts ---

    @Test
    fun aTerminalInTheProjectRestartsAtTheProjectsRoot() {
        val repo = repository()
        val deep = repo.resolve("src/deep").createDirectories()

        assertEquals(repo.toString(), restartDirectory(repo.toString(), repo.toString()))
        assertEquals(repo.toString(), restartDirectory(repo.toString(), deep.toString()))
    }

    @Test
    fun aTerminalInAWorktreeOfTheProjectRestartsAtTheWorktreesRoot() {
        val repo = repository()
        val tree = root.resolve("feature")
        git(repo, "worktree", "add", "-b", "feature", tree.toString())
        val deep = tree.resolve("src/deep").createDirectories()

        assertEquals(tree.toString(), restartDirectory(repo.toString(), deep.toString()))
        assertEquals(tree.toString(), restartDirectory(repo.toString(), tree.toString()))
    }

    @Test
    fun aWorktreeInsideTheProjectIsTheProject() {
        val repo = repository()
        val tree = repo.resolve(".worktrees/feature")
        git(repo, "worktree", "add", "-b", "feature", tree.toString())

        assertEquals(repo.toString(), restartDirectory(repo.toString(), tree.resolve("src").createDirectories().toString()))
    }

    @Test
    fun aTerminalAnywhereElseRestartsAtTheProjectsRoot() {
        val repo = repository()
        val project = repo.resolve("services/api").createDirectories()
        val elsewhere = root.resolve("elsewhere").createDirectories()

        assertEquals(repo.toString(), restartDirectory(repo.toString(), elsewhere.toString()))
        assertEquals(project.toString(), restartDirectory(project.toString(), repo.toString()), "The rest of the repository is not the project")
        assertEquals(repo.toString(), restartDirectory(repo.toString(), root.resolve("gone").toString()), "A folder that is not there any more")
    }
}
