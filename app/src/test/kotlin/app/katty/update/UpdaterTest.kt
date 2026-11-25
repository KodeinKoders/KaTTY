package app.katty.update

import app.katty.core.AppVersion
import app.katty.core.KattyDirectories
import app.katty.core.ManagedUpdates
import app.katty.core.Settings
import app.katty.core.UpdateAction
import app.katty.core.UpdateCheck
import app.katty.core.UpdateState
import app.katty.core.UpdateUnavailable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.util.HexFormat
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/** The updater with a manifest and a disk image in files, and a verifier that stages what it is given: what is tested is what it decides. */
class UpdaterTest {
    private val folder = createTempDirectory("katty-updater")
    private val directories = KattyDirectories(folder.resolve("support"))
    private val app = folder.resolve("Applications/KaTTY.app").also { Files.createDirectories(it.resolve("Contents")); it.resolve("Contents/version").writeText("1.0.10") }
    private val bundle = BundleLocation.Installed(app)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var settings = Settings()
    private var managed = ManagedUpdates.NONE
    private val finished = mutableListOf<Boolean>()

    /** Stages a folder that says the version, which is how [versionOf] reads it back. */
    private val verifier = object : UpdateVerifier() {
        var refuse = false

        override fun verifyAndStage(dmg: Path, expected: AppVersion, staged: Path) {
            if (refuse) throw UpdateRejected("the disk image is not signed by Kodein")
            Files.createDirectories(staged.resolve("Contents"))
            staged.resolve("Contents/version").writeText(expected.toString())
        }

        override fun verifyApp(app: Path, expected: AppVersion?) {}

        override fun versionOf(app: Path): AppVersion? = runCatching { AppVersion.parse(app.resolve("Contents/version").readText()) }.getOrNull()
    }

    @AfterTest
    fun clean() {
        scope.cancel()
        deleteRecursively(folder)
    }

    /** A release of [version]: its disk image and its manifest, in files. */
    private fun release(version: String): java.net.URI {
        val dmg = folder.resolve("releases/KaTTY-$version.dmg").also { Files.createDirectories(it.parent) }
        val bytes = "disk image of $version".toByteArray()
        dmg.writeBytes(bytes)
        val sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
        val manifest = folder.resolve("releases/latest.json")
        manifest.writeText("""{"version":"$version","dmg":"${dmg.toUri()}","size":${bytes.size},"sha256":"$sha"}""")
        return manifest.toUri()
    }

    private fun updater(feed: java.net.URI, location: BundleLocation = bundle, current: String = "1.0.10") = Updater(
        directories, AppVersion.parse(current), { settings }, scope, UpdateHttp("1.0.10"),
        managed = { managed }, bundle = location, verifier = verifier, feedOverride = feed,
        firstCheckDelay = 0.milliseconds, finish = { _, _, _, relaunch, _ -> finished += relaunch },
    )

    private fun Updater.awaitState(predicate: (UpdateState) -> Boolean): UpdateState = runBlocking {
        withTimeout(10_000) { state.first(predicate) }
    }

    @Test
    fun aNewerVersionIsDownloadedAndInstalledOnQuit() {
        val updater = updater(release("1.0.11"))
        updater.checkNow()
        val ready = assertIs<UpdateState.Ready>(updater.awaitState { it is UpdateState.Ready || it is UpdateState.Failed })
        assertEquals(AppVersion.parse("1.0.11"), ready.version)
        assertFalse(Files.list(directories.updates).use { files -> files.anyMatch { it.fileName.toString().endsWith(".dmg") } }, "the disk image is kept")
        assertTrue(Files.exists(Path.of(directories.updates.resolve("last-check").toString())))

        assertTrue(updater.installsAtExit(relaunch = false))
        assertTrue(updater.installAtExit(relaunch = false))
        assertEquals("1.0.11", app.resolve("Contents/version").readText())
        assertEquals("1.0.10", bundle.staged.resolve("Contents/version").readText())
        assertEquals(listOf(false), finished)
    }

    @Test
    fun aVersionThatIsNotNewerIsNothing() {
        val updater = updater(release("1.0.10"))
        updater.checkNow()
        assertIs<UpdateState.UpToDate>(updater.awaitState { it is UpdateState.UpToDate || it is UpdateState.Failed })
        assertFalse(updater.installAtExit(relaunch = true))
        assertFalse(Files.exists(bundle.staged))
    }

    @Test
    fun toNotifyIsToSayAndToDownloadOnlyWhenAsked() {
        settings = Settings(updateAction = UpdateAction.NOTIFY)
        val updater = updater(release("1.0.12"))
        updater.checkNow()
        val available = assertIs<UpdateState.Available>(updater.awaitState { it is UpdateState.Available || it is UpdateState.Failed })
        assertEquals(AppVersion.parse("1.0.12"), available.manifest.version)
        assertFalse(Files.exists(bundle.staged))

        updater.downloadAvailable()
        assertIs<UpdateState.Ready>(updater.awaitState { it is UpdateState.Ready || it is UpdateState.Failed })
        // Notify only: quitting does not install, a restart that the user asks for does.
        assertFalse(updater.installsAtExit(relaunch = false))
        assertTrue(updater.installsAtExit(relaunch = true))
    }

    @Test
    fun toDownloadIsNotToInstallOnQuit() {
        settings = Settings(updateAction = UpdateAction.DOWNLOAD)
        val updater = updater(release("1.0.12"))
        updater.checkNow()
        assertIs<UpdateState.Ready>(updater.awaitState { it is UpdateState.Ready || it is UpdateState.Failed })
        assertFalse(updater.installsAtExit(relaunch = false))
        assertTrue(updater.installsAtExit(relaunch = true))
    }

    @Test
    fun aVersionThatTheVerifierRefusesIsAFailureAndNothingIsStaged() {
        verifier.refuse = true
        val updater = updater(release("1.0.11"))
        updater.checkNow()
        val failed = assertIs<UpdateState.Failed>(updater.awaitState { it is UpdateState.Failed || it is UpdateState.Ready })
        assertEquals("the disk image is not signed by Kodein", failed.reason)
        assertFalse(Files.exists(bundle.staged))
    }

    @Test
    fun aManifestThatCannotBeReadIsAFailureWhenTheUserAsked() {
        val updater = updater(folder.resolve("nothing.json").toUri())
        updater.checkNow()
        assertIs<UpdateState.Failed>(updater.awaitState { it is UpdateState.Failed })
    }

    @Test
    fun theOrganizationCanTurnTheUpdatesOff() {
        managed = ManagedUpdates(disabled = true)
        val updater = updater(release("1.0.11"))
        updater.checkNow()
        assertEquals(UpdateState.Unavailable(UpdateUnavailable.DISABLED_BY_ADMIN), updater.awaitState { it is UpdateState.Unavailable })
    }

    @Test
    fun theOrganizationCanImposeTheAction() {
        managed = ManagedUpdates(action = UpdateAction.NOTIFY)
        val updater = updater(release("1.0.11"))
        updater.checkNow()
        assertIs<UpdateState.Available>(updater.awaitState { it is UpdateState.Available || it is UpdateState.Ready })
    }

    @Test
    fun anApplicationThatCannotBeReplacedSaysWhy() {
        val updater = updater(release("1.0.11"), BundleLocation.Unavailable(UpdateUnavailable.TRANSLOCATED))
        updater.checkNow()
        assertEquals(UpdateState.Unavailable(UpdateUnavailable.TRANSLOCATED, RELEASES_PAGE), updater.awaitState { it is UpdateState.Unavailable })
    }

    @Test
    fun aVersionThatWasReadyAtTheLastQuitIsReadyAgain() {
        Files.createDirectories(bundle.staged.resolve("Contents"))
        bundle.staged.resolve("Contents/version").writeText("1.0.11")
        settings = Settings(updateCheck = UpdateCheck.MANUALLY)
        val updater = updater(release("1.0.11"))
        updater.start()
        assertEquals(AppVersion.parse("1.0.11"), assertIs<UpdateState.Ready>(updater.awaitState { it is UpdateState.Ready }).version)
    }

    @Test
    fun aStagedVersionThatIsNotNewerIsDeletedAtStart() {
        Files.createDirectories(bundle.staged.resolve("Contents"))
        bundle.staged.resolve("Contents/version").writeText("1.0.10")
        settings = Settings(updateCheck = UpdateCheck.MANUALLY)
        val updater = updater(release("1.0.10"))
        updater.start()
        runBlocking { withTimeout(10_000) { while (Files.exists(bundle.staged)) kotlinx.coroutines.delay(20) } }
        assertEquals(UpdateState.Idle, updater.state.value)
    }

    @Test
    fun aCheckThatIsDueIsMadeAtStart() {
        Files.createDirectories(directories.updates)
        directories.updates.resolve("last-check").writeText(Instant.now().minusSeconds(2 * 86_400).toString())
        val updater = updater(release("1.0.11"))
        updater.start()
        assertIs<UpdateState.Ready>(updater.awaitState { it is UpdateState.Ready || it is UpdateState.Failed })
    }

    @Test
    fun aCheckThatIsNotDueIsNotMade() {
        Files.createDirectories(directories.updates)
        directories.updates.resolve("last-check").writeText(Instant.now().minusSeconds(3600).toString())
        val updater = updater(release("1.0.11"))
        updater.start()
        Thread.sleep(500)
        assertEquals(UpdateState.Idle, updater.state.value)
    }
}
