package app.katty.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.net.URI
import java.time.Duration
import java.time.Instant

/**
 * A version of KaTTY as the build makes it (`1.0.322`: see the root build script), compared number by number. A version that is not only numbers
 * is not one ([parse] gives `null`), so that nothing is ever taken for newer than what runs by mistake.
 */
public class AppVersion private constructor(public val numbers: List<Int>) : Comparable<AppVersion> {
    override fun compareTo(other: AppVersion): Int {
        for (i in 0 until maxOf(numbers.size, other.numbers.size)) {
            val compared = numbers.getOrElse(i) { 0 }.compareTo(other.numbers.getOrElse(i) { 0 })
            if (compared != 0) return compared
        }
        return 0
    }

    override fun equals(other: Any?): Boolean = other is AppVersion && compareTo(other) == 0

    override fun hashCode(): Int = numbers.dropLastWhile { it == 0 }.hashCode()

    override fun toString(): String = numbers.joinToString(".")

    public companion object {
        public fun parse(text: String): AppVersion? {
            val parts = text.trim().split('.')
            if (parts.size < 2 || parts.any { it.isEmpty() || it.length > 9 || !it.all(Char::isDigit) }) return null
            return AppVersion(parts.map { it.toInt() })
        }
    }
}

/**
 * What the release workflow publishes beside the disk image of a release, as `latest.json`: the version, where its disk image is, how big it is and
 * its SHA-256, and the page of the release. The application reads it at `releases/latest/download/latest.json`, which GitHub sends to the file of
 * the release that is the latest, and which is not its REST API (no limit of requests for an address).
 */
public data class UpdateManifest(
    val version: AppVersion,
    val dmg: URI,
    val size: Long,
    val sha256: String,
    val notes: URI?,
)

/** A manifest that is not one: the application says so and installs nothing. */
public class UpdateManifestException(message: String) : Exception(message)

private val manifestJson = Json { ignoreUnknownKeys = true }

/** The name of the disk image of a version, as the build names it. */
public fun updateDiskImageName(version: AppVersion): String = "KaTTY-$version.dmg"

/**
 * Reads a manifest. Its fields that are not known are ignored, so that one can be added. The addresses must be `https` (`file` too when
 * [allowFiles], which only a feed given for a test is), the SHA-256 is 64 hexadecimal digits, and the disk image is named after the version
 * ([updateDiskImageName]): what does not say what it claims is not taken.
 */
public fun parseManifest(text: String, allowFiles: Boolean = false): UpdateManifest {
    val root = runCatching { manifestJson.parseToJsonElement(text) as? JsonObject }.getOrNull()
        ?: throw UpdateManifestException("the manifest is not a JSON object")

    fun string(name: String): String? = (root[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

    fun address(name: String): URI? {
        val text = string(name) ?: return null
        val uri = runCatching { URI(text) }.getOrNull() ?: throw UpdateManifestException("$name is not an address")
        val scheme = uri.scheme?.lowercase()
        if (scheme != "https" && !(allowFiles && scheme == "file")) throw UpdateManifestException("$name is not an https address")
        return uri
    }

    val version = string("version")?.let { AppVersion.parse(it) } ?: throw UpdateManifestException("the manifest has no version")
    val dmg = address("dmg") ?: throw UpdateManifestException("the manifest has no disk image")
    val size = (root["size"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull?.takeIf { it > 0 }
        ?: throw UpdateManifestException("the manifest has no size")
    val sha256 = string("sha256")?.lowercase()?.takeIf { it.length == 64 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }
        ?: throw UpdateManifestException("the manifest has no SHA-256")
    val name = dmg.path?.substringAfterLast('/')
    if (name != updateDiskImageName(version)) throw UpdateManifestException("the disk image $name is not the one of $version")
    return UpdateManifest(version, dmg, size, sha256, address("notes"))
}

/** How often the application looks for a new version. */
@Serializable
public enum class UpdateCheck(public val interval: Duration?) {
    HOURLY(Duration.ofHours(1)),
    DAILY(Duration.ofDays(1)),
    WEEKLY(Duration.ofDays(7)),

    /** Only when the user asks. */
    MANUALLY(null),
}

/** What the application does when it finds a new version. */
@Serializable
public enum class UpdateAction {
    /** It says so, and downloads it when the user asks. */
    NOTIFY,

    /** It downloads it, and installs it when the user asks (Restart to update). */
    DOWNLOAD,

    /** It downloads it, and installs it when the application quits, or when the user asks. */
    INSTALL_ON_QUIT,
}

/** Whether a look for a new version is due: never for [UpdateCheck.MANUALLY], at once when none was made, else once its interval has passed. */
public fun isCheckDue(lastCheck: Instant?, now: Instant, check: UpdateCheck): Boolean {
    val interval = check.interval ?: return false
    return lastCheck == null || lastCheck.isAfter(now) || !lastCheck.plus(interval).isAfter(now)
}

/**
 * What an organization imposes on the updates (the managed preferences of the application's domain, which a configuration profile sets):
 * `null` for what it leaves to the user.
 */
public data class ManagedUpdates(
    val disabled: Boolean? = null,
    val check: UpdateCheck? = null,
    val action: UpdateAction? = null,
    val feed: String? = null,
) {
    public companion object {
        public val NONE: ManagedUpdates = ManagedUpdates()

        /** The values of the managed preferences `UpdateCheck` and `UpdateAction`, by the names that a profile writes. */
        public fun checkNamed(name: String?): UpdateCheck? = when (name?.trim()?.lowercase()) {
            "hourly" -> UpdateCheck.HOURLY
            "daily" -> UpdateCheck.DAILY
            "weekly" -> UpdateCheck.WEEKLY
            "manually" -> UpdateCheck.MANUALLY
            else -> null
        }

        public fun actionNamed(name: String?): UpdateAction? = when (name?.trim()?.lowercase()) {
            "notify" -> UpdateAction.NOTIFY
            "download" -> UpdateAction.DOWNLOAD
            "install-on-quit" -> UpdateAction.INSTALL_ON_QUIT
            else -> null
        }
    }
}

/** The address of the manifest of the latest release. */
public const val DEFAULT_UPDATE_FEED: String = "https://github.com/KodeinKoders/KaTTY/releases/latest/download/latest.json"

/** How the application looks for updates: the user's settings, and over them what the organization imposes, which is said ([checkManaged]…). */
public data class UpdatePolicy(
    val enabled: Boolean,
    val check: UpdateCheck,
    val action: UpdateAction,
    val feed: URI,
    val checkManaged: Boolean,
    val actionManaged: Boolean,
)

/** The policy of [settings] under [managed]. A managed feed that is not an `https` address is not taken: the default one is. */
public fun updatePolicy(settings: Settings, managed: ManagedUpdates, defaultFeed: URI = URI(DEFAULT_UPDATE_FEED)): UpdatePolicy {
    val feed = managed.feed?.let { runCatching { URI(it.trim()) }.getOrNull() }?.takeIf { it.scheme?.lowercase() == "https" } ?: defaultFeed
    return UpdatePolicy(
        enabled = managed.disabled != true,
        check = managed.check ?: settings.updateCheck,
        action = managed.action ?: settings.updateAction,
        feed = feed,
        checkManaged = managed.check != null,
        actionManaged = managed.action != null,
    )
}

/** Why the application cannot update itself. */
public enum class UpdateUnavailable {
    /** It runs from Gradle or a jar: there is no bundle to replace. */
    NOT_PACKAGED,

    /** macOS runs it from a hidden copy, as it does for an application that was downloaded and not moved. */
    TRANSLOCATED,

    /** Its folder cannot be written (the disk image, or a folder that needs an administrator). */
    READ_ONLY,

    /** The organization turned the updates off. */
    DISABLED_BY_ADMIN,
}

/** Where the updates of the application are. */
public sealed interface UpdateState {
    public data object Idle : UpdateState

    public data object Checking : UpdateState

    public data class UpToDate(val checkedAt: Instant) : UpdateState

    /** A new version, which the user is told of and which is downloaded when they ask ([UpdateAction.NOTIFY]). */
    public data class Available(val manifest: UpdateManifest) : UpdateState

    public data class Downloading(val version: AppVersion, val fraction: Float) : UpdateState

    /** A new version that is checked and ready, next to the application: it replaces it when it quits. */
    public data class Ready(val version: AppVersion, val notes: URI?) : UpdateState

    public data class Failed(val reason: String) : UpdateState

    /** The application cannot update itself; [releases] is where the user can download a version. */
    public data class Unavailable(val reason: UpdateUnavailable, val releases: URI? = null) : UpdateState
}
