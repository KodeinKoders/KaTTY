package app.katty.update

import app.katty.core.UpdateUnavailable
import java.nio.file.Files
import java.nio.file.Path

/** Where the application that runs is, as far as replacing it goes. */
internal sealed interface BundleLocation {
    /** A bundle that can be replaced by another next to it. */
    data class Installed(val app: Path) : BundleLocation {
        /** Where a new version waits, on the volume of the application so that the two can be swapped: not a name that ends with `.app`. */
        val staged: Path get() = app.resolveSibling(".${app.fileName}.update")
    }

    data class Unavailable(val reason: UpdateUnavailable) : BundleLocation
}

/**
 * The bundle of the application, from the path of its launcher that jpackage gives in `jpackage.app-path`
 * (`…/KaTTY.app/Contents/MacOS/KaTTY`). No path is an application that runs from Gradle or a jar; a path under `AppTranslocation` is a copy
 * that macOS made of one that was downloaded and not moved, and replacing it would change nothing; and a bundle or a folder that cannot be
 * written (the disk image, a folder of an administrator) cannot be replaced by the user.
 */
internal fun locateBundle(
    appPath: String? = System.getProperty("jpackage.app-path"),
    writable: (Path) -> Boolean = Files::isWritable,
): BundleLocation {
    val launcher = appPath?.takeIf { it.isNotBlank() }?.let { Path.of(it).toAbsolutePath().normalize() }
        ?: return BundleLocation.Unavailable(UpdateUnavailable.NOT_PACKAGED)
    val app = launcher.parent?.parent?.parent
    if (app == null || !app.fileName.toString().endsWith(".app") || launcher.parent.parent.fileName.toString() != "Contents") {
        return BundleLocation.Unavailable(UpdateUnavailable.NOT_PACKAGED)
    }
    if ("/AppTranslocation/" in app.toString()) return BundleLocation.Unavailable(UpdateUnavailable.TRANSLOCATED)
    if (!writable(app) || !writable(app.parent)) return BundleLocation.Unavailable(UpdateUnavailable.READ_ONLY)
    return BundleLocation.Installed(app)
}
