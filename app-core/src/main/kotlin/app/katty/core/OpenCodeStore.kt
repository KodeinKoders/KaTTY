package app.katty.core

import app.katty.core.sqlite.SqliteDatabase
import app.katty.core.sqlite.SqliteException
import app.katty.core.sqlite.SqliteRow
import java.nio.file.Path

// A session id that did not fit a record's id: `ses_` and 16 digits of its SHA-256.
private val HASHED_ID = Regex("ses_([0-9a-f]{16})")

/**
 * What OpenCode keeps of its sessions, read from its SQLite database (`~/.local/share/opencode/opencode.db`), **read-only**. OpenCode 1.x
 * has `session` and `message`, 2.x `session_v2` and `session_message`: both are read, and a table that is not there counts as empty. A read
 * that fails (no database, a schema that is another one, a lock that does not go) gives `null`: no reading, never a wrong one.
 *
 * Every query is by id or by an indexed column of a session: the database is big (1.7 GB here), and `message` is not scanned.
 */
internal class OpenCodeStore(
    private val database: Path = defaultDatabase(),
    private val limits: ModelLimits = ModelLimits(),
) {
    private class Layout(
        val sessions: String,
        val messages: String,
        // The condition that picks the answers of the assistant, and the paths in `data` of what is read from one.
        val assistant: String,
        val order: String,
        val input: String,
        val output: String,
        val reasoning: String,
        val cacheRead: String,
        val cacheWrite: String,
        val provider: String,
        val model: String,
        val cost: String,
    )

    private val v1 = Layout(
        sessions = "session", messages = "message",
        assistant = "json_extract(data, '$.role') = 'assistant'", order = "time_created DESC",
        input = "$.tokens.input", output = "$.tokens.output", reasoning = "$.tokens.reasoning",
        cacheRead = "$.tokens.cache.read", cacheWrite = "$.tokens.cache.write",
        provider = "$.providerID", model = "$.modelID", cost = "$.cost",
    )

    private val v2 = Layout(
        sessions = "session_v2", messages = "session_message",
        assistant = "type = 'assistant'", order = "seq DESC",
        input = "$.tokens.input", output = "$.tokens.output", reasoning = "$.tokens.reasoning",
        cacheRead = "$.tokens.cache.read", cacheWrite = "$.tokens.cache.write",
        provider = "$.model.providerID", model = "$.model.id", cost = "$.cost",
    )

    private val layouts = listOf(v1, v2)

    /**
     * The session that the OpenCode that runs in [cwd] and started at [startedAtMs] is in: the newest one (by its last update) in that
     * directory, created or updated since the process started. A session that another one started (a sub-agent's) is not the terminal's.
     * The caller knows whether another OpenCode runs in the same directory, in which case this cannot say which one is whose.
     */
    fun sessionFor(cwd: String, startedAtMs: Long): String? = read {
        layouts.mapNotNull { layout ->
            tolerantQuery(
                "SELECT id, time_updated FROM ${layout.sessions} " +
                    "WHERE directory = ? AND parent_id IS NULL AND (time_created >= ? OR time_updated >= ?) " +
                    "ORDER BY time_updated DESC LIMIT 1",
                cwd, startedAtMs, startedAtMs,
            ) { it.text(0)!! to it.long(1)!! }.firstOrNull()
        }.maxByOrNull { it.second }?.first
    }

    /**
     * The session that a program status record names (what OpenCode reports for a session that works or waits, as the record's id): [reported] is
     * the id of a session, or `ses_` and 16 hexadecimal digits of the SHA-256 of an id that a record cannot hold (over 32 bytes, or with other
     * characters). A session that another one started (a sub-agent's) is the session it came from: the program reports the roots, and the id of
     * a child cannot be resumed. `null` when there is no such session.
     */
    fun rootOf(reported: String): String? = read {
        val id = layouts.firstNotNullOfOrNull { layout ->
            tolerantQuery("SELECT id FROM ${layout.sessions} WHERE id = ?", reported) { it.text(0) }.firstOrNull()
                ?: hashed(reported)?.let { digest ->
                    tolerantQuery("SELECT id FROM ${layout.sessions} WHERE length(id) > 32 OR id GLOB '*[^A-Za-z0-9_.+-]*'") { it.text(0)!! }
                        .firstOrNull { sha256(it).startsWith(digest) }
                }
        } ?: return@read null
        var current = id
        repeat(MAX_DEPTH) {
            val parent = layouts.firstNotNullOfOrNull { layout ->
                tolerantQuery("SELECT parent_id FROM ${layout.sessions} WHERE id = ?", current) { it.text(0) }.firstOrNull()
            } ?: return@read current
            current = parent
        }
        current
    }

    /** When session [id] was last updated, in milliseconds since the epoch, or `null` when there is no such session. */
    fun updatedAt(id: String): Long? = read {
        layouts.firstNotNullOfOrNull { layout ->
            tolerantQuery("SELECT time_updated FROM ${layout.sessions} WHERE id = ?", id) { it.long(0) }.firstOrNull()
        }
    }

    /** What session [id] uses: the cost of it and of the sessions it started, and the context its last answer filled. `null` for no such session. */
    fun usage(id: String): SessionUsage? = read {
        val layout = layouts.firstOrNull { tolerantQuery("SELECT 1 FROM ${it.sessions} WHERE id = ?", id) { 1 }.isNotEmpty() }
            ?: return@read null
        val cost = tolerantQuery(
            "WITH RECURSIVE tree(id) AS (SELECT id FROM ${layout.sessions} WHERE id = ? " +
                "UNION SELECT s.id FROM ${layout.sessions} s JOIN tree t ON s.parent_id = t.id) " +
                "SELECT SUM(cost) FROM ${layout.sessions} WHERE id IN (SELECT id FROM tree)",
            id,
        ) { it.double(0) }.firstOrNull()
        SessionUsage(contextPercent(layout, id), cost)
    }

    /** What the assistant's answers created since [sinceMs] cost, in all the sessions of both versions, or `null` when it cannot be read. */
    fun spend(sinceMs: Long): Double? = read {
        layouts.sumOf { layout ->
            // A session that was not updated since cannot have a newer answer, and it is how the messages of 1.x, which have no index on their
            // time, are reached by their session.
            tolerantQuery(
                "SELECT SUM(json_extract(data, '${layout.cost}')) FROM ${layout.messages} " +
                    "WHERE session_id IN (SELECT id FROM ${layout.sessions} WHERE time_updated >= ?) " +
                    "AND time_created >= ? AND ${layout.assistant}",
                sinceMs, sinceMs,
            ) { it.double(0) }.firstOrNull() ?: 0.0
        }
    }

    private fun SqliteDatabase.contextPercent(layout: Layout, id: String): Int? {
        val last = tolerantQuery(
            "SELECT json_extract(data, '${layout.input}'), json_extract(data, '${layout.output}'), json_extract(data, '${layout.reasoning}'), " +
                "json_extract(data, '${layout.cacheRead}'), json_extract(data, '${layout.cacheWrite}'), " +
                "json_extract(data, '${layout.provider}'), json_extract(data, '${layout.model}') " +
                "FROM ${layout.messages} WHERE session_id = ? AND ${layout.assistant} " +
                "AND json_extract(data, '${layout.output}') > 0 ORDER BY ${layout.order} LIMIT 1",
            id,
        ) { row: SqliteRow ->
            val tokens = (0..4).sumOf { row.long(it) ?: 0L }
            val provider = row.text(5)
            val model = row.text(6)
            if (provider == null || model == null) null else Triple(tokens, provider, model)
        }.firstOrNull() ?: return null
        val limit = limits.contextOf(last.second, last.third) ?: return null
        return (last.first * 100.0 / limit).toInt().coerceIn(0, 100)
    }

    // A table that the other version of OpenCode has not made is not an error.
    private fun <T> SqliteDatabase.tolerantQuery(sql: String, vararg parameters: Any, map: (SqliteRow) -> T): List<T> = try {
        query(sql, *parameters, map = map)
    } catch (e: SqliteException) {
        if (e.message?.contains("no such table") == true) emptyList() else throw e
    }

    private fun <T> read(block: SqliteDatabase.() -> T?): T? = SqliteDatabase.read(database) { it.block() }

    private fun hashed(reported: String): String? = HASHED_ID.matchEntire(reported)?.groupValues?.get(1)

    private fun sha256(id: String): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(id.toByteArray()).joinToString("") { "%02x".format(it) }

    internal companion object {
        // Sessions that started sessions that started sessions: more than this is not a chain.
        private const val MAX_DEPTH = 10

        fun defaultDatabase(): Path =
            Path.of(System.getProperty("user.home"), ".local", "share", "opencode", "opencode.db")

        /**
         * Kilo Code's: its CLI is OpenCode's, and its database (`~/.local/share/kilo/kilo.db`) and its list of models have the same shape. The database
         * that the process has open is where a session is looked for ([KiloStore]); what its agent spent altogether is in this one.
         */
        fun kilo(): OpenCodeStore = OpenCodeStore(KiloStore.defaultDatabase(), ModelLimits(ModelLimits.kiloFile()))
    }
}
