package app.katty.core

import app.katty.core.sqlite.SqliteDatabase
import app.katty.core.sqlite.SqliteException
import java.nio.file.Path

/**
 * What Goose keeps of its sessions, read from its SQLite database (`~/.local/share/goose/sessions/sessions.db`), **read-only**. Its
 * timestamps are text in UTC (`2026-10-21 13:39:42`). A read that fails (no database, another schema, a lock that does not go) gives `null`:
 * no reading, never a wrong one.
 *
 * A Goose started with another root (`GOOSE_PATH_ROOT`) keeps its database elsewhere, which is not looked at: it starts a new conversation.
 */
internal class GooseStore(private val database: Path = defaultDatabase()) {
    /**
     * The session that the Goose that runs in [cwd] and started at [startedAtMs] is in: the newest one (by its last update) in that directory,
     * created or updated since the process started, that has a message (a session that was opened and left has nothing to resume). A session
     * that another one started, and an archived one, are not the terminal's. The caller knows whether another Goose runs in the same
     * directory, in which case this cannot say which one is whose.
     *
     * The conditions on `parent_session_id` and `archived_at` are left out when the database has no such columns (an older Goose).
     */
    fun sessionFor(cwd: String, startedAtMs: Long): String? {
        // Goose's timestamps are in seconds, as text.
        val since = epochSeconds(startedAtMs)
        return SqliteDatabase.read(database) { db ->
            fun ask(extra: String) = db.query(
                "SELECT id FROM sessions WHERE working_dir = ? AND session_type = 'user'$extra " +
                    "AND EXISTS (SELECT 1 FROM messages WHERE messages.session_id = sessions.id) " +
                    "AND (CAST(strftime('%s', created_at) AS INTEGER) >= ? OR CAST(strftime('%s', updated_at) AS INTEGER) >= ?) " +
                    "ORDER BY updated_at DESC LIMIT 1",
                cwd, since, since,
            ) { it.text(0)!! }.firstOrNull()
            try {
                ask(" AND parent_session_id IS NULL AND archived_at IS NULL")
            } catch (e: SqliteException) {
                // An older Goose, whose table has neither column: asked again without them. Another failure (the database is busy) is
                // not a reason to take the sessions of the sub-agents and the archived ones.
                if (e.message?.contains("no such column") == true) ask("") else throw e
            }
        }
    }

    internal companion object {
        fun defaultDatabase(): Path = Path.of(System.getProperty("user.home"), ".local", "share", "goose", "sessions", "sessions.db")
    }
}
