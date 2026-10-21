package app.katty.core

import app.katty.core.sqlite.SqliteDatabase
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

private val logger = LoggerFactory.getLogger("app.katty.core.CodexStore")

// The program starts a moment before its TUI starts its thread (a second in a measurement), and the start time of the process is read from the system.
private const val BEFORE_START_MS = 1_000L
private const val AFTER_START_MS = 15_000L

private val THREAD_ID = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

/**
 * What Codex keeps of its conversations (its threads), read from the SQLite database in [directory] (`~/.codex/state_<version>.sqlite`), **read-only**.
 * A read that fails (no database, another schema, a lock that does not go) gives `null`: no reading, never a wrong one.
 *
 * The terminals share a server that holds the threads (`codex app-server`, a daemon), and nothing says which terminal's a thread is: the logs name the
 * connection that started it, not the process behind the connection. What does is the program's own command line (`codex resume <id>`), and when the
 * thread was made: the TUI starts its thread a second after it starts (`created_at_ms`, to the millisecond), and the thread of a resumed conversation is
 * updated when it is resumed. A Codex that the user started in a directory is told apart from another that started a few seconds later; two that
 * were resumed with no id on the command line (`resume --last`, the picker) are not.
 */
internal class CodexStore(private val directory: Path = defaultDirectory()) {
    /**
     * The thread that the Codex that runs [argv], in [cwd] and since [startedAtMs], is in:
     * 1. the id on its command line (`resume <id>`), when there is such a thread;
     * 2. the thread made in the seconds after the process started (a new Codex), the nearest to the start;
     * 3. the thread of the directory that was updated since (a resumed Codex that was not given an id), the newest.
     *
     * A thread is the terminal's when it is of a TUI (`originator`, when the database has one), is not an agent's own (a sub-agent's), is not
     * archived, and has a message of the user (`has_user_event` is not set by this one: the message is).
     */
    fun sessionFor(cwd: String, startedAtMs: Long, argv: List<String> = emptyList()): String? {
        val database = database() ?: return null
        return try {
            SqliteDatabase.open(database).use { db ->
                explicitId(argv)?.takeIf { id -> db.query("SELECT 1 FROM threads WHERE id = ?", id) { 1 }.isNotEmpty() }
                    ?: created(db, cwd, startedAtMs)
                    ?: updated(db, cwd, startedAtMs)
            }
        } catch (e: Exception) {
            logger.debug("Could not read {}", database, e)
            null
        }
    }

    // `codex resume <id>`, with options before it perhaps: the word after `resume`, when it is an id and not an option (`--last`).
    private fun explicitId(argv: List<String>): String? {
        val at = argv.drop(1).indexOf("resume").takeIf { it >= 0 }?.plus(2) ?: return null
        return argv.getOrNull(at)?.takeIf { THREAD_ID.matches(it) }
    }

    private fun created(db: SqliteDatabase, cwd: String, startedAtMs: Long): String? =
        ask(
            db, cwd,
            "AND created_at_ms BETWEEN ? AND ? ORDER BY ABS(created_at_ms - ?) ASC LIMIT 1",
            startedAtMs - BEFORE_START_MS, startedAtMs + AFTER_START_MS, startedAtMs,
        )

    private fun updated(db: SqliteDatabase, cwd: String, startedAtMs: Long): String? =
        ask(db, cwd, "AND updated_at_ms >= ? ORDER BY updated_at_ms DESC LIMIT 1", startedAtMs - BEFORE_START_MS)

    // The conditions on the origin and on the agent's own threads are left out when the database has no such columns (an older Codex).
    private fun ask(db: SqliteDatabase, cwd: String, tail: String, vararg parameters: Any): String? {
        val where = "cwd = ? AND archived = 0 AND first_user_message != ''"
        val conditions = listOf(" AND originator = 'codex-tui' AND agent_role IS NULL", " AND agent_role IS NULL", "")
        for (extra in conditions) {
            try {
                return db.query("SELECT id FROM threads WHERE $where$extra $tail", cwd, *parameters) { it.text(0)!! }.firstOrNull()
            } catch (e: Exception) {
                logger.debug("Codex threads, without {}", extra, e)
            }
        }
        return null
    }

    // The newest version of the file (`state_5.sqlite`): its number goes up when Codex changes the schema, and it leaves the old one where it is.
    private fun database(): Path? =
        try {
            Files.newDirectoryStream(directory, "state_*.sqlite").use { files ->
                files.maxByOrNull { Regex("state_(\\d+)\\.sqlite").matchEntire(it.fileName.toString())?.groupValues?.get(1)?.toInt() ?: -1 }
            }
        } catch (e: Exception) {
            null
        }

    internal companion object {
        fun defaultDirectory(): Path = Path.of(System.getProperty("user.home"), ".codex")
    }
}
