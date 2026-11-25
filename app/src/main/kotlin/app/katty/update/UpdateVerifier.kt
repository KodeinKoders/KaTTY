package app.katty.update

import app.katty.core.AppVersion
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

private val logger = LoggerFactory.getLogger("app.katty.update.UpdateVerifier")

/** The team of Kodein's Developer ID: only what it signed is installed. */
internal const val KODEIN_TEAM_ID = "DN74B8MP8V"

/** The bundle identifier of KaTTY. */
internal const val KATTY_BUNDLE_ID = "net.kodein.app.katty"

/** A version that is refused, with why, in words that the user can be shown. */
internal class UpdateRejected(message: String) : IOException(message)

/**
 * What decides that a disk image is KaTTY and may replace it: the disk image and the application in it are signed by Kodein's Developer ID
 * ([requirement]: Apple's anchor, the team in the certificate, the identifier), the application is notarized (`spctl`, which reads the ticket
 * that is stapled to it), and it is the version that the manifest says. The manifest, its address and the network are not trusted for that.
 */
internal open class UpdateVerifier(
    private val teamId: String = KODEIN_TEAM_ID,
    private val bundleId: String = KATTY_BUNDLE_ID,
    /** Whether the application must be notarized: only a test of the refusals turns it off, since a test cannot make a notarized one. */
    private val requireNotarization: Boolean = true,
) {
    private val requirement get() = """anchor apple generic and certificate leaf[subject.OU] = "$teamId""""

    /**
     * Checks [dmg] and copies the application in it to [staged] (replaced), checked again there. The disk image is mounted where nothing
     * shows it, and always unmounted. [expected] is the version that the manifest said.
     */
    open fun verifyAndStage(dmg: Path, expected: AppVersion, staged: Path) {
        check(dmg, listOf("codesign", "--verify", "-R=$requirement", dmg.toString()), "the disk image is not signed by Kodein")
        val mount = Files.createTempDirectory("katty-update-")
        try {
            run(listOf("hdiutil", "attach", "-nobrowse", "-readonly", "-noautoopen", "-mountpoint", mount.toString(), dmg.toString()))
                .takeIf { it.first == 0 } ?: throw UpdateRejected("the disk image cannot be opened")
            try {
                val app = mount.resolve("KaTTY.app")
                if (!Files.isDirectory(app)) throw UpdateRejected("the disk image has no KaTTY.app")
                verifyApp(app, expected)
                deleteRecursively(staged)
                run(listOf("ditto", app.toString(), staged.toString()), timeoutMs = 10 * 60_000L)
                    .takeIf { it.first == 0 } ?: throw UpdateRejected("the new version cannot be copied next to the application")
            } finally {
                run(listOf("hdiutil", "detach", "-force", mount.toString()))
            }
        } finally {
            Files.deleteIfExists(mount)
        }
        try {
            check(staged, listOf("codesign", "--verify", "--deep", "--strict", "-R=$requirement and identifier \"$bundleId\"", staged.toString()), "the copy of the new version is not intact")
        } catch (e: Exception) {
            deleteRecursively(staged)
            throw e
        }
    }

    /** Checks an application: its signature, its notarization and its version, which [expected] is when given. */
    open fun verifyApp(app: Path, expected: AppVersion?) {
        check(app, listOf("codesign", "--verify", "--deep", "--strict", "-R=$requirement and identifier \"$bundleId\"", app.toString()), "the new version is not signed by Kodein")
        if (requireNotarization) {
            val (code, output) = run(listOf("spctl", "--assess", "--type", "exec", "-vv", app.toString()))
            if (code != 0 || "source=Notarized Developer ID" !in output) throw UpdateRejected("the new version is not notarized by Apple")
        }
        val version = versionOf(app) ?: throw UpdateRejected("the new version has no version")
        if (expected != null && version != expected) throw UpdateRejected("the disk image has $version, not $expected")
    }

    /** The version of the bundle [app], `null` when it has none that can be read. */
    open fun versionOf(app: Path): AppVersion? {
        val (code, output) = run(listOf("/usr/libexec/PlistBuddy", "-c", "Print :CFBundleShortVersionString", app.resolve("Contents/Info.plist").toString()))
        return if (code == 0) AppVersion.parse(output.trim()) else null
    }

    private fun check(path: Path, command: List<String>, refusal: String) {
        val (code, output) = run(command)
        if (code != 0) {
            logger.info("{}: {}", refusal, output.trim())
            throw UpdateRejected(refusal)
        }
    }

    /** Runs [command] and gives its exit code and its output, the errors with it (codesign and spctl say what they found there). */
    private fun run(command: List<String>, timeoutMs: Long = 5 * 60_000L): Pair<Int, String> {
        val process = ProcessBuilder(command).redirectErrorStream(true).redirectInput(ProcessBuilder.Redirect.from(File("/dev/null"))).start()
        val output = StringBuilder()
        val reader = Thread { output.append(process.inputStream.bufferedReader().readText()) }.also { it.isDaemon = true; it.start() }
        if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            throw UpdateRejected("${command.first()} did not end")
        }
        reader.join(1000)
        return process.exitValue() to output.toString()
    }
}

/** Deletes a folder and what it holds, or a file; nothing when there is nothing. A link is deleted, not followed. */
internal fun deleteRecursively(path: Path) {
    if (!Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return
    Files.walk(path).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
}
