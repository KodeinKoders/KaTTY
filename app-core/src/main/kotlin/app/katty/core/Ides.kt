package app.katty.core

/**
 * The editors and IDEs that a project's folder can be opened in, in the order the menus list them. Each is an application of macOS, known
 * by its bundle identifier (the first of [bundleIds] that is installed is the one that opens): LaunchServices finds it wherever it is, in
 * `/Applications`, in `~/Applications` where JetBrains Toolbox puts them, or elsewhere.
 *
 * Seen on a machine: IntelliJ IDEA, Visual Studio Code, Xcode, Android Studio. The other identifiers are the vendors', not checked on an
 * installation, and [NOTEPAD_PLUS_PLUS] has none: it has no version for macOS, so it is never found there.
 *
 * [FINDER] is not an IDE, and only opens what is clicked in a terminal ([forProjects] is false): a project's menu already shows its folder there.
 */
public enum class Ide(
    public val displayName: String,
    public val bundleIds: List<String>,
    public val opener: FileOpener = FileOpener.PLAIN,
    /** Whether a project's folder is offered to it (the project's menu and the palette), and not only the files and folders that are clicked. */
    public val forProjects: Boolean = true,
) {
    VS_CODE("Visual Studio Code", listOf("com.microsoft.VSCode"), FileOpener.VS_CODE),
    INTELLIJ_IDEA("IntelliJ IDEA", listOf("com.jetbrains.intellij"), FileOpener.JETBRAINS),
    INTELLIJ_IDEA_CE("IntelliJ IDEA Community", listOf("com.jetbrains.intellij.ce"), FileOpener.JETBRAINS),
    CLION("CLion", listOf("com.jetbrains.CLion"), FileOpener.JETBRAINS),
    RUSTROVER("RustRover", listOf("com.jetbrains.rustrover"), FileOpener.JETBRAINS),
    GOLAND("GoLand", listOf("com.jetbrains.goland"), FileOpener.JETBRAINS),
    PYCHARM("PyCharm", listOf("com.jetbrains.pycharm"), FileOpener.JETBRAINS),
    PYCHARM_CE("PyCharm Community", listOf("com.jetbrains.pycharm.ce"), FileOpener.JETBRAINS),
    WEBSTORM("WebStorm", listOf("com.jetbrains.WebStorm"), FileOpener.JETBRAINS),
    PHPSTORM("PhpStorm", listOf("com.jetbrains.PhpStorm"), FileOpener.JETBRAINS),
    RIDER("Rider", listOf("com.jetbrains.rider"), FileOpener.JETBRAINS),
    RUBYMINE("RubyMine", listOf("com.jetbrains.rubymine"), FileOpener.JETBRAINS),
    ANDROID_STUDIO("Android Studio", listOf("com.google.android.studio"), FileOpener.JETBRAINS),
    VISUAL_STUDIO("Visual Studio", listOf("com.microsoft.visual-studio")),
    ECLIPSE("Eclipse", listOf("org.eclipse.platform.ide", "org.eclipse.sdk.ide")),
    XCODE("Xcode", listOf("com.apple.dt.Xcode"), FileOpener.XCODE),
    LIGHT_TABLE("Light Table", listOf("com.kodowa.LightTable")),
    RSTUDIO("RStudio", listOf("com.rstudio.desktop", "org.rstudio.RStudio")),
    NETBEANS("NetBeans", listOf("org.apache.netbeans", "org.netbeans.ide")),
    SUBLIME_TEXT("Sublime Text", listOf("com.sublimetext.4", "com.sublimetext.3"), FileOpener.SUBLIME),
    CODE_BLOCKS("Code::Blocks", listOf("org.codeblocks.codeblocks")),
    NOTEPAD_PLUS_PLUS("Notepad++", emptyList()),
    JUPYTER("Jupyter", listOf("org.jupyter.jupyterlab-desktop")),
    FINDER("Finder", listOf("com.apple.finder"), FileOpener.FINDER, forProjects = false),
    ;

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
    /** `open -n -b <id> --args --line N --column C <file>`, which is what JetBrains Toolbox's launchers do: the new process gives the file to the IDE that runs. */
    JETBRAINS,

    /** The `code` script of the bundle, `--goto <file>:N:C`. Not the `vscode://file` address, which Visual Studio Code asks about before it opens it. */
    VS_CODE,

    /** The `subl` of the bundle, `<file>:N:C`. */
    SUBLIME,

    /** The `xed` of the bundle, `--line N <file>`. */
    XCODE,

    /** `open -b <id> <file>`: no line. */
    PLAIN,

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
            if (column != null) addAll(listOf("--column", column.toString()))
            add(path)
        }
        FileOpener.VS_CODE -> tool("Contents/Resources/app/bin/code")?.let { if (line != null) listOf(it, "--goto", withLine) else listOf(it, path) } ?: plain
        FileOpener.SUBLIME -> tool("Contents/SharedSupport/bin/subl")?.let { listOf(it, withLine) } ?: plain
        FileOpener.XCODE -> tool("Contents/Developer/usr/bin/xed")?.let { if (line != null) listOf(it, "--line", line.toString(), path) else listOf(it, path) } ?: plain
        FileOpener.PLAIN -> plain
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
