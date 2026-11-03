package app.katty.terminals

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GitHeadWatcherTest {
    private val root: Path = Files.createTempDirectory("katty-heads").toRealPath()
    private val watcher = GitHeadWatcher()

    @AfterTest
    fun cleanUp() {
        watcher.close()
        root.toFile().deleteRecursively()
    }

    private fun gitDirectory(): Path = root.resolve("repo/.git").createDirectories().also { it.resolve("HEAD").writeText("ref: refs/heads/main\n") }

    // What git does: it writes the new HEAD next to it and moves it over the old one.
    private fun checkout(gitDirectory: Path, branch: String) {
        val lock = gitDirectory.resolve("HEAD.lock")
        lock.writeText("ref: refs/heads/$branch\n")
        Files.move(lock, gitDirectory.resolve("HEAD"), StandardCopyOption.ATOMIC_MOVE)
    }

    private fun awaitUntil(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(20)
        assertTrue(condition(), what)
    }

    @Test
    fun aCheckoutIsTold() {
        val gitDirectory = gitDirectory()
        val told = AtomicInteger()
        assertNotNull(watcher.watch(gitDirectory) { told.incrementAndGet() })

        checkout(gitDirectory, "feature")

        awaitUntil("the change of HEAD is told") { told.get() > 0 }
    }

    @Test
    fun everyoneWhoWatchesADirectoryIsToldUntilTheyStop() {
        val gitDirectory = gitDirectory()
        val first = AtomicInteger()
        val second = AtomicInteger()
        val firstWatch = watcher.watch(gitDirectory) { first.incrementAndGet() }!!
        // The same directory by another path is the same watch.
        watcher.watch(root.resolve("repo/../repo/.git")) { second.incrementAndGet() }!!

        checkout(gitDirectory, "feature")
        awaitUntil("both are told") { first.get() > 0 && second.get() > 0 }

        // The one that stops is not told any more, and the other still is.
        firstWatch.close()
        val before = first.get()
        val secondBefore = second.get()
        checkout(gitDirectory, "other")
        awaitUntil("the other is told again") { second.get() > secondBefore }
        assertEquals(before, first.get())
    }

    @Test
    fun aDirectoryThatIsNotThereCannotBeWatched() {
        assertNull(watcher.watch(root.resolve("gone/.git")) {})
    }
}
