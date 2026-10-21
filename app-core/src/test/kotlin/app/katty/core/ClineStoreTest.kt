package app.katty.core

import app.katty.core.sqlite.SqliteDatabase
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ClineStoreTest {
    private val root: Path = Files.createTempDirectory("katty-cline")

    @AfterTest
    fun cleanUp() {
        root.toFile().deleteRecursively()
    }

    private fun store() = ClineStore(root)

    private fun database(fill: SqliteDatabase.() -> Unit) {
        SqliteDatabase.open(root.resolve("sessions.db"), readOnly = false).use { db ->
            db.execute(
                """CREATE TABLE sessions (session_id TEXT PRIMARY KEY, source TEXT NOT NULL, pid INTEGER NOT NULL, started_at TEXT NOT NULL,
                    interactive INTEGER NOT NULL, cwd TEXT NOT NULL, is_subagent INTEGER NOT NULL DEFAULT 0, messages_path TEXT, updated_at TEXT NOT NULL)""",
            )
            db.fill()
        }
    }

    // The times are epoch seconds that SQLite formats as Cline writes them: ISO text, in UTC, with milliseconds.
    private fun SqliteDatabase.session(
        id: String, cwd: String, started: Long, updated: Long, source: String = "cli", interactive: Boolean = true, subagent: Boolean = false, messages: Boolean = true,
    ) {
        val file = root.resolve("$id.messages.json")
        if (messages) Files.writeString(file, "{}")
        execute(
            "INSERT INTO sessions VALUES (?, ?, 25631, strftime('%Y-%m-%dT%H:%M:%S.123Z', ?, 'unixepoch'), ?, ?, ?, ${if (messages) "'$file'" else "NULL"}, " +
                "strftime('%Y-%m-%dT%H:%M:%S.456Z', ?, 'unixepoch'))",
            id, source, started, if (interactive) 1 else 0, cwd, if (subagent) 1 else 0, updated,
        )
    }

    // The hub's record: the events as the daemon writes them, with the times in milliseconds.
    private fun hub(name: String = "hub-events-hub-production.db", fill: SqliteDatabase.() -> Unit) {
        SqliteDatabase.open(root.resolve(name), readOnly = false).use { db ->
            db.execute(
                """CREATE TABLE hub_events (sequence INTEGER PRIMARY KEY AUTOINCREMENT, event TEXT NOT NULL, session_id TEXT, envelope_json TEXT NOT NULL,
                    created_at INTEGER NOT NULL)""",
            )
            db.fill()
        }
    }

    private fun SqliteDatabase.event(event: String, at: Long, payload: String, session: String? = null) =
        execute(
            "INSERT INTO hub_events (event, session_id, envelope_json, created_at) VALUES (?, ${if (session != null) "'$session'" else "NULL"}, ?, ?)",
            event, """{"version":"v1","event":"$event","timestamp":$at,"payload":$payload}""", at,
        )

    private fun SqliteDatabase.registered(client: String, pid: Int, at: Long, type: String = "cli") =
        event("hub.client.registered", at, """{"clientId":"$client","clientType":"$type","metadata":{"version":"0.0.95","pid":$pid},"connectedAt":$at}""")

    private fun SqliteDatabase.created(client: String, session: String, at: Long) =
        event("session.created", at, """{"session":{"sessionId":"$session","createdByClientId":"$client","status":"idle"}}""", session)

    private fun SqliteDatabase.asked(client: String, session: String, at: Long) =
        event("capability.requested", at, """{"requestId":"r","targetClientId":"$client","capabilityName":"hook.onEvent"}""", session)

    private fun SqliteDatabase.ran(client: String, session: String, at: Long) =
        event("run.started", at, """{"requestId":"q","clientId":"$client"}""", session)

    @Test
    fun theSessionIsTheNewestOneOfTheDirectorySinceTheProcessStarted() {
        database {
            session("1000_old", "/work", started = 100, updated = 900)
            session("1100_mine", "/work", started = 1_100, updated = 1_200)
            session("1150_newer", "/work", started = 1_150, updated = 1_300)
            session("1150_other", "/other", started = 1_150, updated = 1_400)
        }

        assertEquals("1150_newer", store().sessionFor("/work", 1_000_000))
    }

    @Test
    fun aSessionOfBeforeTheProcessThatWasUpdatedSinceIsItsToo() {
        database { session("100_resumed", "/work", started = 100, updated = 1_200) }

        assertEquals("100_resumed", store().sessionFor("/work", 1_000_000))
    }

    @Test
    fun aSubAgentsAnotherSourcesAndABackgroundSessionAreNotTheTerminalsNorOneWithNoMessages() {
        database {
            session("1100_mine", "/work", started = 1_100, updated = 1_200)
            session("1110_sub", "/work", started = 1_110, updated = 1_300, subagent = true)
            session("1120_ide", "/work", started = 1_120, updated = 1_400, source = "vscode")
            session("1130_batch", "/work", started = 1_130, updated = 1_500, interactive = false)
            session("1140_empty", "/work", started = 1_140, updated = 1_600, messages = false)
        }

        assertEquals("1100_mine", store().sessionFor("/work", 1_000_000))
    }

    @Test
    fun noSessionSinceTheProcessStartedIsNone() {
        database { session("100_old", "/work", started = 100, updated = 900) }

        assertNull(store().sessionFor("/work", 1_000_000))
    }

    @Test
    fun noDatabaseOrOneThatIsNotClinesIsNoReading() {
        assertNull(store().sessionFor("/work", 0))

        SqliteDatabase.open(root.resolve("sessions.db"), readOnly = false).use { it.execute("CREATE TABLE other (x integer)") }
        assertNull(store().sessionFor("/work", 0))
    }

    @Test
    fun twoClinesInOneDirectoryAreToldApartByTheProcessesOfTheirGroups() {
        database {
            session("1_first", "/work", started = 1_000, updated = 1_100)
            session("2_second", "/work", started = 1_050, updated = 1_200)
        }
        hub {
            // The native program is the one that registers, and a child of the script that has the terminal (its group has both).
            registered("core-a", pid = 501, at = 1_000_500)
            created("core-a", "1_first", at = 1_000_600)
            registered("core-b", pid = 601, at = 1_050_500)
            created("core-b", "2_second", at = 1_050_600)
        }

        assertEquals("1_first", store().sessionFor("/work", 1_000_000, listOf(500, 501)))
        assertEquals("2_second", store().sessionFor("/work", 1_050_000, listOf(600, 601)))
    }

    @Test
    fun aSessionThatTheClientResumedIsItsEvenThoughAnotherClientCreatedIt() {
        database {
            session("1_old", "/work", started = 100, updated = 5_000)
            session("2_other", "/work", started = 4_000, updated = 6_000)
        }
        hub {
            created("core-gone", "1_old", at = 100_000)
            registered("core-a", pid = 501, at = 4_500_000)
            // Asked a hook of, and ran: what a client that resumed a session does.
            asked("core-a", "1_old", at = 4_600_000)
            ran("core-a", "1_old", at = 4_700_000)
            registered("core-b", pid = 601, at = 4_800_000)
            created("core-b", "2_other", at = 4_900_000)
        }

        assertEquals("1_old", store().sessionFor("/work", 4_000_000, listOf(501)))
    }

    @Test
    fun theLastSessionTheClientDidSomethingWithIsTheOneItIsIn() {
        database {
            session("1_first", "/work", started = 1_000, updated = 2_000)
            session("2_next", "/work", started = 1_500, updated = 2_100)
        }
        hub {
            registered("core-a", pid = 501, at = 1_000_500)
            created("core-a", "1_first", at = 1_000_600)
            // `/new` or `/resume` in the same Cline.
            created("core-a", "2_next", at = 1_500_000)
        }

        assertEquals("2_next", store().sessionFor("/work", 1_000_000, listOf(501)))
    }

    @Test
    fun aClineThatIsInTheHubWithNoSessionYetHasNoneAndDoesNotTakeAnotherOnes() {
        database { session("2_second", "/work", started = 1_050, updated = 1_200) }
        hub {
            registered("core-b", pid = 601, at = 1_050_500)
            created("core-b", "2_second", at = 1_050_600)
            // A Cline that was just started: registered, said nothing.
            registered("core-c", pid = 701, at = 1_100_000)
        }

        assertNull(store().sessionFor("/work", 1_100_000, listOf(700, 701)))
    }

    @Test
    fun aRegistrationOfBeforeTheProcessIsAnOlderProgramThatHadItsPid() {
        database { session("1_first", "/work", started = 1_000, updated = 1_100) }
        hub {
            registered("core-a", pid = 501, at = 1_000_500)
            created("core-a", "1_first", at = 1_000_600)
        }

        // A later process that has the same pid does not own the old registration: it is not in the hub, and is looked for by its directory.
        assertEquals("1_first", store().sessionFor("/work", 1_000_000, listOf(501)))
        assertNull(store().sessionFor("/work", 9_000_000, listOf(501)))
    }
}
