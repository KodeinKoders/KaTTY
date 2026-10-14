package app.katty.core

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
}
