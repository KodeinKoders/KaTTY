package app.katty.core

import app.katty.core.sqlite.SqliteDatabase
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class KiloStoreTest {
    private val root: Path = Files.createTempDirectory("katty-kilo")
    private val data: Path = Files.createDirectories(root.resolve("kilo"))
    private val database = data.resolve("kilo.db")

    @AfterTest
    fun cleanUp() {
        root.toFile().deleteRecursively()
    }

    private val schema = """CREATE TABLE session (id text PRIMARY KEY, parent_id text, directory text NOT NULL, time_created integer NOT NULL,
        time_updated integer NOT NULL, cost real DEFAULT 0 NOT NULL)"""

    private fun sessions(fill: SqliteDatabase.() -> Unit) {
        SqliteDatabase.open(database, readOnly = false).use { db ->
            db.execute(schema)
            db.fill()
        }
    }

    private fun SqliteDatabase.session(id: String, dir: String = "/work", created: Long, updated: Long = created, parent: String? = null) =
        if (parent == null) {
            execute("INSERT INTO session (id, directory, time_created, time_updated) VALUES (?, ?, ?, ?)", id, dir, created, updated)
        } else {
            execute("INSERT INTO session (id, parent_id, directory, time_created, time_updated) VALUES (?, ?, ?, ?, ?)", id, parent, dir, created, updated)
        }

    // What `log/<start time>.log` has: a line that says the session when it is made, among others.
    private fun log(name: String, vararg lines: String): String {
        val file = Files.createDirectories(data.resolve("log")).resolve(name)
        Files.writeString(file, lines.joinToString("\n", postfix = "\n"))
        return file.toString()
    }

    private fun named(id: String) = "INFO  2026-10-21T15:59:27 +8ms service=kilo-sessions sessionId=$id session bootstrap skipped: no client"
    private val other = "INFO  2026-10-21T15:59:09 +137ms service=worker-rpc method=tuiReady id=0 rpc request"

    @Test
    fun theSessionIsTheOneThatTheLogOfTheProcessNames() {
        sessions {
            session("ses_first", created = 1_000)
            session("ses_second", created = 1_100)
        }
        val mine = log("2026-10-21T155909.log", other, named("ses_first"), other)

        // The newest session of the directory is the other's: the log is what says.
        assertEquals("ses_first", KiloStore().sessionFor("/work", 0, listOf(database.toString(), mine)))
    }

    @Test
    fun twoKilosOfOneDirectoryAreToldApartByTheirLogs() {
        sessions {
            session("ses_first", created = 1_000)
            session("ses_second", created = 1_100)
        }
        val one = log("2026-10-21T155909.log", named("ses_first"))
        val two = log("2026-10-21T160011.log", named("ses_second"))

        assertEquals("ses_first", KiloStore().sessionFor("/work", 0, listOf(database.toString(), one)))
        assertEquals("ses_second", KiloStore().sessionFor("/work", 0, listOf(database.toString(), two)))
    }

    @Test
    fun theLastSessionTheLogNamesIsTheOneItIsInAndASubAgentsIsItsRoot() {
        sessions {
            session("ses_root", created = 1_000)
            session("ses_new", created = 1_200)
            session("ses_child", created = 1_300, parent = "ses_root")
        }

        val tabs = log("2026-10-21T155909.log", named("ses_root"), named("ses_new"))
        assertEquals("ses_new", KiloStore().sessionFor("/work", 0, listOf(database.toString(), tabs)))

        val sub = log("2026-10-21T160011.log", named("ses_child"))
        assertEquals("ses_root", KiloStore().sessionFor("/work", 0, listOf(database.toString(), sub)))
    }

    @Test
    fun aLogThatNamesNothingLeavesTheNewestSessionOfTheDirectorySinceTheProcessStarted() {
        sessions {
            session("ses_old", created = 100, updated = 200)
            session("ses_mine", created = 1_000, updated = 1_500)
            session("ses_other_dir", dir = "/other", created = 1_100, updated = 1_600)
        }
        val quiet = log("2026-10-21T155909.log", other)

        assertEquals("ses_mine", KiloStore().sessionFor("/work", 900, listOf(database.toString(), quiet)))
        assertNull(KiloStore().sessionFor("/work", 9_000, listOf(database.toString(), quiet)))
    }

    @Test
    fun aLogOfAnotherFolderOrASessionTheDatabaseDoesNotKnowIsNotTaken() {
        sessions { session("ses_mine", created = 1_000, updated = 1_500) }
        val elsewhere = root.resolve("elsewhere/log").also { Files.createDirectories(it) }.resolve("2026-10-21T155909.log")
        Files.writeString(elsewhere, named("ses_not_here") + "\n")
        val unknown = log("2026-10-21T160011.log", named("ses_nowhere"))

        // Neither names a session of this database: the directory says.
        assertEquals("ses_mine", KiloStore().sessionFor("/work", 900, listOf(database.toString(), elsewhere.toString(), unknown)))
    }

    @Test
    fun theDatabaseIsTheOneThatTheProcessHasOpenWhateverItsChannel() {
        val channel = data.resolve("kilo-dev.db")
        SqliteDatabase.open(channel, readOnly = false).use { db ->
            db.execute(schema)
            db.session("ses_dev", created = 1_000, updated = 1_500)
        }

        assertEquals("ses_dev", KiloStore().sessionFor("/work", 900, listOf(channel.toString())))
    }

    @Test
    fun noDatabaseIsNoSession() {
        assertNull(KiloStore().sessionFor("/work", 0, listOf(root.resolve("kilo/kilo.db").toString())))
    }
}
