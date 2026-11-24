package app.katty.core

import app.katty.terminal.defaultShell

/**
 * A shell that was found on this machine, which the terminals can start instead of the default one ([Settings.shell]).
 *
 * @property id What [Settings.shell] keeps of it: the path of its program, which tells two installs of one shell apart and does not change when it
 * is updated.
 * @property name The name of its program, as the process is called (`bash`, `pwsh`), which is what says that the shell has the terminal again.
 * @property label What the settings and the palette call it (`Git Bash`, `MSYS2 zsh (UCRT64)`, `fish`).
 * @property argv How it is started: its program and what makes it a login shell.
 * @property environment What it needs in its environment (`MSYSTEM` for MSYS2), added to the terminal's.
 */
public data class InstalledShell(
    val id: String,
    val name: String,
    val label: String,
    val argv: List<String>,
    val environment: Map<String, String> = emptyMap(),
)

/**
 * The shell that a terminal starts, as [resolveShell] decides it from the settings.
 *
 * @property isDefault Whether it is the default one (`$SHELL` on macOS, PowerShell on Windows), which is what any setting that cannot be followed
 * comes back to.
 */
public data class ShellLaunch(
    val argv: List<String>,
    val environment: Map<String, String>,
    val name: String,
    val label: String,
    val isDefault: Boolean,
) {
    public companion object {
        /** The shell that the terminals start when nothing else is chosen: the user's login shell, or PowerShell on Windows. */
        public fun default(): ShellLaunch {
            val argv = defaultShell()
            val name = fileName(argv.first())
            return ShellLaunch(argv, emptyMap(), name, if (name == "pwsh") "PowerShell 7" else if (name == "powershell") "Windows PowerShell" else name, isDefault = true)
        }
    }
}

// The name of a program without its folders, whichever separator its path has, and without the `.exe` of Windows: a path of Windows is read the
// same on any system (its tests run on macOS too).
internal fun fileName(path: String): String =
    path.substringAfterLast('/').substringAfterLast('\\').let { if (it.endsWith(".exe", ignoreCase = true)) it.dropLast(4) else it }

private val UNIX_SHELLS = listOf("zsh", "bash", "fish", "pwsh")

/**
 * The folders where a shell of a Unix system is looked for, before the `PATH` of the user's login shell: the system's, and Homebrew's of both kinds
 * of Mac.
 */
public val UNIX_SHELL_FOLDERS: List<String> = listOf("/bin", "/usr/bin", "/usr/local/bin", "/opt/homebrew/bin")

/**
 * The shells of a Unix system (zsh, bash, fish and pwsh): in [folders] (the system's, then the folders of the `PATH`) and the lines of [etcShells]
 * (`/etc/shells`), the ones that [isExecutable] says are there. A file that two paths lead to ([canonical]: `/bin/bash` and a link to it) is one shell,
 * found at the first path. Each starts as a login shell (`-l`; pwsh's is `-Login`, which must be its first parameter). Two shells of the same name are
 * told apart by their folder.
 */
public fun detectUnixShells(
    folders: List<String>,
    etcShells: List<String>,
    isExecutable: (String) -> Boolean,
    canonical: (String) -> String,
): List<InstalledShell> {
    val candidates = folders.flatMap { folder -> UNIX_SHELLS.map { "${folder.trimEnd('/')}/$it" } } +
        etcShells.map { it.trim() }.filter { it.startsWith("/") && fileName(it) in UNIX_SHELLS }
    val seen = HashSet<String>()
    val found = candidates.filter { path -> isExecutable(path) && seen.add(canonical(path)) }
    return found.map { path ->
        val name = fileName(path)
        val twin = found.count { fileName(it) == name } > 1
        val argv = if (name == "pwsh") listOf(path, "-Login", "-NoLogo") else listOf(path, "-l")
        InstalledShell(path, name, if (twin) "$name (${path.substringBeforeLast('/')})" else name, argv)
    }
}

/**
 * Where the POSIX environments of Windows are installed, each a folder that holds the tree of its system (`usr\bin`, `etc`): what [detectWindowsShells]
 * looks in. The platform finds them (the registry, the folders of their installers), in their order of preference.
 */
public data class WindowsShellRoots(val git: List<String>, val msys2: List<String>, val cygwin: List<String>)

/**
 * The shells of Windows: pwsh ([pwsh], found on the `PATH`, else in its folder of Program Files), Windows PowerShell, and the bash, zsh and fish of
 * the POSIX environments of [roots] that [exists] says are there.
 *
 * - **Git Bash** is the bash of Git for Windows, `usr\bin\bash.exe` and not the `bin\bash.exe` launcher, which only starts it with one process more
 *   (measured), with the `MSYSTEM` of the toolchain that the install has (`clangarm64` on ARM64, else `mingw64`): what its launcher sets.
 * - **MSYS2**'s shells are given `MSYSTEM=UCRT64`, its default, and `MSYS2_PATH_TYPE=inherit` unless [inherited] has one: by default an MSYS2 shell does
 *   not see the `PATH` of Windows, so neither the programs of Windows nor the agents would be found in it (the user's choice).
 * - **Cygwin**'s inherit the `PATH` of Windows by themselves.
 *
 * Each is started as a login shell (`--login -i`), which reads the profile of its environment. cmd and WSL are not offered (the user's choice).
 */
public fun detectWindowsShells(
    roots: WindowsShellRoots,
    exists: (String) -> Boolean,
    pwsh: String?,
    systemRoot: String,
    programFiles: String,
    inherited: Map<String, String> = System.getenv(),
): List<InstalledShell> {
    val shells = ArrayList<InstalledShell>()
    val seen = HashSet<String>()
    fun add(shell: InstalledShell) {
        if (seen.add(shell.id.lowercase())) shells += shell
    }
    (pwsh ?: "$programFiles\\PowerShell\\7\\pwsh.exe".takeIf(exists))?.let { add(InstalledShell(it, "pwsh", "PowerShell 7", listOf(it, "-NoLogo"))) }
    "$systemRoot\\System32\\WindowsPowerShell\\v1.0\\powershell.exe".takeIf(exists)?.let {
        add(InstalledShell(it, "powershell", "Windows PowerShell", listOf(it, "-NoLogo")))
    }
    for (root in roots.git) {
        val bash = "$root\\usr\\bin\\bash.exe".takeIf(exists) ?: continue
        val system = listOf("clangarm64", "mingw64", "mingw32").firstOrNull { exists("$root\\$it") }?.uppercase() ?: "MINGW64"
        add(InstalledShell(bash, "bash", "Git Bash", listOf(bash, "--login", "-i"), mapOf("MSYSTEM" to system)))
    }
    for (root in roots.msys2) {
        val environment = buildMap {
            put("MSYSTEM", "UCRT64")
            if (inherited["MSYS2_PATH_TYPE"].isNullOrEmpty()) put("MSYS2_PATH_TYPE", "inherit")
        }
        for (shell in listOf("bash", "zsh", "fish")) {
            val program = "$root\\usr\\bin\\$shell.exe".takeIf(exists) ?: continue
            add(InstalledShell(program, shell, "MSYS2 $shell", listOf(program, "--login", "-i"), environment))
        }
    }
    for (root in roots.cygwin) {
        for (shell in listOf("bash", "zsh", "fish")) {
            val program = "$root\\bin\\$shell.exe".takeIf(exists) ?: continue
            add(InstalledShell(program, shell, "Cygwin $shell", listOf(program, "--login", "-i")))
        }
    }
    return shells
}

/**
 * The shell of a command line that the user typed ([Settings.shellCommand]), `null` for a blank one. Words of the form `NAME=value` before the
 * program go to its environment (`MSYSTEM=UCRT64 C:\msys64\usr\bin\zsh.exe --login -i`). The words are split as the system splits a command line:
 * on Windows as `CommandLineToArgvW` does (double quotes, the backslashes of a path as they are), elsewhere as a POSIX shell does (single and double
 * quotes, a backslash escapes, `~` at the start is the home). A program without a folder is looked for with [find] (in the `PATH`), and kept as it is
 * typed when it is not found, so that the terminal says it could not start it.
 */
public fun parseShellCommand(line: String, windows: Boolean = isWindows, home: String = System.getProperty("user.home"), find: (String) -> String? = { null }): ShellLaunch? {
    val words = if (windows) splitWindows(line) else splitPosix(line, home)
    val environment = LinkedHashMap<String, String>()
    var index = 0
    while (index < words.size && ENVIRONMENT_WORD.matches(words[index])) {
        val (name, value) = words[index].split('=', limit = 2)
        environment[name] = value
        index++
    }
    val argv = words.drop(index).toMutableList()
    if (argv.isEmpty()) return null
    if ('/' !in argv[0] && '\\' !in argv[0]) find(argv[0])?.let { argv[0] = it }
    val name = fileName(argv[0])
    return ShellLaunch(argv, environment, name, name, isDefault = false)
}

private val ENVIRONMENT_WORD = Regex("[A-Za-z_][A-Za-z0-9_]*=.*")

// What CommandLineToArgvW makes of a line, simplified: whitespace separates words, double quotes group them (and are taken away), a backslash is
// itself unless it comes before a double quote.
private fun splitWindows(line: String): List<String> {
    val words = ArrayList<String>()
    val word = StringBuilder()
    var quoted = false
    var started = false
    var i = 0
    while (i < line.length) {
        val c = line[i]
        when {
            c == '\\' && i + 1 < line.length && line[i + 1] == '"' -> { word.append('"'); i++; started = true }
            c == '"' -> { quoted = !quoted; started = true }
            c.isWhitespace() && !quoted -> { if (started) words += word.toString(); word.clear(); started = false }
            else -> { word.append(c); started = true }
        }
        i++
    }
    if (started) words += word.toString()
    return words
}

// What a POSIX shell makes of a line, for words: single quotes keep everything, double quotes keep everything but a backslash before \ " $ `, a
// backslash escapes the next character outside them, and a ~ that starts a word is the home folder.
private fun splitPosix(line: String, home: String): List<String> {
    val words = ArrayList<String>()
    val word = StringBuilder()
    var started = false
    var quote: Char? = null
    var i = 0
    while (i < line.length) {
        val c = line[i]
        when {
            quote == '\'' -> if (c == '\'') quote = null else word.append(c)
            quote == '"' -> when {
                c == '"' -> quote = null
                c == '\\' && i + 1 < line.length && line[i + 1] in "\\\"\$`" -> { word.append(line[i + 1]); i++ }
                else -> word.append(c)
            }
            c == '\'' || c == '"' -> { quote = c; started = true }
            c == '\\' && i + 1 < line.length -> { word.append(line[i + 1]); i++; started = true }
            c.isWhitespace() -> { if (started) words += word.toString(); word.clear(); started = false }
            c == '~' && !started && (i + 1 == line.length || line[i + 1] == '/' || line[i + 1].isWhitespace()) -> { word.append(home); started = true }
            else -> { word.append(c); started = true }
        }
        i++
    }
    if (started) words += word.toString()
    return words
}

/** The value of [Settings.shell] that stands for the command that the user typed, [Settings.shellCommand]. */
public const val CUSTOM_SHELL: String = "custom"

/**
 * The shell that the terminals start, from the settings: the [default] one when nothing is chosen ([shell] `null`), when the command that the user
 * typed is blank, or when the shell that was chosen is not among the [installed] ones any more (uninstalled, moved: [missing] is told, so that it is
 * said once); the one of [installed] whose id is [shell] (any case on Windows); else the [command] that the user typed.
 */
public fun resolveShell(
    shell: String?,
    command: String,
    installed: List<InstalledShell>,
    default: ShellLaunch,
    windows: Boolean = isWindows,
    parse: (String) -> ShellLaunch? = { parseShellCommand(it, windows) },
    missing: (String) -> Unit = {},
): ShellLaunch = when (shell) {
    null -> default
    CUSTOM_SHELL -> parse(command) ?: default
    else -> installed.firstOrNull { it.id == shell || (windows && it.id.equals(shell, ignoreCase = true)) }
        ?.let { ShellLaunch(it.argv, it.environment, it.name, it.label, isDefault = false) }
        ?: default.also { missing(shell) }
}

/**
 * How a run terminal runs [command] with the shell [launch]: PowerShell with `-Command`, zsh, bash and fish with `-c` as a login and interactive shell
 * (so that the `PATH` and the aliases of its files are there). A shell that KaTTY does not know does not say how it runs a command: the [default]
 * one runs it then.
 */
public fun runArgv(launch: ShellLaunch, command: String, default: ShellLaunch, windows: Boolean = isWindows): ShellLaunch {
    fun with(shell: ShellLaunch, arguments: List<String>) = shell.copy(argv = listOf(shell.argv.first()) + arguments)
    return when (launch.name.lowercase()) {
        "pwsh", "powershell" -> with(launch, listOf("-NoLogo", "-Command", command))
        "zsh", "bash", "fish" -> with(launch, listOf(if (windows) "--login" else "-l", "-i", "-c", command))
        // The default shell of a Unix system that KaTTY does not know (`/bin/sh`, tcsh) runs it as it always did.
        else -> if (launch.isDefault) with(launch, listOf("-l", "-i", "-c", command)) else runArgv(default, command, default, windows)
    }
}
