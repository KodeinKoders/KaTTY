package app.katty.update

import app.katty.core.AppVersion
import org.junit.Assume.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The checks that decide what may replace the application. A test cannot make an application signed by Kodein and notarized, so the refusals
 * are tested with applications signed ad hoc, and the acceptance only with a real disk image that `KATTY_TEST_NOTARIZED_DMG` names (one that
 * `./gradlew :app:notarize` made), skipped without it.
 */
class UpdateVerifierTest {
    private val folder = createTempDirectory("katty-verify")

    @AfterTest
    fun clean() = deleteRecursively(folder)

    private fun run(vararg command: String) {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor(120, TimeUnit.SECONDS) && process.exitValue() == 0) { "${command.joinToString(" ")}: $output" }
    }

    /** A bundle with a launcher and an `Info.plist`, signed ad hoc with [identifier]. */
    private fun fakeApp(at: Path, version: String, identifier: String = KATTY_BUNDLE_ID): Path {
        Files.createDirectories(at.resolve("Contents/MacOS"))
        at.resolve("Contents/Info.plist").writeText(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
            <plist version="1.0"><dict>
              <key>CFBundleIdentifier</key><string>$identifier</string>
              <key>CFBundleExecutable</key><string>KaTTY</string>
              <key>CFBundleShortVersionString</key><string>$version</string>
            </dict></plist>
            """.trimIndent(),
        )
        at.resolve("Contents/MacOS/KaTTY").apply { writeText("#!/bin/sh\n") }.toFile().setExecutable(true)
        run("codesign", "--force", "--sign", "-", "--identifier", identifier, at.toString())
        return at
    }

    private fun diskImage(app: Path): Path {
        val dmg = folder.resolve("KaTTY.dmg")
        run("hdiutil", "create", "-quiet", "-fs", "HFS+", "-volname", "KaTTY", "-srcfolder", app.toString(), "-ov", dmg.toString())
        run("codesign", "--force", "--sign", "-", dmg.toString())
        return dmg
    }

    @Test
    fun anApplicationThatKodeinDidNotSignIsRefused() {
        val app = fakeApp(folder.resolve("source/KaTTY.app"), "1.0.900")
        val rejected = assertFailsWith<UpdateRejected> { UpdateVerifier().verifyApp(app, AppVersion.parse("1.0.900")) }
        assertEquals("the new version is not signed by Kodein", rejected.message)
    }

    @Test
    fun aDiskImageThatKodeinDidNotSignIsRefusedAndNothingIsStaged() {
        val dmg = diskImage(fakeApp(folder.resolve("source/KaTTY.app"), "1.0.900"))
        val staged = folder.resolve("Applications/.KaTTY.app.update")
        val rejected = assertFailsWith<UpdateRejected> { UpdateVerifier().verifyAndStage(dmg, AppVersion.parse("1.0.900")!!, staged) }
        assertEquals("the disk image is not signed by Kodein", rejected.message)
        assertFalse(Files.exists(staged))
    }

    @Test
    fun theVersionIsReadFromTheBundle() {
        val app = fakeApp(folder.resolve("source/KaTTY.app"), "1.0.900")
        assertEquals(AppVersion.parse("1.0.900"), UpdateVerifier().versionOf(app))
        assertEquals(null, UpdateVerifier().versionOf(folder.resolve("none.app")))
    }

    @Test
    fun aRealNotarizedDiskImageIsAcceptedAndStaged() {
        val path = System.getenv("KATTY_TEST_NOTARIZED_DMG")
        assumeTrue("KATTY_TEST_NOTARIZED_DMG names no notarized disk image", path != null && Files.isRegularFile(Path.of(path)))
        val dmg = Path.of(path!!)
        val version = AppVersion.parse(dmg.fileName.toString().removePrefix("KaTTY-").removeSuffix(".dmg"))!!
        val staged = folder.resolve("Applications/.KaTTY.app.update").also { Files.createDirectories(it.parent) }
        UpdateVerifier().verifyAndStage(dmg, version, staged)
        assertTrue(Files.isDirectory(staged.resolve("Contents/MacOS")))
        assertEquals(version, UpdateVerifier().versionOf(staged))
        // Another version than the manifest's is refused, and nothing stays staged.
        deleteRecursively(staged)
        val other = AppVersion.parse("$version.1")!!
        assertFailsWith<UpdateRejected> { UpdateVerifier().verifyAndStage(dmg, other, staged) }
        assertFalse(Files.exists(staged))
    }
}
