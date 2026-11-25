package app.katty.update

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BundleSwapTest {
    private val folder = createTempDirectory("katty-swap")
    private val installed = folder.resolve("KaTTY.app")
    private val staged = folder.resolve(".KaTTY.app.update")

    @AfterTest
    fun clean() = deleteRecursively(folder)

    private fun bundle(at: Path, version: String) {
        Files.createDirectories(at.resolve("Contents"))
        at.resolve("Contents/version").writeText(version)
    }

    private fun versionAt(at: Path) = at.resolve("Contents/version").readText()

    @Test
    fun theTwoBundlesExchangeTheirPlaces() {
        bundle(installed, "old")
        bundle(staged, "new")
        swapBundles(staged, installed)
        assertEquals("new", versionAt(installed))
        assertEquals("old", versionAt(staged))
    }

    @Test
    fun aVolumeThatCannotSwapGetsTwoRenames() {
        bundle(installed, "old")
        bundle(staged, "new")
        swapBundles(staged, installed) { _, _ -> 45 }
        assertEquals("new", versionAt(installed))
        assertEquals("old", versionAt(staged))
    }

    @Test
    fun theOldBundleIsPutBackWhenTheNewOneCannotTakeItsPlace() {
        bundle(installed, "old")
        // Nothing is staged: the second rename fails.
        assertFailsWith<IOException> { swapBundles(staged, installed) { _, _ -> 45 } }
        assertEquals("old", versionAt(installed))
        assertFalse(Files.exists(folder.resolve(".KaTTY.app.old")))
    }

    @Test
    fun anotherFailureChangesNothing() {
        bundle(installed, "old")
        bundle(staged, "new")
        assertFailsWith<IOException> { swapBundles(staged, installed) { _, _ -> 1 } }
        assertEquals("old", versionAt(installed))
        assertEquals("new", versionAt(staged))
    }

    @Test
    fun theFinisherWaitsForTheApplicationThenDeletesTheOldVersionAndOpensTheNewOne() {
        bundle(installed, "new")
        bundle(staged, "old")
        val application = ProcessBuilder("sleep", "1").start()
        val opened = folder.resolve("opened")
        val log = folder.resolve("updates/install.log")
        val finisher = startFinisher(
            application.pid(), staged, installed, relaunch = true, log,
            open = listOf("/bin/sh", "-c", "echo \"$1\" > '$opened'", "open"),
            register = "/usr/bin/true",
        )
        // The application still runs: the old version is still there.
        assertTrue(Files.exists(staged))
        assertTrue(finisher.waitFor(30, TimeUnit.SECONDS), "the finisher did not end: ${log.readText()}")
        assertFalse(application.isAlive)
        assertFalse(Files.exists(staged), log.readText())
        assertEquals(installed.toString(), opened.readText().trim())
        assertEquals("new", versionAt(installed))
    }

    @Test
    fun theFinisherOpensNothingWhenNoRestartWasAsked() {
        bundle(staged, "old")
        val opened = folder.resolve("opened")
        val finisher = startFinisher(
            ProcessBuilder("true").start().apply { waitFor() }.pid(), staged, installed, relaunch = false,
            folder.resolve("install.log"), open = listOf("touch", opened.toString()), register = "/usr/bin/true",
        )
        assertTrue(finisher.waitFor(30, TimeUnit.SECONDS))
        assertFalse(Files.exists(staged))
        assertFalse(Files.exists(opened))
    }
}
