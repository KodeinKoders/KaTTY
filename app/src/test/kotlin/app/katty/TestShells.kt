package app.katty

import app.katty.core.isWindows
import app.katty.terminal.TestProgram
import app.katty.terminal.defaultShell
import java.nio.file.Path
import java.util.Base64

// What the tests of the application type and run where a test needs a real shell, on each system: on a Unix system zsh, sleep and printf, and on
// Windows PowerShell (the default shell there, `pwsh` when it is installed), ping, and a line of PowerShell that writes the same text.

/** An interactive shell that reads none of the user's files. */
internal val interactiveShell: List<String> = if (isWindows) listOf(defaultShell().first(), "-NoLogo", "-NoProfile") else listOf("/bin/zsh", "-f", "-i")

/** The name of the program of [interactiveShell], which a terminal is called at its prompt. */
internal val interactiveShellName: String = if (isWindows) programName(defaultShell().first()) else "zsh"

/** [interactiveShell] as a line of a script of `sh` (or of TestProgram), which `exec` starts. */
internal val interactiveShellLine: String =
    if (isWindows) "'${defaultShell().first()}' -NoLogo -NoProfile" else "${System.getenv("SHELL") ?: "/bin/zsh"} -f -i"

/** A command that runs for 30 s as a program of its own, for a shell to run: `sleep` is a cmdlet of PowerShell, which starts no program. */
internal val longCommand: String = if (isWindows) "ping -n 30 127.0.0.1" else "sleep 30"

/** The name of the program of [longCommand], and of the one of [runsAProgram]. */
internal val longCommandName: String = if (isWindows) "PING" else "sleep"

/**
 * A script of `sh` (or of TestProgram) whose shell runs a program for [seconds], which is the program that has the terminal: in a group of its own
 * (job control) on a Unix system, a child of the shell on Windows.
 */
internal fun runsAProgram(seconds: Int): String = if (isWindows) "exec ping -n ${seconds + 1} 127.0.0.1" else "set -m; sleep $seconds"

/** The address of [path] that a shell says with OSC 7: on Windows its drive after a slash, as PowerShell's integration writes it. */
internal fun fileUrl(path: String): String = if (isWindows) "file://localhost/" + path.replace('\\', '/') else "file://localhost$path"

/** The line to type at [interactiveShell] for it to write what printf's [format] writes, and run it. */
internal fun printfLine(format: String): String = if (isWindows) {
    "[Console]::Write([Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('${Base64.getEncoder().encodeToString(TestProgram.printf(listOf(format)))}')))\r"
} else {
    "printf '$format'\r"
}

/**
 * The AI command that writes the question it is asked in [asked], when given, and answers [answer] as it is: for `sh`, and on Windows for
 * PowerShell, which runs it there.
 */
internal fun aiAnswers(answer: String, asked: Path? = null): String = if (isWindows) {
    val save = asked?.let { "Set-Content -NoNewline -LiteralPath '$it' -Value \$PROMPT; " }.orEmpty()
    save + "[Console]::Write([Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('${Base64.getEncoder().encodeToString(answer.toByteArray())}')))"
} else {
    val save = asked?.let { "printf '%s' \"\$PROMPT\" > '$it'; " }.orEmpty()
    save + "printf '%s' '${answer.replace("'", "'\\''")}'"
}

// The name of a program as KaTTY says it: without its folder, nor `.exe` on Windows.
private fun programName(path: String): String = path.substringAfterLast('\\').substringAfterLast('/').removeSuffix(".exe").removeSuffix(".EXE")
