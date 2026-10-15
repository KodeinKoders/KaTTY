package app.katty.core

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Finds out which of the agents are installed on the machine: their programs are looked for in the `PATH` of the user's login shell. An
 * application that was started from the Finder has not got that `PATH`: it has the system's, which has none of what the user installed.
 */
public object AgentDetection {
    /**
     * The `PATH` that a login shell has, or `null` if it could not be read in [timeoutMs]. It starts the shell once; the caller keeps what
     * it got.
     */
    public fun loginShellPath(shell: String = System.getenv("SHELL") ?: "/bin/zsh", timeoutMs: Long = 8_000): String? =
        try {
            val process = ProcessBuilder(shell, "-l", "-c", "printf %s \"\$PATH\"")
                .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
            val output = process.inputStream.readBytes().decodeToString()
            if (process.waitFor(timeoutMs, TimeUnit.MILLISECONDS) && process.exitValue() == 0) output.trim().takeIf { it.isNotEmpty() } else null
        } catch (e: Exception) {
            null
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
}
