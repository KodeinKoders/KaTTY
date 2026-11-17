package app.katty.terminals

import app.katty.core.GitReader
import app.katty.core.GitTree
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// The tests run on the JetBrains Runtime of the toolchain, whose watch service watches a whole tree.
class GitTreeWatcherTest {
    private val root: Path = Files.createTempDirectory("katty-trees").toRealPath()
    private val watcher = GitTreeWatcher()

    @AfterTest
    fun cleanUp() {
        watcher.close()
        root.toFile().deleteRecursively()
    }

    private fun git(directory: Path, vararg arguments: String) {
        val process = ProcessBuilder(listOf("git", "-c", "user.name=t", "-c", "user.email=t@t", "-c", "commit.gpgsign=false", "-c", "init.defaultBranch=main") + arguments)
            .directory(directory.toFile()).redirectErrorStream(true).start()
        val output = process.inputStream.readBytes().decodeToString()
        check(process.waitFor() == 0) { "git ${arguments.joinToString(" ")} failed: $output" }
    }

    private fun repository(): Path {
        val repo = root.resolve("repo").createDirectories()
        git(repo, "init", "-q")
        repo.resolve("src/main/deep").createDirectories().resolve("a.txt").writeText("a")
        git(repo, "add", ".")
        git(repo, "commit", "-qm", "first")
        return repo
    }

    private fun tree(directory: Path): GitTree = GitReader.locate(directory)!!

    private fun awaitUntil(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(20)
        assertTrue(condition(), what)
    }

    // Told only after a moment: the service tells within a few hundred milliseconds, and what is tested is that nothing is told.
    private fun assertNothingTold(told: AtomicInteger, what: String) {
        Thread.sleep(1_000)
        assertEquals(0, told.get(), what)
    }

    // Waits for the service to have nothing more to tell of what was done before, and counts from there.
    private fun settled(told: AtomicInteger) {
        var last: Int
        do {
            last = told.get()
            Thread.sleep(700)
        } while (told.get() != last)
        told.set(0)
    }

    @Test
    fun anEditDeepInTheTreeIsTold() {
        val repo = repository()
        val told = AtomicInteger()
        val watching = assertNotNull(watcher.watch(tree(repo)) { told.incrementAndGet() })
        assertTrue(watching.recursive)

        repo.resolve("src/main/deep/a.txt").writeText("changed")

        awaitUntil("an edit four folders down is told") { told.get() > 0 }
    }

    @Test
    fun whatGitDoesIsToldAndWhatSaysNothingOfTheStatusIsNot() {
        val repo = repository()
        val told = AtomicInteger()
        watcher.watch(tree(repo)) { told.incrementAndGet() }!!
        settled(told)

        // Git's objects, its logs and its locks say nothing of the status.
        repo.resolve(".git/objects/ab").createDirectories().resolve("cdef").writeText("object")
        repo.resolve(".git/logs/HEAD").writeText("log")
        repo.resolve(".git/index.lock").writeText("lock")
        assertNothingTold(told, "objects, logs and locks are not told")
        Files.delete(repo.resolve(".git/index.lock"))

        repo.resolve("src/main/deep/a.txt").writeText("staged")
        settled(told)
        git(repo, "add", ".")
        awaitUntil("git add writes the index, which is told") { told.get() > 0 }
        settled(told)
        git(repo, "checkout", "-q", "-b", "feature")
        awaitUntil("a checkout writes HEAD, which is told") { told.get() > 0 }
    }

    // A linked working tree's index, HEAD and refs are in the repository's git directory, outside the tree: they are watched too.
    @Test
    fun whatGitDoesInALinkedTreeIsTold() {
        val repo = repository()
        val linked = root.resolve("linked")
        git(repo, "worktree", "add", "-q", "-b", "linked", linked.toString())
        val tree = tree(linked)
        assertTrue(tree.gitDirOutside)
        val told = AtomicInteger()
        watcher.watch(tree) { told.incrementAndGet() }!!

        linked.resolve("src/main/deep/a.txt").writeText("changed")
        awaitUntil("an edit in the linked tree is told") { told.get() > 0 }
        settled(told)

        // Not a file of the tree: only the index in .git/worktrees/linked changes.
        git(linked, "add", ".")
        awaitUntil("git add in the linked tree is told") { told.get() > 0 }
        settled(told)

        // The branch of the linked tree moves in the refs that the trees share.
        git(linked, "commit", "-qm", "linked")
        awaitUntil("a commit in the linked tree is told") { told.get() > 0 }
    }

    @Test
    fun everyoneWhoWatchesATreeIsToldUntilTheyStop() {
        val repo = repository()
        val first = AtomicInteger()
        val second = AtomicInteger()
        val firstWatch = watcher.watch(tree(repo)) { first.incrementAndGet() }!!
        watcher.watch(tree(repo.resolve("src"))) { second.incrementAndGet() }!!

        repo.resolve("b.txt").writeText("b")
        awaitUntil("both are told") { first.get() > 0 && second.get() > 0 }

        firstWatch.close()
        settled(first)
        settled(second)
        repo.resolve("c.txt").writeText("c")
        awaitUntil("the other is told again") { second.get() > 0 }
        assertEquals(0, first.get(), "The one that stopped is not told any more")
    }

    // What another JDK does: the tree's folders are not watched, which the reader is told, to read it every few seconds instead.
    @Test
    fun withoutTheFoldersOfATreeTheWatchSaysSo() {
        val flat = GitTreeWatcher(fileTree = false)
        try {
            val repo = repository()
            val watching = assertNotNull(flat.watch(tree(repo)) {})
            assertFalse(watching.recursive)
        } finally {
            flat.close()
        }
    }

    @Test
    fun aTreeThatIsGoneCannotBeWatched() {
        val repo = repository()
        val tree = tree(repo)
        repo.toFile().deleteRecursively()

        assertNull(watcher.watch(tree) {})
    }
}
