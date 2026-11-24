package app.katty.terminals

import app.katty.core.AgentDetection
import app.katty.core.CUSTOM_SHELL
import app.katty.core.InstalledShell
import app.katty.core.Settings
import app.katty.core.ShellLaunch
import app.katty.core.findExecutable
import app.katty.core.parseShellCommand
import app.katty.core.resolveShell
import app.katty.platform.desktopIntegration
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

private val logger = LoggerFactory.getLogger("app.katty.terminals.ShellCatalog")

/**
 * The shells that are installed on this machine, which the settings offer, and the shell that a terminal starts. They are looked for the first time
 * they are needed (a restored terminal starts before the window shows), and again when the window gets the focus ([refresh]), as the IDEs are.
 */
object ShellCatalog {
    @Volatile
    private var shells: List<InstalledShell>? = null

    // The shells that were chosen and are not found any more, told once each.
    private val missing = ConcurrentHashMap.newKeySet<String>()

    /** The shells that are installed, looked for if they never were. Not for the UI thread the first time. */
    fun current(): List<InstalledShell> = shells ?: refresh()

    /** Looks for the shells again. Not for the UI thread. */
    fun refresh(): List<InstalledShell> = desktopIntegration.installedShells().also { shells = it }

    /** The shell that a terminal starts with [settings] (see [resolveShell]): the default one looks for no shell. */
    fun launch(settings: Settings): ShellLaunch {
        val choice = settings.shell
        val installed = if (choice == null || choice == CUSTOM_SHELL) emptyList() else current()
        val path = AgentDetection.knownLoginPath ?: System.getenv("PATH").orEmpty()
        return resolveShell(
            choice, settings.shellCommand, installed, ShellLaunch.default(),
            parse = { line -> parseShellCommand(line, find = { findExecutable(it, path)?.toString() }) },
            missing = { if (this.missing.add(it)) logger.warn("The shell {} is not installed any more: the terminals start the default one", it) },
        )
    }
}
