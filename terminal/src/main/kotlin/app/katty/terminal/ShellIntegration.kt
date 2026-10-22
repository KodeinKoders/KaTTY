package app.katty.terminal

import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

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
 * - fish: KaTTY's directory is put in front of `XDG_DATA_DIRS`, and fish reads the `vendor_conf.d` of the directories in it before
 *   the user's configuration. The script takes the directory out again, and sets itself up at the first prompt.
 *
 * Other shells, and a shell that is started with other arguments than a login or an interactive one (a `-c` command, a
 * script), are left as they are.
 */
internal object ShellIntegration {
    private const val RESOURCES = "/app/katty/terminal/shell-integration"
    private val FILES = listOf("zsh/.zshenv", "zsh/katty-integration.zsh", "bash/katty.bash", "fish/vendor_conf.d/katty-shell-integration.fish")

    // The scripts as this build has them, read once from the resources.
    private val scripts: Map<String, ByteArray> by lazy {
        FILES.associateWith { name ->
            ShellIntegration::class.java.getResourceAsStream("$RESOURCES/$name")?.use { it.readBytes() } ?: error("The resource $name is missing")
        }
    }

    // Where the scripts are when the host names no directory: a temporary one, made once for the process and removed when it ends.
    private val temporary: Path? by lazy {
        try {
            Files.createTempDirectory("katty-shell-integration").also { root ->
                Runtime.getRuntime().addShutdownHook(Thread { root.toFile().deleteRecursively() })
            }
        } catch (e: Exception) {
            logger.warn("Could not make a directory for the shell integration: the shell will not report its prompts", e)
            null
        }
    }

    /**
     * Makes [root] hold the scripts as this build has them, and writes the ones that are missing or differ: the directory may be an older
     * KaTTY's, or one that the system cleaned (macOS removes what is not used in its temporary directory after a few days, and zsh pointed to a
     * directory that is gone reads none of the user's files). Whether they are there. Several applications may share the directory: each file
     * is written to a file of its own and moved in its place.
     */
    @Synchronized
    fun prepare(root: Path): Boolean = try {
        for ((name, bytes) in scripts) {
            val target = root.resolve(name)
            if (Files.isRegularFile(target) && Files.readAllBytes(target).contentEquals(bytes)) continue
            Files.createDirectories(target.parent)
            val written = Files.createTempFile(target.parent, ".katty-", ".tmp")
            try {
                Files.write(written, bytes)
                Files.move(written, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } finally {
                Files.deleteIfExists(written)
            }
        }
        true
    } catch (e: Exception) {
        logger.warn("Could not write the shell integration in {}: the shell will not report its prompts", root, e)
        false
    }

    // The directory of the scripts, [directory] or the temporary one, once they are there; null when they cannot be.
    private fun root(directory: Path?): Path? = (directory ?: temporary)?.takeIf(::prepare)

    /** The name of the shell that [command] starts as a login or interactive shell, or `null` for any other command. */
    fun shellOf(command: List<String>): String? {
        val program = command.firstOrNull() ?: return null
        if (!command.drop(1).all { it == "-l" || it == "--login" || it == "-i" }) return null
        return File(program).name.removePrefix("-")
    }

    /**
     * The command to run instead of [command], with what it needs added to [environment]: [command] itself and [environment]
     * untouched when the shell is not one that is known, or is not started to be typed in.
     *
     * @param directory Where the scripts are kept (see [prepare]): a temporary directory when it is `null`.
     */
    fun apply(command: List<String>, environment: MutableMap<String, String>, directory: Path? = null): List<String> {
        val program = command.firstOrNull() ?: return command
        val arguments = command.drop(1)
        // A login shell, which is what a terminal starts: anything else is a command or a script that must run as it is.
        if (!arguments.all { it == "-l" || it == "--login" || it == "-i" }) return command
        val shell = File(program).name.removePrefix("-")
        return when (shell) {
            "zsh" -> {
                val root = root(directory) ?: return command
                // What KaTTY, running in a terminal of its own, may have inherited is not the user's.
                environment.remove("KATTY_ZDOTDIR")
                environment["ZDOTDIR"]?.let { environment["KATTY_ZDOTDIR"] = it }
                environment["ZDOTDIR"] = root.resolve("zsh").toString()
                command
            }

            "bash" -> {
                val root = root(directory) ?: return command
                environment["KATTY_BASH_INJECTED"] = "1"
                // It is not a login shell any more: the script reads the files of one.
                if (arguments.any { it == "-l" || it == "--login" }) environment["KATTY_BASH_LOGIN"] = "1" else environment.remove("KATTY_BASH_LOGIN")
                listOf(program, "--rcfile", root.resolve("bash/katty.bash").toString(), "-i")
            }

            "fish" -> {
                val root = root(directory) ?: return command
                // What KaTTY, running in a terminal of its own, may have inherited is not the user's: the script takes its own out.
                environment["KATTY_SHELL_INTEGRATION_XDG_DIR"] = root.toString()
                // The directories that fish and the system use when it is not set.
                val existing = environment["XDG_DATA_DIRS"]?.takeIf { it.isNotEmpty() } ?: "/usr/local/share:/usr/share"
                environment["XDG_DATA_DIRS"] = "$root:$existing"
                command
            }

            else -> command
        }
    }
}
