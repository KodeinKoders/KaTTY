package app.katty.core

import app.katty.core.sqlite.SqliteDatabase
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class OpenCodeStoreTest {
    private val directory: Path = Files.createTempDirectory("katty-opencode")
    private val databasePath = directory.resolve("opencode.db")
    private val modelsPath = directory.resolve("models.json")

    @AfterTest
    fun cleanUp() {
        directory.toFile().deleteRecursively()
    }

    private fun store() = OpenCodeStore(databasePath, ModelLimits(modelsPath))

    private fun database(vararg schemas: String, fill: SqliteDatabase.() -> Unit) {
        SqliteDatabase.open(databasePath, readOnly = false).use { db ->
            schemas.forEach { db.execute(it) }
            db.fill()
        }
    }

    private val v1 = """CREATE TABLE session (id text PRIMARY KEY, parent_id text, directory text NOT NULL, time_created integer NOT NULL,
        time_updated integer NOT NULL, cost real DEFAULT 0 NOT NULL)"""
    private val v1Messages = """CREATE TABLE message (id text PRIMARY KEY, session_id text NOT NULL, time_created integer NOT NULL,
        time_updated integer NOT NULL, data text NOT NULL)"""
    private val v2 = """CREATE TABLE session_v2 (id text PRIMARY KEY, parent_id text, directory text NOT NULL, time_created integer NOT NULL,
        time_updated integer NOT NULL, cost real DEFAULT 0 NOT NULL)"""
    private val v2Messages = """CREATE TABLE session_message (id text PRIMARY KEY, session_id text NOT NULL, type text NOT NULL, seq integer NOT NULL,
        time_created integer NOT NULL, time_updated integer NOT NULL, data text NOT NULL)"""

    private fun SqliteDatabase.session(table: String, id: String, dir: String, created: Long, updated: Long, cost: Double = 0.0, parent: String? = null) =
        if (parent == null) {
            execute("INSERT INTO $table (id, directory, time_created, time_updated, cost) VALUES (?, ?, ?, ?, 0)", id, dir, created, updated)
                .also { execute("UPDATE $table SET cost = $cost WHERE id = ?", id) }
        } else {
            execute("INSERT INTO $table (id, parent_id, directory, time_created, time_updated, cost) VALUES (?, ?, ?, ?, ?, $cost)", id, parent, dir, created, updated)
        }

    private fun SqliteDatabase.answer1(id: String, session: String, created: Long, cost: Double, output: Int, input: Int = 1000, model: String = "m") =
        execute(
            "INSERT INTO message VALUES (?, ?, ?, ?, ?)", id, session, created, created,
            """{"role":"assistant","providerID":"p","modelID":"$model","cost":$cost,"tokens":{"input":$input,"output":$output,"reasoning":0,"cache":{"read":10,"write":5}}}""",
        )

    private fun SqliteDatabase.answer2(id: String, session: String, seq: Int, created: Long, cost: Double, output: Int, input: Int = 1000) =
        execute(
            "INSERT INTO session_message VALUES (?, ?, 'assistant', ?, ?, ?, ?)", id, session, seq, created, created,
            """{"cost":$cost,"model":{"id":"m","providerID":"p"},"tokens":{"input":$input,"output":$output,"reasoning":0,"cache":{"read":10,"write":5}}}""",
        )

    private fun models(context: Int = 10_000) {
        modelsPath.toFile().writeText("""{"p":{"models":{"m":{"limit":{"context":$context,"output":100}},"n":{}}}}""")
    }

    @Test
    fun noDatabaseIsNoReading() {
        val store = store()
        assertNull(store.sessionFor("/x", 0))
        assertNull(store.updatedAt("ses_1"))
        assertNull(store.usage("ses_1"))
        assertNull(store.spend(0))
    }

    @Test
    fun theNewestSessionOfTheDirectorySinceTheProcessStartedIsTheOne() {
        database(v1, v1Messages) {
            session("session", "ses_old", "/p", 100, 200)
            session("session", "ses_a", "/p", 1000, 2000)
            session("session", "ses_b", "/p", 1500, 3000)
            session("session", "ses_other", "/q", 1500, 9000)
            session("session", "ses_child", "/p", 1600, 5000, parent = "ses_b")
        }
        assertEquals("ses_b", store().sessionFor("/p", 900))
        assertNull(store().sessionFor("/p", 6000))
        assertNull(store().sessionFor("/nowhere", 0))
    }

    @Test
    fun aSessionThatAProgramReportedIsTheSessionItNamesAndAChildOfOneIsItsRoot() {
        database(v1, v2) {
            session("session", "ses_a", "/p", 1000, 2000)
            session("session", "ses_child", "/p", 1100, 2500, parent = "ses_a")
            session("session", "ses_grandchild", "/p", 1200, 2600, parent = "ses_child")
            session("session_v2", "ses_v2", "/p", 1000, 3000)
        }

        assertEquals("ses_a", store().rootOf("ses_a"))
        assertEquals("ses_a", store().rootOf("ses_child"))
        assertEquals("ses_a", store().rootOf("ses_grandchild"))
        assertEquals("ses_v2", store().rootOf("ses_v2"), "A 2.x session, whose table is not the other's")
        assertNull(store().rootOf("ses_nobody"))
    }

    @Test
    fun anIdThatARecordCannotHoldIsReportedAsAHashOfItAndIsFoundByIt() {
        // A record id is 1 to 32 bytes of letters, digits and `_.+-`: the program reports `ses_` and 16 digits of the SHA-256 of any other id.
        val long = "ses_" + "a".repeat(40)
        val odd = "ses_with space"
        fun hash(id: String) = "ses_" + java.security.MessageDigest.getInstance("SHA-256").digest(id.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)
        database(v1) {
            session("session", long, "/p", 1000, 2000)
            session("session", odd, "/p", 1000, 2100)
            session("session", "ses_short", "/p", 1000, 2200)
        }

        assertEquals(long, store().rootOf(hash(long)))
        assertEquals(odd, store().rootOf(hash(odd)))
        assertNull(store().rootOf("ses_0123456789abcdef"), "A hash that is of no session")
    }

    @Test
    fun noDatabaseIsNoRootOfAReportedSession() {
        assertNull(store().rootOf("ses_a"))
    }

    @Test
    fun aSessionThatWasOnlyUpdatedSinceTheStartIsTheOneOfAResumedProcess() {
        database(v1) { session("session", "ses_a", "/p", 10, 5000) }
        assertEquals("ses_a", store().sessionFor("/p", 4000))
    }

    @Test
    fun bothVersionsAreSearchedAndTheNewestWins() {
        database(v1, v1Messages, v2, v2Messages) {
            session("session", "ses_v1", "/p", 1000, 2000)
            session("session_v2", "ses_v2", "/p", 1000, 3000)
        }
        assertEquals("ses_v2", store().sessionFor("/p", 0))
        assertEquals(3000L, store().updatedAt("ses_v2"))
        assertEquals(2000L, store().updatedAt("ses_v1"))
        assertNull(store().updatedAt("ses_none"))
    }

    @Test
    fun aDatabaseThatHasOnlyTheNewTablesWorks() {
        database(v2, v2Messages) { session("session_v2", "ses_v2", "/p", 1000, 3000) }
        assertEquals("ses_v2", store().sessionFor("/p", 0))
    }

    @Test
    fun theCostCountsTheSessionsItStarted() {
        database(v1, v1Messages) {
            session("session", "ses_a", "/p", 1, 2, cost = 1.5)
            session("session", "ses_c", "/p", 1, 2, cost = 0.25, parent = "ses_a")
            session("session", "ses_g", "/p", 1, 2, cost = 0.125, parent = "ses_c")
            session("session", "ses_x", "/p", 1, 2, cost = 100.0)
        }
        assertEquals(1.875, store().usage("ses_a")?.costUsd)
    }

    @Test
    fun theContextIsWhatTheLastAnswerWithOutputFilled() {
        models(10_000)
        database(v1, v1Messages) {
            session("session", "ses_a", "/p", 1, 2)
            answer1("m1", "ses_a", 10, 0.1, output = 100, input = 100)
            answer1("m2", "ses_a", 20, 0.1, output = 500, input = 4000) // 4000 + 500 + 10 + 5
            answer1("m3", "ses_a", 30, 0.0, output = 0, input = 9000) // a step that has not answered yet
        }
        assertEquals(45, store().usage("ses_a")?.contextPercent)
    }

    @Test
    fun theContextOfAVersion2SessionIsRead() {
        models(10_000)
        database(v2, v2Messages) {
            session("session_v2", "ses_a", "/p", 1, 2)
            answer2("m1", "ses_a", 1, 10, 0.1, output = 100, input = 100)
            answer2("m2", "ses_a", 2, 20, 0.1, output = 500, input = 4000)
        }
        assertEquals(45, store().usage("ses_a")?.contextPercent)
    }

    @Test
    fun aModelThatTheListDoesNotHaveHasNoPercentButHasItsCost() {
        models()
        database(v1, v1Messages) {
            session("session", "ses_a", "/p", 1, 2, cost = 2.0)
            answer1("m1", "ses_a", 10, 0.1, output = 100, model = "unknown")
        }
        val usage = assertNotNull(store().usage("ses_a"))
        assertNull(usage.contextPercent)
        assertEquals(2.0, usage.costUsd)
    }

    @Test
    fun aModelWithNoLimitHasNoPercent() {
        models()
        database(v1, v1Messages) {
            session("session", "ses_a", "/p", 1, 2)
            answer1("m1", "ses_a", 10, 0.1, output = 100, model = "n")
        }
        assertNull(store().usage("ses_a")?.contextPercent)
    }

    @Test
    fun theContextIsCappedAtAHundred() {
        models(100)
        database(v1, v1Messages) {
            session("session", "ses_a", "/p", 1, 2)
            answer1("m1", "ses_a", 10, 0.1, output = 100)
        }
        assertEquals(100, store().usage("ses_a")?.contextPercent)
    }

    @Test
    fun theSpendIsWhatAnswersCostedSinceAndOfBothVersions() {
        database(v1, v1Messages, v2, v2Messages) {
            session("session", "ses_a", "/p", 1, 5000)
            session("session", "ses_stale", "/p", 1, 100)
            session("session_v2", "ses_b", "/p", 1, 5000)
            answer1("m1", "ses_a", 4000, 0.5, output = 1)
            answer1("m2", "ses_a", 500, 7.0, output = 1) // before
            answer1("m3", "ses_stale", 4500, 9.0, output = 1) // a session that has not been updated since cannot have it
            answer2("m4", "ses_b", 1, 4200, 0.25, output = 1)
            database2UserMessage()
        }
        assertEquals(0.75, store().spend(1000))
        assertEquals(0.0, OpenCodeStore(databasePath, ModelLimits(modelsPath)).spend(10_000))
    }

    private fun SqliteDatabase.database2UserMessage() =
        execute("INSERT INTO session_message VALUES ('u1', 'ses_b', 'user', 0, 4100, 4100, '{\"cost\":99}')")

    @Test
    fun theModelListIsReadAgainWhenItChanges() {
        val limits = ModelLimits(modelsPath)
        assertNull(limits.contextOf("p", "m"))
        models(1000)
        assertEquals(1000, limits.contextOf("p", "m"))
        models(2000)
        modelsPath.toFile().setLastModified(System.currentTimeMillis() + 5000)
        assertEquals(2000, limits.contextOf("p", "m"))
    }

    @Test
    fun aModelListThatCouldNotBeReadIsReadAgainEvenIfItsTimeDidNotChange() {
        // Caught while OpenCode writes it: then whole, within the same second as far as its time says.
        modelsPath.toFile().writeText("{")
        val time = modelsPath.toFile().lastModified()
        val limits = ModelLimits(modelsPath)
        assertNull(limits.contextOf("p", "m"))

        models(1000)
        modelsPath.toFile().setLastModified(time)

        assertEquals(1000, limits.contextOf("p", "m"))
    }
}
