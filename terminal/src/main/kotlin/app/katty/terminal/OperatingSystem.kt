package app.katty.terminal

import java.io.File
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption
import java.nio.file.Path

/** Whether the terminal runs on Windows, where what differs only by a value (a path, a shell, a signal) differs. */
internal val isWindows: Boolean = System.getProperty("os.name").orEmpty().startsWith("Windows")

/**
 * The shell that a terminal runs when it is given no command: the user's login shell (`$SHELL -l`, zsh when it is not set) on a Unix system, and
 * PowerShell on Windows, PowerShell 7 (`pwsh`) when it is on the `PATH`, else the Windows PowerShell that every Windows has.
 */
public fun defaultShell(): List<String> = if (isWindows) windowsShell() else listOf(System.getenv("SHELL") ?: "/bin/zsh", "-l")

private fun windowsShell(): List<String> {
    val pwsh = System.getenv("PATH").orEmpty().split(File.pathSeparatorChar).filter { it.isNotBlank() }
        .mapNotNull { folder -> try { Path.of(folder, "pwsh.exe") } catch (_: InvalidPathException) { null } }
        // Not `File.exists`: the pwsh of the Microsoft Store is an alias of the system (`WindowsApps\pwsh.exe`, a reparse point that the JVM
        // does not follow), which it says is not there, though Windows starts it (measured).
        .firstOrNull { Files.exists(it, LinkOption.NOFOLLOW_LINKS) }
    return listOf(pwsh?.toString() ?: "powershell.exe", "-NoLogo")
}
