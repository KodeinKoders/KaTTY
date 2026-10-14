package app.katty.terminal

import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

private val logger = LoggerFactory.getLogger(ShellIntegration::class.java)

/**
 * Makes the shell that a session starts tell the terminal where its prompts are (OSC 133) and which directory it is in
 * (OSC 7), which a shell does not do on its own, except for the ones of the system's terminal: that is what lets the host know
 * that a command ended, where a new terminal should start, and where the prompts are to jump between.
 *
 * It is done as Ghostty does it, by starting the shell so that it reads KaTTY's script first, which then lets it read the
 * user's own files as usual: nothing of the user's is replaced or edited.
 *
 * - zsh: `ZDOTDIR` points to a directory with a `.zshenv` that puts it back, reads the user's `.zshenv`, and loads the script.
 * - bash: it is started with `--rcfile`, which an interactive shell reads instead of `~/.bashrc`. The script reads the files that
 *   bash would have read, those of a login shell if one was asked for, and then installs its hooks. (`--posix` with `ENV`, which
 *   other terminals use, is not read by the bash 3.2 of macOS.)
 *
 * Other shells, and a shell that is started with other arguments than a login or an interactive one (a `-c` command, a
 * script), are left as they are.
 */
internal object ShellIntegration {
    private const val RESOURCES = "/app/katty/terminal/shell-integration"
    private val FILES = listOf("zsh/.zshenv", "zsh/katty-integration.zsh", "bash/katty.bash")

    // Written once for the process, and removed when it ends.
    private val directory: Path? by lazy {
        try {
            Files.createTempDirectory("katty-shell-integration").also { root ->
                for (name in FILES) {
                    val target = root.resolve(name)
                    Files.createDirectories(target.parent)
                    val source = ShellIntegration::class.java.getResourceAsStream("$RESOURCES/$name")
                        ?: error("The resource $name is missing")
                    source.use { Files.copy(it, target) }
                }
                Runtime.getRuntime().addShutdownHook(Thread { root.toFile().deleteRecursively() })
            }
        } catch (e: Exception) {
            logger.warn("Could not prepare the shell integration: the shell will not report its prompts", e)
            null
        }
    }

    /**
     * The command to run instead of [command], with what it needs added to [environment]: [command] itself and [environment]
     * untouched when the shell is not one that is known, or is not started to be typed in.
     */
    fun apply(command: List<String>, environment: MutableMap<String, String>): List<String> {
        val program = command.firstOrNull() ?: return command
        val arguments = command.drop(1)
        // A login shell, which is what a terminal starts: anything else is a command or a script that must run as it is.
        if (!arguments.all { it == "-l" || it == "--login" || it == "-i" }) return command
        val shell = File(program).name.removePrefix("-")
        return when (shell) {
            "zsh" -> {
                val root = directory ?: return command
                // What KaTTY, running in a terminal of its own, may have inherited is not the user's.
                environment.remove("KATTY_ZDOTDIR")
                environment["ZDOTDIR"]?.let { environment["KATTY_ZDOTDIR"] = it }
                environment["ZDOTDIR"] = root.resolve("zsh").toString()
                command
            }

            "bash" -> {
                val root = directory ?: return command
                environment["KATTY_BASH_INJECTED"] = "1"
                // It is not a login shell any more: the script reads the files of one.
                if (arguments.any { it == "-l" || it == "--login" }) environment["KATTY_BASH_LOGIN"] = "1" else environment.remove("KATTY_BASH_LOGIN")
                listOf(program, "--rcfile", root.resolve("bash/katty.bash").toString(), "-i")
            }

            else -> command
        }
    }
}
