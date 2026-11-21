package app.katty.core

/**
 * The editors and IDEs that a project's folder can be opened in, in the order the menus list them. Each is an application of macOS, known
 * by its bundle identifier (the first of [bundleIds] that is installed is the one that opens): LaunchServices finds it wherever it is, in
 * `/Applications`, in `~/Applications` where JetBrains Toolbox puts them, or elsewhere.
 *
 * Seen on a machine: IntelliJ IDEA, Visual Studio Code, Xcode, Android Studio, OpenIDEA. The other identifiers are the vendors', not checked on an
 * installation, and [NOTEPAD_PLUS_PLUS] has none: it has no version for macOS, so it is never found there.
 *
 * On Windows an IDE is known by its executable ([windowsExecutables]), which the list of installed programs of the system points to: see
 * [detectWindowsIdes]. Xcode and OpenIDEA have none.
 *
 * [FINDER] is not an IDE, and only opens what is clicked in a terminal ([forProjects] is false): a project's menu already shows its folder there.
 */
public enum class Ide(
    private val macName: String,
    public val bundleIds: List<String>,
    public val opener: FileOpener = FileOpener.PLAIN,
    /** Whether a project's folder is offered to it (the project's menu and the palette), and not only the files and folders that are clicked. */
    public val forProjects: Boolean = true,
    /**
     * The names of its executable on Windows (`idea64.exe`), which the system's list of installed programs points to (see [detectWindowsIdes]):
     * none for an IDE that has no version for Windows.
     */
    public val windowsExecutables: List<String> = emptyList(),
    /** How the list of installed programs of Windows names it, when another IDE has the same executable (`IntelliJ IDEA Community Edition`). */
    public val windowsNames: List<String> = emptyList(),
    private val windowsName: String? = null,
) {
    VS_CODE("Visual Studio Code", listOf("com.microsoft.VSCode"), FileOpener.VS_CODE, windowsExecutables = listOf("Code.exe")),
    INTELLIJ_IDEA("IntelliJ IDEA", listOf("com.jetbrains.intellij"), FileOpener.JETBRAINS, windowsExecutables = listOf("idea64.exe", "idea.exe"), windowsNames = listOf("IntelliJ IDEA")),
    INTELLIJ_IDEA_CE("IntelliJ IDEA Community", listOf("com.jetbrains.intellij.ce"), FileOpener.JETBRAINS, windowsExecutables = listOf("idea64.exe", "idea.exe"), windowsNames = listOf("IntelliJ IDEA Community")),
    OPENIDEA("OpenIDEA", listOf("ai.l0ng.openidea"), FileOpener.OPENIDEA),
    CLION("CLion", listOf("com.jetbrains.CLion"), FileOpener.JETBRAINS, windowsExecutables = listOf("clion64.exe")),
    RUSTROVER("RustRover", listOf("com.jetbrains.rustrover"), FileOpener.JETBRAINS, windowsExecutables = listOf("rustrover64.exe")),
    GOLAND("GoLand", listOf("com.jetbrains.goland"), FileOpener.JETBRAINS, windowsExecutables = listOf("goland64.exe")),
    PYCHARM("PyCharm", listOf("com.jetbrains.pycharm"), FileOpener.JETBRAINS, windowsExecutables = listOf("pycharm64.exe"), windowsNames = listOf("PyCharm")),
    PYCHARM_CE("PyCharm Community", listOf("com.jetbrains.pycharm.ce"), FileOpener.JETBRAINS, windowsExecutables = listOf("pycharm64.exe"), windowsNames = listOf("PyCharm Community")),
    WEBSTORM("WebStorm", listOf("com.jetbrains.WebStorm"), FileOpener.JETBRAINS, windowsExecutables = listOf("webstorm64.exe")),
    PHPSTORM("PhpStorm", listOf("com.jetbrains.PhpStorm"), FileOpener.JETBRAINS, windowsExecutables = listOf("phpstorm64.exe")),
    RIDER("Rider", listOf("com.jetbrains.rider"), FileOpener.JETBRAINS, windowsExecutables = listOf("rider64.exe")),
    RUBYMINE("RubyMine", listOf("com.jetbrains.rubymine"), FileOpener.JETBRAINS, windowsExecutables = listOf("rubymine64.exe")),
    ANDROID_STUDIO("Android Studio", listOf("com.google.android.studio"), FileOpener.JETBRAINS, windowsExecutables = listOf("studio64.exe")),
    VISUAL_STUDIO("Visual Studio", listOf("com.microsoft.visual-studio"), FileOpener.VISUAL_STUDIO, windowsExecutables = listOf("devenv.exe")),
    ECLIPSE("Eclipse", listOf("org.eclipse.platform.ide", "org.eclipse.sdk.ide"), windowsExecutables = listOf("eclipse.exe")),
    XCODE("Xcode", listOf("com.apple.dt.Xcode"), FileOpener.XCODE),
    LIGHT_TABLE("Light Table", listOf("com.kodowa.LightTable")),
    RSTUDIO("RStudio", listOf("com.rstudio.desktop", "org.rstudio.RStudio"), windowsExecutables = listOf("rstudio.exe")),
    NETBEANS("NetBeans", listOf("org.apache.netbeans", "org.netbeans.ide"), windowsExecutables = listOf("netbeans64.exe")),
    SUBLIME_TEXT("Sublime Text", listOf("com.sublimetext.4", "com.sublimetext.3"), FileOpener.SUBLIME, windowsExecutables = listOf("sublime_text.exe")),
    CODE_BLOCKS("Code::Blocks", listOf("org.codeblocks.codeblocks"), windowsExecutables = listOf("codeblocks.exe")),
    NOTEPAD_PLUS_PLUS("Notepad++", emptyList(), FileOpener.NOTEPAD_PLUS_PLUS, windowsExecutables = listOf("notepad++.exe")),
    JUPYTER("Jupyter", listOf("org.jupyter.jupyterlab-desktop"), windowsExecutables = listOf("JupyterLab.exe")),
    FINDER("Finder", listOf("com.apple.finder"), FileOpener.FINDER, forProjects = false, windowsName = "File Explorer"),
    ;

    /** What it is called: [FINDER] is File Explorer on Windows. */
    public val displayName: String get() = windowsName?.takeIf { isWindows } ?: macName

    public companion object {
        /** The IDE whose [name] this is, as a workspace keeps it, or `null` for one that this version does not know. */
        public fun named(name: String?): Ide? = entries.firstOrNull { it.name == name }
    }
}

/**
 * How an IDE opens a file at a line: what its own command line tool does, which is in its bundle and not necessarily on the `PATH`. The others
 * are given the file with `open -b`, which opens it at its start.
 */
public enum class FileOpener {
    /**
     * `open -n -b <id> --args --line N --column C <file>`, which is what JetBrains Toolbox's launchers do: the new process gives the file to the IDE
     * that runs. **Its column counts from 0** (`--column 5` put the caret on the sixth character of IntelliJ IDEA's line, measured on Windows), so
     * it is given the column less one.
     */
    JETBRAINS,

    /** The `code` script of the bundle, `--goto <file>:N:C`. Not the `vscode://file` address, which Visual Studio Code asks about before it opens it. */
    VS_CODE,

    /** The `subl` of the bundle, `<file>:N:C`. */
    SUBLIME,

    /** The `xed` of the bundle, `--line N <file>`. */
    XCODE,

    /** The `openidea` script of the bundle, `--line N --column C <file>`: it gives the file to the OpenIDEA that runs, and starts it if none does. */
    OPENIDEA,

    /** `open -b <id> <file>`: no line. */
    PLAIN,

    /** Windows: `notepad++.exe -nN -cC <file>`. A plain opening elsewhere (it has no version for macOS). */
    NOTEPAD_PLUS_PLUS,

    /** Windows: `devenv.exe /edit <file> /command "Edit.GoTo N"`, with no column. A plain opening elsewhere. */
    VISUAL_STUDIO,

    /**
     * A folder is opened in the Finder (`open -b com.apple.finder <folder>`), and a file is shown there, selected in its folder (`open -R`). So is
     * a folder with an extension, which is a bundle that opening could run (`Tool.app`), as the terminal's own links do.
     */
    FINDER,
}

/**
 * The command that opens the file or the [directory] at [path] (absolute: it starts with `/`, so that no tool takes it for an option) at its
 * [line] and [column] in [ide], installed as [bundleId] at [bundlePath]. A tool that is not in the bundle ([isExecutable] says), or a bundle that
 * is not known, falls back on `open -b`, which opens the file at its start. It is never given to a shell.
 */
public fun openFileCommand(
    ide: Ide,
    bundleId: String,
    bundlePath: String?,
    path: String,
    line: Int?,
    column: Int?,
    directory: Boolean = false,
    isExecutable: (String) -> Boolean = { java.io.File(it).canExecute() },
): List<String> {
    require(path.startsWith("/")) { "The path of the file is absolute, not $path" }
    val column = column?.takeIf { line != null }
    // `file:12:5`, as the tools that take the line after the file write it.
    val withLine = path + (line?.let { ":$it" } ?: "") + (column?.let { ":$it" } ?: "")
    fun tool(relative: String) = bundlePath?.let { "${it.trimEnd('/')}/$relative" }?.takeIf(isExecutable)
    val plain = listOf("open", "-b", bundleId, path)
    return when (ide.opener) {
        FileOpener.JETBRAINS -> buildList {
            addAll(listOf("open", "-n", "-b", bundleId, "--args"))
            if (line != null) addAll(listOf("--line", line.toString()))
            if (column != null) addAll(listOf("--column", jetBrainsColumn(column)))
            add(path)
        }
        FileOpener.VS_CODE -> tool("Contents/Resources/app/bin/code")?.let { if (line != null) listOf(it, "--goto", withLine) else listOf(it, path) } ?: plain
        FileOpener.SUBLIME -> tool("Contents/SharedSupport/bin/subl")?.let { listOf(it, withLine) } ?: plain
        FileOpener.XCODE -> tool("Contents/Developer/usr/bin/xed")?.let { if (line != null) listOf(it, "--line", line.toString(), path) else listOf(it, path) } ?: plain
        FileOpener.OPENIDEA -> tool("Contents/Resources/bin/openidea")?.let {
            buildList {
                add(it)
                if (line != null) addAll(listOf("--line", line.toString()))
                if (column != null) addAll(listOf("--column", column.toString()))
                add(path)
            }
        } ?: plain
        FileOpener.PLAIN, FileOpener.NOTEPAD_PLUS_PLUS, FileOpener.VISUAL_STUDIO -> plain
        FileOpener.FINDER -> if (directory && java.io.File(path).extension.isEmpty()) plain else listOf("open", "-R", path)
    }
}

/** The IDEs that are installed, with the bundle identifier that opens each, in the order of [Ide]. */
public fun detectIdes(isInstalled: (String) -> Boolean): Map<Ide, String> = buildMap {
    for (ide in Ide.entries) {
        val bundleId = ide.bundleIds.firstOrNull(isInstalled) ?: continue
        put(ide, bundleId)
    }
}

/** A program that Windows lists as installed (its uninstall key, or `App Paths`): what it is called, and the path of its executable. */
public data class InstalledProgram(val displayName: String, val executable: String)

/**
 * The IDEs that are installed on Windows, with the path of the executable that opens each, in the order of [Ide]: the first of [programs] whose
 * executable's file is one of the IDE's [Ide.windowsExecutables] (whatever the case), and whose name is the IDE's when another IDE has the same
 * executable (the longest of [Ide.windowsNames] that starts it wins: `IntelliJ IDEA Community Edition` is not IntelliJ IDEA), and that [exists];
 * else the first of its executables that [onPath] finds. File Explorer is always there.
 */
public fun detectWindowsIdes(programs: List<InstalledProgram>, onPath: (String) -> String?, exists: (String) -> Boolean): Map<Ide, String> {
    val claimed = HashMap<Ide, String>()
    for (program in programs) {
        if (!exists(program.executable)) continue
        val file = java.io.File(program.executable.replace('\\', '/')).name
        val candidates = Ide.entries.filter { ide -> ide.windowsExecutables.any { it.equals(file, ignoreCase = true) } }
        fun nameLength(ide: Ide) = ide.windowsNames.filter { program.displayName.startsWith(it, ignoreCase = true) }.maxOfOrNull { it.length }
        val ide = candidates.filter { it.windowsNames.isEmpty() || nameLength(it) != null }.maxByOrNull { nameLength(it) ?: 0 } ?: continue
        claimed.putIfAbsent(ide, program.executable)
    }
    return buildMap {
        for (ide in Ide.entries) {
            val executable = when (ide) {
                Ide.FINDER -> "explorer.exe"
                else -> claimed[ide] ?: ide.windowsExecutables.firstNotNullOfOrNull(onPath)
            } ?: continue
            put(ide, executable)
        }
    }
}

/**
 * The command that opens the file or the [directory] at [path] (absolute: it starts with a drive, so that no tool takes it for an option) at its
 * [line] and [column] in [ide], whose executable is [executable], on Windows. It is never given to a shell. File Explorer shows a file selected
 * in its folder, and opens a folder.
 */
public fun windowsOpenFileCommand(ide: Ide, executable: String, path: String, line: Int?, column: Int?, directory: Boolean = false): List<String> {
    require(java.io.File(path).isAbsolute || WINDOWS_ABSOLUTE.matches(path)) { "The path of the file is absolute, not $path" }
    val column = column?.takeIf { line != null }
    if (directory && ide.opener != FileOpener.FINDER) return listOf(executable, path)
    return when (ide.opener) {
        // `/select,` and the path apart: Java puts an argument that has a space in quotes, and File Explorer does not read `"/select,C:\a b"`.
        FileOpener.FINDER -> if (directory) listOf(executable, path) else listOf(executable, "/select,", path)
        FileOpener.JETBRAINS -> buildList {
            add(executable)
            if (line != null) addAll(listOf("--line", line.toString()))
            if (column != null) addAll(listOf("--column", jetBrainsColumn(column)))
            add(path)
        }
        FileOpener.OPENIDEA -> buildList {
            add(executable)
            if (line != null) addAll(listOf("--line", line.toString()))
            if (column != null) addAll(listOf("--column", column.toString()))
            add(path)
        }
        FileOpener.VS_CODE -> if (line != null) listOf(executable, "--goto", path + ":$line" + (column?.let { ":$it" } ?: "")) else listOf(executable, path)
        FileOpener.SUBLIME -> listOf(executable, path + (line?.let { ":$it" } ?: "") + (column?.let { ":$it" } ?: ""))
        FileOpener.NOTEPAD_PLUS_PLUS -> buildList {
            add(executable)
            if (line != null) add("-n$line")
            if (column != null) add("-c$column")
            add(path)
        }
        // Its `Edit.GoTo` runs once the file is open, whether Visual Studio ran or not (measured); it has no column.
        FileOpener.VISUAL_STUDIO -> listOf(executable, "/edit", path) + (line?.let { listOf("/command", "Edit.GoTo $it") } ?: emptyList())
        FileOpener.XCODE, FileOpener.PLAIN -> listOf(executable, path)
    }
}

// A path of Windows that starts with its drive, whatever the system that reads it (the tests run on a Mac too).
private val WINDOWS_ABSOLUTE = Regex("[A-Za-z]:[\\\\/].*")

// The column of a JetBrains IDE, which counts from 0, for [column], which counts from 1 as compilers write it.
private fun jetBrainsColumn(column: Int): String = (column - 1).coerceAtLeast(0).toString()
