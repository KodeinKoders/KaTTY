package app.katty.core

import org.slf4j.LoggerFactory
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

private val logger = LoggerFactory.getLogger("app.katty.core.KiloStore")

// How much of a log is read at once. A log is read forward from where it was read last: the line that names the session is written when the
// session is made, and the log goes on after it for as long as the program runs.
private const val LOG_CHUNK_BYTES = 256 * 1024

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
        forgetLogsThatAreGone()
        val logged = openPaths.filter { path -> LOG.matchEntire(path)?.groupValues?.get(1) == dataDirectory }
            .mapNotNull(::lastSession)
            .lastOrNull()
        return logged?.let(store::rootOf) ?: store.sessionFor(cwd, startedAtMs)
    }

    // The last session that the log names, which is the one that was made last: the newest of a log that has one for each tab or `/new`. The log
    // is read from where it was read the last time, whole lines at a time, and what it named is kept with that place.
    private fun lastSession(path: String): String? =
        try {
            RandomAccessFile(path, "r").use { file ->
                val length = file.length()
                // A log that is shorter than what was read is another (or was emptied): it is read from its start.
                val known = logs[path]?.takeIf { it.offset <= length }
                var offset = known?.offset ?: 0L
                var session = known?.session
                val buffer = ByteArray(LOG_CHUNK_BYTES)
                while (offset < length) {
                    file.seek(offset)
                    val read = file.read(buffer, 0, minOf(LOG_CHUNK_BYTES.toLong(), length - offset).toInt())
                    if (read <= 0) break
                    val end = (read - 1 downTo 0).firstOrNull { buffer[it] == '\n'.code.toByte() }
                    if (end == null) {
                        // A line longer than what is read at once names no session; the end of one that is still being written is read next time.
                        if (read == LOG_CHUNK_BYTES) offset += read else break
                        continue
                    }
                    SESSION.findAll(String(buffer, 0, end + 1, Charsets.UTF_8)).lastOrNull()?.let { session = it.groupValues[1] }
                    offset += end + 1
                }
                logs[path] = LogRead(offset, session)
                session
            }
        } catch (e: Exception) {
            logger.debug("Could not read {}", path, e)
            null
        }

    // Where a log was read up to, and the session it had named by then.
    private data class LogRead(val offset: Long, val session: String?)

    internal companion object {
        // By the path of the log, which is named after the time its program started: one for each Kilo, for the whole application. The ones
        // whose file is gone are let go.
        private val logs = ConcurrentHashMap<String, LogRead>()

        private fun forgetLogsThatAreGone() {
            logs.keys.removeIf { !Files.exists(Path.of(it)) }
        }

        fun defaultDatabase(): Path = Path.of(System.getProperty("user.home"), ".local", "share", "kilo", "kilo.db")
    }
}
