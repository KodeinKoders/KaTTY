package app.katty.terminal

/**
 * A command line that the session types in the shell, as the user would, when the shell first waits for one: how a program that ran when
 * the application quit is started again, over the shell, so that leaving it leaves the shell and not the terminal.
 *
 * @property argv The program and its arguments, which are quoted for the shell.
 * @property run Whether Return follows: the program runs. Without it the line waits for the user, which is what a command that should not run
 * by itself (a deployment, a deletion) gets.
 */
public class TypedCommand(public val argv: List<String>, public val run: Boolean) {
    init {
        require(argv.isNotEmpty()) { "A command has a program" }
    }

    override fun toString(): String = "TypedCommand(argv=$argv, run=$run)"
}

// What needs no quoting in any shell.
private val BARE = Regex("[A-Za-z0-9_@%+=:,./-]+")

/**
 * [argv] as one line for the shell [shell] (its name: `zsh`, `bash`, `fish`), or `null` when it holds a control character: it would be taken for
 * a key of the line editor, and what is typed here must only be text. A word that is not made of safe characters is in single quotes, the one
 * quoting that every shell reads the same, with the single quote and, for fish, the backslash escaped.
 */
internal fun shellLine(argv: List<String>, shell: String): String? {
    if (argv.any { word -> word.any { it.isISOControl() } }) return null
    val fish = shell == "fish"
    return argv.joinToString(" ") { word ->
        when {
            word.isEmpty() -> "''"
            BARE.matches(word) && !word.startsWith("~") -> word
            fish -> "'" + word.replace("\\", "\\\\").replace("'", "\\'") + "'"
            else -> "'" + word.replace("'", "'\\''") + "'"
        }
    }
}

/** The bytes to type for [this] in [shell], or `null` when the line cannot be typed safely. */
internal fun TypedCommand.toInput(shell: String): ByteArray? =
    shellLine(argv, shell)?.let { (if (run) "$it\r" else it).encodeToByteArray() }
