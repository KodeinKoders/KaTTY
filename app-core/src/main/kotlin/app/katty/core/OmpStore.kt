package app.katty.core

import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isRegularFile

private val logger = LoggerFactory.getLogger("app.katty.core.OmpStore")

// What a process that has just started has not yet written: its breadcrumb is as old as the process, or a little older (the clocks of the two).
private const val STARTED_MARGIN_MS = 2_000L

private val SESSION_FILE = Regex(".*_([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})\\.jsonl")

// The options that say which session an omp opens: when one is given, it writes the breadcrumb of what it opened, which may be the one that is already there.
private val OPENS_A_SESSION = setOf("--resume", "-r", "--continue", "-c", "--restore")

/**
 * What omp (oh-my-pi, a fork of Pi) keeps of the session that each terminal is in: a file for each terminal, `~/.omp/agent/terminal-sessions/<terminal>`
 * (the name of its tty without `/dev/`, `ttys018`), which omp writes when it makes a session, switches to one or starts another with `/new`, so that
 * `--continue` finds the terminal's last session even with several omps running. It has three or four lines:
 * ```
 * /Users/me/Code/app                       the directory
 * /Users/me/.omp/agent/sessions/…/<start time>_<id>.jsonl   the session's file
 * fresh                                     only for a session that has no file yet (it is made at the first message; `/new` is one)
 * cwdstat 16777234 184349763                the device and the inode of the directory
 * ```
 * It is the process's own word about the session it is in, kept by terminal, and it follows `/new` and `/resume`: it is what KaTTY needs to come back on
 * the conversation that was open, and not on an older one.
 *
 * A session that is `fresh` has no conversation: nothing is resumed (a `null`), and that is not "not known". A breadcrumb that omp did not write for this
 * process (a terminal's name that an older omp used) is not the process's: it is as old as the process or newer, unless the process was told which session
 * to open. An omp with its own profile (`--profile`) keeps its files elsewhere: it starts again with no conversation.
 */
internal class OmpStore(private val agentDirectory: Path = defaultAgentDirectory()) {
    /**
     * The id of the session that the omp that runs [argv] in [cwd], started at [startedAtMs] and has the terminal [tty] is in, or `null`: it has none yet, or
     * what says so is not there or is not its own.
     */
    fun sessionFor(cwd: String, startedAtMs: Long, tty: String?, argv: List<String>): String? {
        val breadcrumb = tty?.takeIf { it.isNotEmpty() && '/' !in it }?.let { agentDirectory.resolve("terminal-sessions").resolve(it) } ?: return null
        return try {
            if (!breadcrumb.isRegularFile()) return null
            val lines = Files.readAllLines(breadcrumb)
            if (lines.size < 2) return null
            // An omp writes the breadcrumb of the session it opens when it starts: one that is older than the process is another's, unless the process was told
            // which session to open and it was the one that was already there (an identical breadcrumb is not written again).
            val own = breadcrumb.getLastModifiedTime().toMillis() >= startedAtMs - STARTED_MARGIN_MS ||
                argv.drop(1).any { it in OPENS_A_SESSION || OPENS_A_SESSION.any { option -> option.startsWith("--") && it.startsWith("$option=") } }
            if (!own) return null
            if (!sameDirectory(lines[0], cwd)) return null
            if (lines.drop(2).any { it.trim() == "fresh" }) return null
            val file = Path.of(lines[1]).let { if (it.isAbsolute) it else Path.of(lines[0]).resolve(it) }
            if (!file.isRegularFile()) return null
            SESSION_FILE.matchEntire(file.fileName.toString())?.groupValues?.get(1)
        } catch (e: Exception) {
            logger.debug("Could not read {}", breadcrumb, e)
            null
        }
    }

    // Both are real paths: omp writes the directory as it was given (`/tmp/x`), and the system says `/private/tmp/x`.
    private fun sameDirectory(recorded: String, cwd: String): Boolean =
        try {
            Path.of(recorded).toRealPath() == Path.of(cwd).toRealPath()
        } catch (e: Exception) {
            recorded == cwd
        }

    internal companion object {
        fun defaultAgentDirectory(): Path = Path.of(System.getProperty("user.home"), ".omp", "agent")
    }
}
