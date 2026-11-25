package app.katty.update

import app.katty.core.AppVersion
import app.katty.core.KattyDirectories
import app.katty.core.ManagedUpdates
import app.katty.core.Settings
import app.katty.core.UpdateAction
import app.katty.core.UpdateManifest
import app.katty.core.UpdatePolicy
import app.katty.core.UpdateState
import app.katty.core.UpdateUnavailable
import app.katty.core.isCheckDue
import app.katty.core.parseManifest
import app.katty.core.updateDiskImageName
import app.katty.core.updatePolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.ConnectException
import java.net.URI
import java.net.http.HttpTimeoutException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

private val logger = LoggerFactory.getLogger("app.katty.update.Updater")

/** The page that lists the releases: where a user whose application cannot update itself downloads a version. */
internal val RELEASES_PAGE: URI = URI("https://github.com/KodeinKoders/KaTTY/releases/latest")

/** The system property that replaces the address of the manifest, for a test (a `file:` manifest may then point to a `file:` disk image). */
internal const val FEED_PROPERTY = "katty.update.feed"

/**
 * Looks for new versions of the application, downloads them, checks them and puts them next to it, and puts one in its place when it quits.
 *
 * It looks when the user's settings say ([UpdatePolicy], which the organization's managed preferences may impose), a minute after it starts and
 * then once an hour whether a look is due (the time of the last one is kept in [KattyDirectories.updates], so that "weekly" outlives a restart),
 * and when the user asks. A version that is found is said ([UpdateAction.NOTIFY]) or downloaded, and only an application that
 * [UpdateVerifier] accepted is ever [UpdateState.Ready]. Nothing runs on the UI thread.
 */
internal class Updater(
    private val directories: KattyDirectories,
    private val current: AppVersion?,
    private val settings: () -> Settings,
    private val scope: CoroutineScope,
    private val http: UpdateHttp,
    private val managed: () -> ManagedUpdates = ::readManagedUpdates,
    private val bundle: BundleLocation = locateBundle(),
    private val verifier: UpdateVerifier = UpdateVerifier(),
    private val feedOverride: URI? = System.getProperty(FEED_PROPERTY)?.takeIf { it.isNotBlank() }?.let(::URI),
    private val clock: () -> Instant = Instant::now,
    private val firstCheckDelay: Duration = 1.minutes,
    private val tick: Duration = 1.hours,
    private val finish: (pid: Long, old: Path, app: Path, relaunch: Boolean, log: Path) -> Unit =
        { pid, old, app, relaunch, log -> startFinisher(pid, old, app, relaunch, log) },
) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private val busy = Mutex()
    private var loop: Job? = null

    private val lastCheckFile: Path get() = directories.updates.resolve("last-check")

    /** The policy as it is now: the settings and the managed preferences are read again each time. */
    fun policy(): UpdatePolicy {
        val policy = updatePolicy(settings(), managed())
        return if (feedOverride != null) policy.copy(feed = feedOverride) else policy
    }

    /** Takes back a version that was ready at the last quit and not installed, and starts looking. */
    fun start() {
        if (loop != null) return
        loop = scope.launch(Dispatchers.IO) {
            prepare()
            delay(firstCheckDelay)
            while (true) {
                val policy = policy()
                val state = _state.value
                when {
                    state is UpdateState.Available && policy.action != UpdateAction.NOTIFY -> download(state.manifest)
                    isCheckDue(lastCheck(), clock(), policy.check) -> check(userAsked = false)
                }
                delay(tick)
            }
        }
    }

    fun stop() {
        loop?.cancel()
    }

    /** Looks now, whatever the frequency (not when the organization turned the updates off). */
    fun checkNow() {
        scope.launch(Dispatchers.IO) { check(userAsked = true) }
    }

    /** Downloads the version that was only said ([UpdateState.Available]). */
    fun downloadAvailable() {
        val available = _state.value as? UpdateState.Available ?: return
        scope.launch(Dispatchers.IO) { download(available.manifest) }
    }

    /** Whether quitting installs the version that is ready: when the user asked for a restart, or when the policy says to install on quit. */
    fun installsAtExit(relaunch: Boolean): Boolean =
        _state.value is UpdateState.Ready && policy().enabled && (relaunch || policy().action == UpdateAction.INSTALL_ON_QUIT)

    /**
     * Puts the version that is ready in the place of the application, which then exits, and starts what deletes the old one and, when [relaunch],
     * opens the new one. Called once everything is saved, just before the application exits. False when nothing was installed.
     */
    fun installAtExit(relaunch: Boolean): Boolean {
        val ready = _state.value as? UpdateState.Ready ?: return false
        val installed = bundle as? BundleLocation.Installed ?: return false
        return try {
            swapBundles(installed.staged, installed.app)
            logger.info("KaTTY {} is installed in {}", ready.version, installed.app)
            finish(ProcessHandle.current().pid(), installed.staged, installed.app, relaunch, directories.updates.resolve("install.log"))
            true
        } catch (e: Exception) {
            logger.warn("Could not install KaTTY {}", ready.version, e)
            false
        }
    }

    /** A version that waits next to the application from an earlier run is ready if it is newer and still checks; anything else is deleted. */
    private fun prepare() {
        val installed = when (bundle) {
            is BundleLocation.Unavailable -> {
                _state.value = UpdateState.Unavailable(bundle.reason, RELEASES_PAGE)
                return
            }
            is BundleLocation.Installed -> bundle
        }
        runCatching { Files.list(directories.updates).use { files -> files.filter { it.fileName.toString().endsWith(".part") }.toList() }.forEach(Files::deleteIfExists) }
        val staged = installed.staged
        if (!Files.exists(staged)) return
        val version = runCatching { verifier.versionOf(staged) }.getOrNull()
        val ready = version != null && current != null && version > current && runCatching { verifier.verifyApp(staged, version) }.isSuccess
        if (ready) {
            _state.value = UpdateState.Ready(version, null)
        } else {
            logger.info("Deleting {}: it is not a newer version that checks", staged)
            runCatching { deleteRecursively(staged) }
        }
    }

    private suspend fun check(userAsked: Boolean) {
        look(userAsked)?.let { download(it) }
    }

    /** Reads the manifest and says what it found: the manifest of a version to download now, or `null` (the state says the rest). */
    private fun look(userAsked: Boolean): UpdateManifest? {
        if (!busy.tryLock()) return null
        try {
            val policy = policy()
            if (!policy.enabled) {
                _state.value = UpdateState.Unavailable(UpdateUnavailable.DISABLED_BY_ADMIN)
                return null
            }
            if (bundle is BundleLocation.Unavailable) {
                _state.value = UpdateState.Unavailable(bundle.reason, RELEASES_PAGE)
                return null
            }
            val before = _state.value
            if (before !is UpdateState.Ready) _state.value = UpdateState.Checking
            val manifest = try {
                parseManifest(http.text(policy.feed), allowFiles = feedOverride?.scheme == "file")
            } catch (e: Exception) {
                logger.info("Could not read the manifest {}", policy.feed, e)
                // A look that the user did not ask for and that fails says nothing over what was said: the network may only be down for now.
                _state.value = when {
                    before is UpdateState.Ready -> before
                    userAsked || before !is UpdateState.UpToDate -> UpdateState.Failed(reasonOf(e))
                    else -> before
                }
                return null
            }
            val now = clock()
            writeLastCheck(now)
            val newest = listOfNotNull(current, (before as? UpdateState.Ready)?.version).maxOrNull()
            if (newest == null || manifest.version <= newest) {
                _state.value = if (before is UpdateState.Ready) before else UpdateState.UpToDate(now)
                return null
            }
            logger.info("KaTTY {} is available ({} runs)", manifest.version, current)
            if (policy.action == UpdateAction.NOTIFY) {
                _state.value = UpdateState.Available(manifest)
                return null
            }
            return manifest
        } finally {
            busy.unlock()
        }
    }

    private suspend fun download(manifest: UpdateManifest) {
        val installed = bundle as? BundleLocation.Installed ?: return
        if (!busy.tryLock()) return
        try {
            val version = manifest.version
            val dmg = directories.updates.resolve(updateDiskImageName(version))
            var shown = -1
            try {
                _state.value = UpdateState.Downloading(version, 0f)
                http.download(manifest.dmg, dmg, manifest.size, manifest.sha256) { read ->
                    val percent = (read * 100 / manifest.size).toInt()
                    if (percent != shown) {
                        shown = percent
                        _state.value = UpdateState.Downloading(version, percent / 100f)
                    }
                }
                verifier.verifyAndStage(dmg, version, installed.staged)
                logger.info("KaTTY {} is ready in {}", version, installed.staged)
                _state.value = UpdateState.Ready(version, manifest.notes)
            } catch (e: Exception) {
                logger.warn("Could not download KaTTY {}", version, e)
                _state.value = UpdateState.Failed(reasonOf(e))
            } finally {
                runCatching { Files.deleteIfExists(dmg) }
            }
        } finally {
            busy.unlock()
        }
    }

    private fun lastCheck(): Instant? = runCatching { Instant.parse(Files.readString(lastCheckFile).trim()) }.getOrNull()

    private fun writeLastCheck(at: Instant) {
        runCatching {
            Files.createDirectories(directories.updates)
            Files.writeString(lastCheckFile, at.toString())
        }.onFailure { logger.debug("Could not write {}", lastCheckFile, it) }
    }

    private fun reasonOf(e: Exception): String = when (e) {
        is UpdateRejected -> e.message ?: "the new version was refused"
        is HttpTimeoutException, is ConnectException -> "the update server could not be reached"
        is app.katty.core.UpdateManifestException -> "the update server gave a manifest that is not valid (${e.message})"
        is IOException -> e.message ?: "the update could not be downloaded"
        else -> "the update failed (${e.javaClass.simpleName})"
    }
}
