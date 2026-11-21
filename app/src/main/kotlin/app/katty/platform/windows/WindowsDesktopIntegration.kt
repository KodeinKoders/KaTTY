package app.katty.platform.windows

import app.katty.core.Ide
import app.katty.core.InstalledProgram
import app.katty.core.detectWindowsIdes
import app.katty.core.findExecutable
import app.katty.core.windowsOpenFileCommand
import app.katty.platform.DesktopIntegration
import app.katty.terminal.TerminalFilePosition
import org.slf4j.LoggerFactory
import java.io.File

private val logger = LoggerFactory.getLogger("app.katty.platform.windows.WindowsDesktopIntegration")

// Where Windows lists the programs that are installed: for every user, its 32-bit view, and the user's own (JetBrains Toolbox puts its IDEs there).
private val UNINSTALL = listOf(
    RegistryRoot.LOCAL_MACHINE to "Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall",
    RegistryRoot.LOCAL_MACHINE to "Software\\WOW6432Node\\Microsoft\\Windows\\CurrentVersion\\Uninstall",
    RegistryRoot.CURRENT_USER to "Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall",
)

// Where an executable is registered by its name, which `start` and the Run dialog find it by.
private const val APP_PATHS = "Software\\Microsoft\\Windows\\CurrentVersion\\App Paths"

/**
 * Windows: an IDE is found by its executable, in the programs that the system lists as installed (their uninstall keys, `App Paths`) or on the
 * `PATH`, and that executable is what opens a folder or a file; File Explorer shows what is revealed. No key is held for accents.
 */
internal object WindowsDesktopIntegration : DesktopIntegration {
    override fun installedIdes(): Map<Ide, String> = try {
        detectWindowsIdes(installedPrograms(), onPath = { findExecutable(it, System.getenv("PATH").orEmpty())?.toString() }, exists = { File(it).isFile })
    } catch (e: Throwable) {
        logger.warn("Could not read the programs that are installed", e)
        emptyMap()
    }

    override fun openIn(application: String, directory: String) = start(listOf(application, directory), directory)

    override fun openFile(ide: Ide, application: String, file: TerminalFilePosition) =
        start(windowsOpenFileCommand(ide, application, file.path, file.line, file.column, file.directory), file.path)

    // `/select,` and the path apart: Java puts an argument that has a space in quotes, and File Explorer does not read `"/select,C:\a b"`.
    override fun reveal(path: String) = start(listOf("explorer.exe", "/select,", path), path)

    override fun disablePressAndHold() {}

    // The command, which is never given to a shell, with nothing to read on its input and its output going nowhere.
    private fun start(command: List<String>, what: String) {
        try {
            ProcessBuilder(command).redirectInput(ProcessBuilder.Redirect.from(File("NUL")))
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        } catch (e: Exception) {
            logger.warn("Could not open {} with {}", what, command.first(), e)
        }
    }
}

/**
 * The programs that Windows lists as installed, with the executable of each: the `DisplayIcon` of an uninstall key (`"C:\…\idea64.exe",0`),
 * and the executables of the IDEs that `App Paths` names. Not for the UI thread: it reads a few hundred keys.
 */
internal fun installedPrograms(): List<InstalledProgram> {
    val programs = ArrayList<InstalledProgram>()
    for ((root, path) in UNINSTALL) {
        for (name in registrySubKeys(root, path)) {
            val key = "$path\\$name"
            val displayName = registryString(root, key, "DisplayName") ?: continue
            val executable = registryString(root, key, "DisplayIcon")?.let(::iconExecutable) ?: continue
            programs += InstalledProgram(displayName, executable)
        }
    }
    for (executable in Ide.entries.flatMap { it.windowsExecutables }.distinct()) {
        for (root in RegistryRoot.entries) {
            val path = registryString(root, "$APP_PATHS\\$executable", null)?.trim('"') ?: continue
            programs += InstalledProgram(executable, path)
        }
    }
    return programs
}

// The executable of a `DisplayIcon`: a path, in quotes or not, followed by the index of the icon (`,0`); `null` when it is not an `.exe`.
internal fun iconExecutable(icon: String): String? {
    val text = icon.trim()
    val path = if (text.startsWith('"')) text.drop(1).substringBefore('"') else text.replace(ICON_INDEX, "")
    return path.trim().takeIf { it.endsWith(".exe", ignoreCase = true) }
}

private val ICON_INDEX = Regex(",\\s*-?\\d+$")
