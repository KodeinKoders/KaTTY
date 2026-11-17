package app.katty.core

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class GitStatusTest {
    private val root: Path = Files.createTempDirectory("katty-git-status").toRealPath()

    @AfterTest
    fun cleanUp() {
        root.toFile().deleteRecursively()
    }

    // --- What git prints ---

    @Test
    fun aCleanTreeHasNoChangesAndItsUpstream() {
        val reading = parseGitStatus(
            """
            # branch.oid abc1234567890
            # branch.head main
            # branch.upstream origin/main
            # branch.ab +0 -0
            """.trimIndent(),
        )

        assertEquals(GitStatusReading(GitChanges(), ahead = 0, behind = 0), reading)
        assert(reading.changes.isEmpty)
    }

    @Test
    fun eachKindOfChangeIsCountedAndAFileCanBeStagedAndModified() {
        val reading = parseGitStatus(
            """
            # branch.oid abc1234567890
            # branch.head feature/test
            # branch.upstream origin/feature/test
            # branch.ab +3 -1
            1 M. N... 100644 100644 100644 abc123 def456 src/main.rs
            1 .M N... 100644 100644 100644 abc123 def456 src/lib.rs
            1 MM N... 100644 100644 100644 abc123 def456 src/both.rs
            2 R. N... 100644 100644 100644 abc123 def456 R100 new.rs	old.rs
            ? new-file.txt
            ? another new.txt
            u UU N... 100755 100755 100755 100755 abc123 def456 ghi789 conflict.rs
            """.trimIndent(),
        )

        assertEquals(GitStatusReading(GitChanges(staged = 3, modified = 2, untracked = 2, conflicted = 1), ahead = 3, behind = 1), reading)
    }

    @Test
    fun aBranchWithoutAnUpstreamIsNeitherAheadNorBehind() {
        val reading = parseGitStatus("# branch.oid abc\n# branch.head main\n? x\n")

        assertEquals(GitStatusReading(GitChanges(untracked = 1)), reading)
    }

    // --- The git that is run ---

    @Test
    fun theShimOfMacOSIsOnlyTakenWithTheDeveloperTools() {
        val bin = root.resolve("bin").createDirectories()
        val own = bin.resolve("git").apply { writeText("#!/bin/sh\n") }.also { it.toFile().setExecutable(true) }

        assertEquals(own.toString(), GitStatusReader.findGit("$bin:/usr/bin") { null })
        assertNull(GitStatusReader.findGit("/usr/bin") { null }, "Without the developer tools, /usr/bin/git opens the dialog that installs them")
        val tools = root.resolve("tools/usr/bin").createDirectories()
        tools.resolve("git").writeText("#!/bin/sh\n")
        tools.resolve("git").toFile().setExecutable(true)
        assertEquals("/usr/bin/git", GitStatusReader.findGit("/usr/bin") { root.resolve("tools").toString() })
        assertNull(GitStatusReader.findGit(root.resolve("nothing").toString()) { null })
    }

    // --- Real repositories ---

    private val git: String = GitStatusReader.findGit(System.getenv("PATH").orEmpty()) ?: error("These tests need git")

    private fun git(directory: Path, vararg arguments: String) {
        val process = ProcessBuilder(listOf("git", "-c", "user.name=t", "-c", "user.email=t@t", "-c", "commit.gpgsign=false", "-c", "init.defaultBranch=main") + arguments)
            .directory(directory.toFile()).redirectErrorStream(true).start()
        val output = process.inputStream.readBytes().decodeToString()
        check(process.waitFor() == 0) { "git ${arguments.joinToString(" ")} failed: $output" }
    }

    private fun repository(name: String = "repo"): Path {
        val repo = root.resolve(name).createDirectories()
        git(repo, "init")
        repo.resolve("a.txt").writeText("a")
        repo.resolve("b.txt").writeText("b")
        git(repo, "add", ".")
        git(repo, "commit", "-m", "first")
        return repo
    }

    private fun read(tree: Path) = GitStatusReader.read(tree, git = git)

    @Test
    fun readsWhatIsStagedModifiedAndUntrackedInARealTree() {
        val repo = repository()
        assertEquals(GitStatusReading(GitChanges()), read(repo))

        repo.resolve("a.txt").writeText("changed")
        repo.resolve("b.txt").writeText("staged")
        git(repo, "add", "b.txt")
        repo.resolve("new.txt").writeText("new")

        assertEquals(GitStatusReading(GitChanges(staged = 1, modified = 1, untracked = 1)), read(repo))
    }

    @Test
    fun readingNeverWritesTheIndex() {
        val repo = repository()
        val index = repo.resolve(".git/index")
        // A file written again as it was: its time no longer matches the index, which a plain `git status` would write again.
        Thread.sleep(1100)
        repo.resolve("a.txt").writeText("a")
        val before = Files.getLastModifiedTime(index)
        Thread.sleep(1100)

        assertEquals(GitStatusReading(GitChanges()), read(repo))

        assertEquals(before, Files.getLastModifiedTime(index), "--no-optional-locks: the index that is watched is not written")
    }

    @Test
    fun aBranchIsAheadAndBehindItsUpstream() {
        val upstream = repository("upstream")
        git(root, "clone", "-q", upstream.toString(), "clone")
        val clone = root.resolve("clone")
        upstream.resolve("a.txt").writeText("upstream")
        git(upstream, "commit", "-am", "upstream")
        git(clone, "fetch", "-q")
        clone.resolve("b.txt").writeText("local")
        git(clone, "commit", "-am", "local")
        clone.resolve("c.txt").writeText("local")
        git(clone, "add", ".")
        git(clone, "commit", "-m", "local again")

        assertEquals(GitStatusReading(GitChanges(), ahead = 2, behind = 1), read(clone))
    }

    @Test
    fun aLinkedWorkingTreeHasItsOwnChanges() {
        val repo = repository()
        val worktree = root.resolve("linked")
        git(repo, "worktree", "add", "-q", "-b", "linked", worktree.toString())
        worktree.resolve("a.txt").writeText("in the linked tree")

        assertEquals(GitStatusReading(GitChanges(modified = 1)), read(worktree))
        assertEquals(GitStatusReading(GitChanges()), read(repo))
    }

    @Test
    fun aFolderOutsideARepositoryAndNoGitHaveNoReading() {
        val plain = root.resolve("plain").createDirectories()

        assertNull(read(plain))
        assertNull(GitStatusReader.read(repository(), git = null))
    }
}
