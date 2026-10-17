package app.katty.core

import java.nio.file.Path
import java.nio.file.Paths

/**
 * Where KaTTY keeps what it writes: the state of the application, the geometry of its window, and the directory that the usage commands of the agents run in.
 *
 * @param support The directory of the application's files. It is created when something is written in it.
 */
public class KattyDirectories(public val support: Path) {
    /** What is saved of the projects, the workspaces and the settings. */
    public val stateFile: Path get() = support.resolve("state.json")

    /** Where the window was, how big and in which mode: not in the state file, which it would make change at every move. */
    public val windowFile: Path get() = support.resolve("window.json")

    /** Where the board window was, how big and in which mode: the main window's own file is [windowFile]. */
    public val boardWindowFile: Path get() = support.resolve("board-window.json")

    /**
     * The directory that the commands of the agents that report their usage run in. `claude -p` records a session for the directory it
     * runs in, which must not be one of the user's projects.
     */
    public val usageDirectory: Path get() = support.resolve("usage")

    public companion object {
        /** The system property that changes the directory, for tests and for running a second copy of the application. */
        public const val PROPERTY: String = "katty.support"

        /**
         * `~/Library/Application Support/KaTTY` on macOS, `$XDG_DATA_HOME/KaTTY` or `~/.local/share/KaTTY` elsewhere, unless [PROPERTY]
         * says another one.
         */
        public fun default(): KattyDirectories {
            System.getProperty(PROPERTY)?.takeIf { it.isNotBlank() }?.let { return KattyDirectories(Paths.get(it)) }
            val home = Paths.get(System.getProperty("user.home"))
            val support = if (System.getProperty("os.name").startsWith("Mac")) {
                home.resolve("Library").resolve("Application Support").resolve("KaTTY")
            } else {
                (System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() }?.let { Paths.get(it) } ?: home.resolve(".local").resolve("share")).resolve("KaTTY")
            }
            return KattyDirectories(support)
        }
    }
}
