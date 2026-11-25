package app.katty.update

import app.katty.core.UpdateUnavailable
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class RunningBundleTest {
    private val everything: (Path) -> Boolean = { true }

    @Test
    fun theBundleIsTheFolderAboveTheLauncher() {
        val location = locateBundle("/Applications/KaTTY.app/Contents/MacOS/KaTTY", everything)
        assertEquals(BundleLocation.Installed(Path.of("/Applications/KaTTY.app")), location)
        assertEquals(Path.of("/Applications/.KaTTY.app.update"), (location as BundleLocation.Installed).staged)
    }

    @Test
    fun anApplicationThatRunsFromGradleIsNotPackaged() {
        assertEquals(BundleLocation.Unavailable(UpdateUnavailable.NOT_PACKAGED), locateBundle(null, everything))
        assertEquals(BundleLocation.Unavailable(UpdateUnavailable.NOT_PACKAGED), locateBundle("", everything))
        assertEquals(BundleLocation.Unavailable(UpdateUnavailable.NOT_PACKAGED), locateBundle("/usr/local/bin/katty", everything))
    }

    @Test
    fun aCopyThatMacOsMovedAsideCannotBeUpdated() {
        val path = "/private/var/folders/x/T/AppTranslocation/1234-5678/d/KaTTY.app/Contents/MacOS/KaTTY"
        assertEquals(BundleLocation.Unavailable(UpdateUnavailable.TRANSLOCATED), locateBundle(path, everything))
    }

    @Test
    fun aFolderThatCannotBeWrittenCannotBeUpdated() {
        val path = "/Volumes/KaTTY/KaTTY.app/Contents/MacOS/KaTTY"
        assertEquals(BundleLocation.Unavailable(UpdateUnavailable.READ_ONLY), locateBundle(path) { false })
        assertEquals(BundleLocation.Unavailable(UpdateUnavailable.READ_ONLY), locateBundle(path) { it.fileName.toString() == "KaTTY.app" })
    }
}
