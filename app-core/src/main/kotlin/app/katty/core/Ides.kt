package app.katty.core

/**
 * The editors and IDEs that a project's folder can be opened in, in the order the menus list them. Each is an application of macOS, known
 * by its bundle identifier (the first of [bundleIds] that is installed is the one that opens): LaunchServices finds it wherever it is, in
 * `/Applications`, in `~/Applications` where JetBrains Toolbox puts them, or elsewhere.
 *
 * Seen on a machine: IntelliJ IDEA, Visual Studio Code, Xcode, Android Studio. The other identifiers are the vendors', not checked on an
 * installation, and [NOTEPAD_PLUS_PLUS] has none: it has no version for macOS, so it is never found there.
 */
public enum class Ide(public val displayName: String, public val bundleIds: List<String>) {
    VS_CODE("Visual Studio Code", listOf("com.microsoft.VSCode")),
    INTELLIJ_IDEA("IntelliJ IDEA", listOf("com.jetbrains.intellij")),
    INTELLIJ_IDEA_CE("IntelliJ IDEA Community", listOf("com.jetbrains.intellij.ce")),
    CLION("CLion", listOf("com.jetbrains.CLion")),
    RUSTROVER("RustRover", listOf("com.jetbrains.rustrover")),
    GOLAND("GoLand", listOf("com.jetbrains.goland")),
    PYCHARM("PyCharm", listOf("com.jetbrains.pycharm")),
    PYCHARM_CE("PyCharm Community", listOf("com.jetbrains.pycharm.ce")),
    WEBSTORM("WebStorm", listOf("com.jetbrains.WebStorm")),
    PHPSTORM("PhpStorm", listOf("com.jetbrains.PhpStorm")),
    RIDER("Rider", listOf("com.jetbrains.rider")),
    RUBYMINE("RubyMine", listOf("com.jetbrains.rubymine")),
    ANDROID_STUDIO("Android Studio", listOf("com.google.android.studio")),
    VISUAL_STUDIO("Visual Studio", listOf("com.microsoft.visual-studio")),
    ECLIPSE("Eclipse", listOf("org.eclipse.platform.ide", "org.eclipse.sdk.ide")),
    XCODE("Xcode", listOf("com.apple.dt.Xcode")),
    LIGHT_TABLE("Light Table", listOf("com.kodowa.LightTable")),
    RSTUDIO("RStudio", listOf("com.rstudio.desktop", "org.rstudio.RStudio")),
    NETBEANS("NetBeans", listOf("org.apache.netbeans", "org.netbeans.ide")),
    SUBLIME_TEXT("Sublime Text", listOf("com.sublimetext.4", "com.sublimetext.3")),
    CODE_BLOCKS("Code::Blocks", listOf("org.codeblocks.codeblocks")),
    NOTEPAD_PLUS_PLUS("Notepad++", emptyList()),
    JUPYTER("Jupyter", listOf("org.jupyter.jupyterlab-desktop")),
}

/** The IDEs that are installed, with the bundle identifier that opens each, in the order of [Ide]. */
public fun detectIdes(isInstalled: (String) -> Boolean): Map<Ide, String> = buildMap {
    for (ide in Ide.entries) {
        val bundleId = ide.bundleIds.firstOrNull(isInstalled) ?: continue
        put(ide, bundleId)
    }
}
