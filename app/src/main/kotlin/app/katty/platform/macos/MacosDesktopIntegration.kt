package app.katty.platform.macos

import app.katty.core.Ide
import app.katty.platform.DesktopIntegration
import app.katty.terminal.TerminalFilePosition

/** macOS: the applications are known to LaunchServices by their bundle identifier, and held keys are a default of AppKit. */
internal object MacosDesktopIntegration : DesktopIntegration {
    override fun installedIdes(): Map<Ide, String> = app.katty.platform.macos.installedIdes()

    override fun openIn(application: String, directory: String) = openInApplication(application, directory)

    override fun openFile(ide: Ide, application: String, file: TerminalFilePosition) = openFileInApplication(ide, application, file)

    // The Finder, through AWT: it shows the file selected in its folder.
    override fun reveal(path: String) {
        try {
            java.awt.Desktop.getDesktop().browseFileDirectory(java.io.File(path))
        } catch (e: Exception) {
            org.slf4j.LoggerFactory.getLogger(MacosDesktopIntegration::class.java).warn("Could not show {} in the Finder", path, e)
        }
    }

    override fun disablePressAndHold() = app.katty.platform.macos.disablePressAndHold()

    override fun installedShells(): List<app.katty.core.InstalledShell> = app.katty.platform.unixShells()
}
