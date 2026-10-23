package app.katty.core

import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * Finds out which of the agents are installed on the machine: their programs are looked for in the `PATH` of the user's login shell. An
 * application that was started from the Finder has not got that `PATH`: it has the system's, which has none of what the user installed.
 */
public object AgentDetection {
    /**
     * The `PATH` of the user's login shell, once [loginShellPath] has read it, and `null` before: what a program run over a shell is looked for
     * in, which a call that cannot wait for a shell to start reads.
     */
    @Volatile
    public var knownLoginPath: String? = null
        private set

    /**
     * The `PATH` that a login shell has, or `null` if it could not be read in [timeoutMs]. It starts the shell once; the caller keeps what
     * it got, and so does [knownLoginPath].
     */
    public fun loginShellPath(shell: String = userShell(), timeoutMs: Long = 8_000): String? =
        // Only the user's own shell says what the user's PATH is.
        readLoginShellPath(shell, timeoutMs)?.also { if (shell == userShell()) knownLoginPath = it }

    private fun userShell(): String = System.getenv("SHELL") ?: "/bin/zsh"

    // Through the runner of the usage commands, which reads the output on its own thread: a profile that starts a program in the background
    // (ssh-agent, keychain) leaves the output open after the shell ended, and a read that waits for its end would wait for that program.
    private fun readLoginShellPath(shell: String, timeoutMs: Long, runner: CommandRunner = ProcessCommandRunner): String? {
        // Between two markers: what the login files print (a greeting, a notice) comes before and after it.
        val result = runner.run(listOf(shell, "-l", "-c", "printf '$PATH_MARK%s$PATH_MARK' \"\$PATH\""), Path.of(System.getProperty("user.home")), timeoutMs)
        return result?.takeIf { it.exitCode == 0 }?.output?.let(::betweenMarks)
    }

    /**
     * The agents whose program is an executable file in one of the folders of [path] (separated as a `PATH` is), with where it is. The folder
     * that comes first wins, as it does when the shell runs the program.
     */
    public fun detect(path: String): Map<Agent, Path> {
        val directories = path.split(File.pathSeparatorChar).filter { it.isNotEmpty() }.map { File(it).toPath() }
        val found = LinkedHashMap<Agent, Path>()
        for (agent in Agent.entries) {
            val executable = directories.map { it.resolve(agent.executable) }.firstOrNull { Files.isRegularFile(it) && Files.isExecutable(it) } ?: continue
            found[agent] = executable
        }
        return found
    }

    // What the shell printed between the two marks, `null` when they are not there or there is nothing between them.
    private fun betweenMarks(output: String): String? {
        val start = output.indexOf(PATH_MARK)
        val end = output.indexOf(PATH_MARK, start + PATH_MARK.length)
        if (start < 0 || end < 0) return null
        return output.substring(start + PATH_MARK.length, end).takeIf { it.isNotEmpty() }
    }

    // What no profile prints: the PATH is between two of them.
    private const val PATH_MARK = "__KATTY_PATH__"
}
