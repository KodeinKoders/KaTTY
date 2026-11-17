package app.katty.terminals

import app.katty.core.GitChanges
import app.katty.core.GitKind
import app.katty.core.GitReader
import app.katty.core.GitStatusReading
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GitTreesTest {
    private val root: Path = Files.createTempDirectory("katty-git-trees").toRealPath()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val watcher = GitTreeWatcher()

    // How many times `git status` ran, and what it says: the number of the reading, as modified files.
    private val reads = AtomicInteger()
    private var statusDelayMs = 0L

    private fun trees(slowMs: Long = GitTrees.SLOW_MS, watch: (app.katty.core.GitTree, () -> Unit) -> GitTreeWatcher.Watching? = watcher::watch) = GitTrees(scope, watch, readStatus = {
        Thread.sleep(statusDelayMs)
        GitStatusReading(GitChanges(modified = reads.incrementAndGet()))
    }, slowMs = slowMs)

    @AfterTest
    fun cleanUp() {
        scope.cancel()
        watcher.close()
        root.toFile().deleteRecursively()
    }

    private fun repository(): Path {
        val repo = root.resolve("repo").createDirectories()
        val process = ProcessBuilder("git", "-c", "init.defaultBranch=main", "init", "-q").directory(repo.toFile()).start()
        check(process.waitFor() == 0)
        return repo
    }

    private fun awaitUntil(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(20)
        assertTrue(condition(), "$what (read ${reads.get()} times)")
    }

    @Test
    fun aTreeIsReadWhenTakenAndAgainWhenAFileChanges() {
        val repo = repository()
        val lease = trees().acquire(GitReader.locate(repo)!!)

        awaitUntil("the tree is read at once") { lease.info.value?.status != null }
        val first = lease.info.value!!
        assertEquals("main", first.name)
        assertEquals(GitKind.BRANCH, first.kind)
        assertEquals(repo.toString(), first.tree)

        repo.resolve("new.txt").writeText("new")
        awaitUntil("an edit makes it read again") { reads.get() >= 2 && lease.info.value?.status?.changes?.modified == reads.get() }
    }

    // A runtime without the class of the watch of all the folders (a bundle made without jdk.unsupported): the tree is still read, on a timer.
    @Test
    fun aTreeWhoseWatchFailsIsStillReadAndReadAgainOnATimer() {
        val repo = repository()
        val lease = trees(watch = { _, _ -> throw NoClassDefFoundError("com/sun/nio/file/ExtendedWatchEventModifier") }).acquire(GitReader.locate(repo)!!)

        awaitUntil("the tree is read") { lease.info.value?.name == "main" && lease.info.value?.status != null }
        val first = reads.get()
        Thread.sleep(GitTrees.POLL_MS + 1_500)
        assertTrue(reads.get() > first, "Read again on the timer")
    }

    // A build that writes without a pause does not run git without a pause: at most once a second.
    @Test
    fun aTreeThatChangesWithoutAPauseIsReadAtMostOnceASecond() {
        val repo = repository()
        val lease = trees().acquire(GitReader.locate(repo)!!)
        awaitUntil("the tree is read") { lease.info.value != null }
        Thread.sleep(1_200)
        val before = reads.get()

        val until = System.nanoTime() + 3_000_000_000
        var count = 0
        while (System.nanoTime() < until) {
            repo.resolve("build-${count++ % 5}.txt").writeText("$count")
            Thread.sleep(30)
        }
        Thread.sleep(1_200)

        val during = reads.get() - before
        assertTrue(during in 1..5, "Read $during times for 3 s of writes every 30 ms")
    }

    @Test
    fun theTerminalsOfATreeShareItsReadings() {
        val repo = repository()
        val trees = trees()
        val first = trees.acquire(GitReader.locate(repo)!!)
        val second = trees.acquire(GitReader.locate(repo.resolve(".")!!)!!)
        awaitUntil("the tree is read") { first.info.value != null && second.info.value != null }
        Thread.sleep(500)

        assertEquals(1, reads.get(), "Read once for both")
        assertEquals(first.info.value, second.info.value)

        // Once the last one lets go, it is read no more.
        first.close()
        second.close()
        Thread.sleep(300)
        val after = reads.get()
        repo.resolve("new.txt").writeText("new")
        Thread.sleep(1_500)
        assertEquals(after, reads.get())
    }

    // A home folder under git, a huge monorepo: what the file system tells no longer runs git, a terminal that asks still does.
    @Test
    fun aTreeThatIsTooSlowIsOnlyReadWhenAsked() {
        val repo = repository()
        statusDelayMs = 150
        val lease = trees(slowMs = 100).acquire(GitReader.locate(repo)!!)
        awaitUntil("read once") { reads.get() == 1 }
        repo.resolve("one.txt").writeText("1")
        awaitUntil("read a second time, slowly again") { reads.get() == 2 }
        Thread.sleep(500)

        repo.resolve("two.txt").writeText("2")
        Thread.sleep(2_000)
        assertEquals(2, reads.get(), "An edit no longer makes a slow tree read")

        lease.refresh()
        awaitUntil("a command that ended makes it read") { reads.get() == 3 }
        assertEquals(3, lease.info.value?.status?.changes?.modified)
    }
}
