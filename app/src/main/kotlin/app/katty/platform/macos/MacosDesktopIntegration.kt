package app.katty.platform.macos

import app.katty.core.Ide
import app.katty.platform.DesktopIntegration
import app.katty.terminal.TerminalFilePosition

/** macOS: the applications are known to LaunchServices by their bundle identifier, and held keys are a default of AppKit. */
internal object MacosDesktopIntegration : DesktopIntegration {
    override fun installedIdes(): Map<Ide, String> = app.katty.platform.macos.installedIdes()

    override fun openIn(application: String, directory: String) = openInApplication(application, directory)

    override fun openFile(ide: Ide, application: String, file: TerminalFilePosition) = openFileInApplication(ide, application, file)

    override fun disablePressAndHold() = app.katty.platform.macos.disablePressAndHold()
}
