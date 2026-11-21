package app.katty.platform

import app.katty.core.Ide
import app.katty.platform.macos.MacosDesktopIntegration
import app.katty.platform.windows.WindowsDesktopIntegration
import org.slf4j.LoggerFactory
import app.katty.terminal.TerminalFilePosition

// What the application asks of the system it runs on, which each system does its own way: one implementation for each, in a package of its own
// (`macos`, `windows`), chosen once by [desktopIntegration]. Nothing outside this package uses an implementation directly (PlatformBoundaryTest checks it).

/** What the application does with the desktop of the system: the applications that are installed, and its own behaviour there. */
internal interface DesktopIntegration {
    /**
     * The IDEs that are installed, with what the system knows each one as (a bundle identifier on macOS), which is what [openIn] and [openFile]
     * are given back. It asks the system: not for the UI thread.
     */
    fun installedIdes(): Map<Ide, String>

    /** Opens [directory] in [application], one of the values of [installedIdes]. A failure is logged. */
    fun openIn(application: String, directory: String)

    /** Opens [file] at its line in [ide], installed as [application]. Not for the UI thread. A failure is logged. */
    fun openFile(ide: Ide, application: String, file: TerminalFilePosition)

    /**
     * Shows the file or the folder at [path] selected in its folder, in the file manager of the system (the Finder, File Explorer), and opens
     * nothing: a file that is opened may run (a script, an application). A failure is logged.
     */
    fun reveal(path: String)

    /** Keeps a held key from showing a menu of accents instead of repeating it, before the first key is typed. A failure is logged. */
    fun disablePressAndHold()
}

/** The implementation of the system the application runs on: a system that has none has no IDE to offer, and changes nothing. */
internal val desktopIntegration: DesktopIntegration = when {
    System.getProperty("os.name").orEmpty().startsWith("Mac") -> MacosDesktopIntegration
    System.getProperty("os.name").orEmpty().startsWith("Windows") -> WindowsDesktopIntegration
    else -> object : DesktopIntegration {
        override fun installedIdes(): Map<Ide, String> = emptyMap()

        override fun openIn(application: String, directory: String) {}

        override fun openFile(ide: Ide, application: String, file: TerminalFilePosition) {}

        override fun reveal(path: String) {
            LoggerFactory.getLogger(DesktopIntegration::class.java).warn("This system has no way to show {} in a file manager", path)
        }

        override fun disablePressAndHold() {}
    }
}
