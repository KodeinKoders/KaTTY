package app.katty.core

import app.katty.core.sqlite.SqliteDatabase
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

private val logger = LoggerFactory.getLogger("app.katty.core.ClineStore")

// A client registers with the hub a moment after its process started, and the process's start time is read from the system: no more than this
// before is the same program.
private const val REGISTRATION_MARGIN_MS = 5_000L

/**
 * What Cline keeps of its sessions, read from the SQLite databases in [directory] (`~/.cline/data/db`), **read-only**. A read that fails (no
 * database, another schema, a lock that does not go) gives `null`: no reading, never a wrong one.
 *
 * The sessions run in a daemon that the terminals share (`.cline --cline-hub-daemon`), so the `pid` of a row of `sessions.db` is the daemon's and
 * says nothing of the terminal. What does is the hub's own record (`hub-events-*.db`): each client registers (`hub.client.registered`, with its
 * `pid` and a `clientId`), and the events of a session name the client that created it (`session.created`), asked something of it
 * (`capability.requested`) or ran it (`run.started`). So two Clines in one directory have two clients and two sessions, told apart by the pid.
 * That pid is the native `.cline` process, a child of the `node` script that has the terminal: the pids of the whole group are asked.
 *
 * A Cline that is not in the hub (an older one) is looked for by its directory and times, as the other agents with a database are. A Cline started
 * with `--data-dir` keeps its databases elsewhere, which is not looked at: it starts a new conversation.
 */
internal class ClineStore(private val directory: Path = defaultDirectory()) {
    private val sessions: Path get() = directory.resolve("sessions.db")

    /**
     * The session that the Cline that is the processes [pids] (its group, which started at [startedAtMs]) and runs in [cwd] is in. By its client in
     * the hub when it has one: the session that the client has done anything with last, and none when it has done nothing yet (a new Cline has no
     * session until its first message, and a guess could be another Cline's). Otherwise the newest interactive session of [cwd] started or updated
     * since the process started. The session has to have its messages (the file that `--id` loads).
     */
    fun sessionFor(cwd: String, startedAtMs: Long, pids: List<Int> = emptyList()): String? {
        if (!Files.exists(sessions)) return null
        return try {
            SqliteDatabase.open(sessions).use { db ->
                val hubs = if (pids.isEmpty()) emptyList() else hubDatabases()
                val clients = hubs.mapNotNull { hub -> clientOf(hub, pids, startedAtMs)?.let { hub to it } }
                if (clients.isEmpty()) {
                    byDirectory(db, cwd, startedAtMs)
                } else {
                    val (hub, client) = clients.first()
                    lastSessionOf(hub, client.id, client.registeredAtMs)?.takeIf { hasMessages(db, it) }
                }
            }
        } catch (e: Exception) {
            logger.debug("Could not read {}", sessions, e)
            null
        }
    }

    private class Client(val id: String, val registeredAtMs: Long)

    private fun hubDatabases(): List<Path> =
        Files.newDirectoryStream(directory, "hub-events-*.db").use { it.toList() }

    // The client of the newest registration of one of [pids] since the process started.
    private fun clientOf(hub: Path, pids: List<Int>, startedAtMs: Long): Client? =
        try {
            SqliteDatabase.open(hub).use { db ->
                db.query(
                    "SELECT json_extract(envelope_json, '$.payload.clientId'), created_at FROM hub_events WHERE event = 'hub.client.registered' " +
                        "AND created_at >= ? AND json_extract(envelope_json, '$.payload.metadata.pid') IN (${pids.joinToString { "?" }}) " +
                        "ORDER BY sequence DESC LIMIT 1",
                    startedAtMs - REGISTRATION_MARGIN_MS, *pids.toTypedArray(),
                ) { Client(it.text(0)!!, it.long(1)!!) }.firstOrNull()
            }
        } catch (e: Exception) {
            logger.debug("Could not read {}", hub, e)
            null
        }

    private fun lastSessionOf(hub: Path, client: String, sinceMs: Long): String? =
        SqliteDatabase.open(hub).use { db ->
            db.query(
                "SELECT session_id FROM hub_events WHERE session_id IS NOT NULL AND created_at >= ? AND (" +
                    "json_extract(envelope_json, '$.payload.targetClientId') = ? OR json_extract(envelope_json, '$.payload.clientId') = ? " +
                    "OR json_extract(envelope_json, '$.payload.session.createdByClientId') = ?) ORDER BY sequence DESC LIMIT 1",
                sinceMs, client, client, client,
            ) { it.text(0)!! }.firstOrNull()
        }

    private fun hasMessages(db: SqliteDatabase, id: String): Boolean =
        db.query("SELECT messages_path FROM sessions WHERE session_id = ?", id) { it.text(0) }.firstOrNull()
            ?.let { Files.isRegularFile(Path.of(it)) } == true

    // SQLite reads Cline's ISO text (`2026-10-21T14:13:09.755Z`), in seconds.
    private fun byDirectory(db: SqliteDatabase, cwd: String, startedAtMs: Long): String? {
        val since = epochSeconds(startedAtMs)
        return db.query(
            "SELECT session_id, messages_path FROM sessions WHERE cwd = ? AND source = 'cli' AND interactive = 1 AND is_subagent = 0 " +
                "AND (CAST(strftime('%s', started_at) AS INTEGER) >= ? OR CAST(strftime('%s', updated_at) AS INTEGER) >= ?) " +
                "ORDER BY updated_at DESC LIMIT 5",
            cwd, since, since,
        ) { it.text(0)!! to it.text(1) }.firstOrNull { (_, messages) -> messages != null && Files.isRegularFile(Path.of(messages)) }?.first
    }

    internal companion object {
        fun defaultDirectory(): Path = Path.of(System.getProperty("user.home"), ".cline", "data", "db")
    }
}
