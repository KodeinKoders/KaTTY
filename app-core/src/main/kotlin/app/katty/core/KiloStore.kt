package app.katty.core

import org.slf4j.LoggerFactory
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path

private val logger = LoggerFactory.getLogger("app.katty.core.KiloStore")

// What of the end of a log is read: the line that names the session is written when the session is made, and the log goes on after it.
private const val LOG_TAIL_BYTES = 256 * 1024L

private val DATABASE = Regex("(.*)/kilo(?:-[^/]+)?\\.db")
private val LOG = Regex("(.*)/log/\\d{4}-\\d{2}-\\d{2}T\\d{6}\\.log")
private val SESSION = Regex("sessionId=(ses_[A-Za-z0-9]+)")

/**
 * What Kilo Code keeps of its sessions. Its CLI is OpenCode's, with its own folder (`~/.local/share/kilo`) and its own database (`kilo.db`,
 * `kilo-<channel>.db` for the other release channels), whose sessions are OpenCode's 1.x: they are read by [OpenCodeStore], **read-only**.
 *
 * The process that runs has its database open and writes to a log of its own (`log/<start time>.log`), where the files that it has open say
 * which: that is where the data is looked for, and not in a place that is guessed. The log names a session when one is made
 * (`service=kilo-sessions sessionId=ses_…`, which it logs while it syncs sessions with the cloud), so two Kilos have each their own, whichever
 * directory they are in. When the log says nothing (a Kilo that has made no session, or logs another way once logged in to the cloud), the session
 * is the newest of the directory since the process started, as for the programs that have nothing of the kind.
 */
internal class KiloStore {
    /**
     * The session of the Kilo that runs in [cwd], started at [startedAtMs], and has the files [openPaths] open: the one that its own log names last,
     * taken to its root (a session that another one started, a sub-agent's, cannot be resumed), else the newest of [cwd] that it created or updated.
     */
    fun sessionFor(cwd: String, startedAtMs: Long, openPaths: List<String>): String? {
        val database = openPaths.firstOrNull { DATABASE.matches(it) }?.let(Path::of) ?: defaultDatabase()
        val store = OpenCodeStore(database)
        val dataDirectory = database.parent?.toString()
        val logged = openPaths.filter { path -> LOG.matchEntire(path)?.groupValues?.get(1) == dataDirectory }
            .mapNotNull(::lastSession)
            .lastOrNull()
        return logged?.let(store::rootOf) ?: store.sessionFor(cwd, startedAtMs)
    }

    // The last session that the log names, which is the one that was made last: the newest of a log that has one for each tab or `/new`.
    private fun lastSession(path: String): String? =
        try {
            RandomAccessFile(path, "r").use { file ->
                val from = (file.length() - LOG_TAIL_BYTES).coerceAtLeast(0)
                file.seek(from)
                val bytes = ByteArray((file.length() - from).toInt()).also { file.readFully(it) }
                SESSION.findAll(String(bytes, Charsets.UTF_8)).lastOrNull()?.groupValues?.get(1)
            }
        } catch (e: Exception) {
            logger.debug("Could not read {}", path, e)
            null
        }

    internal companion object {
        fun defaultDatabase(): Path = Path.of(System.getProperty("user.home"), ".local", "share", "kilo", "kilo.db")
    }
}
