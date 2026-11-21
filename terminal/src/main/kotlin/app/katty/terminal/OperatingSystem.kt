package app.katty.terminal

import java.io.File

/** Whether the terminal runs on Windows, where what differs only by a value (a path, a shell, a signal) differs. */
internal val isWindows: Boolean = System.getProperty("os.name").orEmpty().startsWith("Windows")

/**
 * The shell that a terminal runs when it is given no command: the user's login shell (`$SHELL -l`, zsh when it is not set) on a Unix system, and
 * PowerShell on Windows, PowerShell 7 (`pwsh`) when it is on the `PATH`, else the Windows PowerShell that every Windows has.
 */
public fun defaultShell(): List<String> = if (isWindows) windowsShell() else listOf(System.getenv("SHELL") ?: "/bin/zsh", "-l")

private fun windowsShell(): List<String> {
    val pwsh = System.getenv("PATH").orEmpty().split(File.pathSeparatorChar).filter { it.isNotEmpty() }
        .map { File(it, "pwsh.exe") }.firstOrNull { it.exists() }
    return listOf(pwsh?.path ?: "powershell.exe", "-NoLogo")
}
