package app.katty.core

import app.katty.core.sqlite.SqliteDatabase
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CrushStoreTest {
    private val root: Path = Files.createTempDirectory("katty-crush")
    private val projects = root.resolve("projects.json")

    @AfterTest
    fun cleanUp() {
        root.toFile().deleteRecursively()
    }

    private fun store() = CrushStore(projects)

    private fun database(dataDirectory: Path, fill: SqliteDatabase.() -> Unit) {
        Files.createDirectories(dataDirectory)
        SqliteDatabase.open(dataDirectory.resolve("crush.db"), readOnly = false).use { db ->
            db.execute(
                """CREATE TABLE sessions (id TEXT PRIMARY KEY, parent_session_id TEXT, title TEXT NOT NULL, message_count INTEGER NOT NULL DEFAULT 0,
                    updated_at INTEGER NOT NULL, created_at INTEGER NOT NULL)""",
            )
            db.fill()
        }
    }

    private fun SqliteDatabase.session(id: String, created: Long, updated: Long, messages: Int = 2, parent: String? = null) =
        execute("INSERT INTO sessions (id, parent_session_id, title, message_count, updated_at, created_at) VALUES (?, ?, 't', ?, ?, ?)", id, parent ?: "", messages, updated, created)

    @Test
    fun theSessionIsTheNewestOneOfTheProjectSinceTheProcessStarted() {
        val project = root.resolve("work")
        database(project.resolve(".crush")) {
            session("old-session-0001", created = 100, updated = 900)
            session("mine-session-0001", created = 1_100, updated = 1_200)
            session("newer-session-0001", created = 1_150, updated = 1_300)
        }

        assertEquals("newer-session-0001", store().sessionFor(project.toString(), 1_000_000))
    }

    @Test
    fun aSessionOfBeforeTheProcessThatWasUpdatedSinceIsItsToo() {
        val project = root.resolve("work")
        database(project.resolve(".crush")) { session("resumed-session-0001", created = 100, updated = 1_200) }

        assertEquals("resumed-session-0001", store().sessionFor(project.toString(), 1_000_000))
    }

    @Test
    fun aSubAgentsSessionAndOneWithNoMessageAreNotTheTerminals() {
        val project = root.resolve("work")
        database(project.resolve(".crush")) {
            session("mine-session-0001", created = 1_100, updated = 1_200)
            session("child-session-0001", created = 1_150, updated = 1_300, parent = "mine-session-0001")
            session("empty-session-0001", created = 1_160, updated = 1_400, messages = 0)
        }

        assertEquals("mine-session-0001", store().sessionFor(project.toString(), 1_000_000))
    }

    @Test
    fun noSessionSinceTheProcessStartedIsNone() {
        val project = root.resolve("work")
        database(project.resolve(".crush")) { session("old-session-0001", created = 100, updated = 900) }

        assertNull(store().sessionFor(project.toString(), 1_000_000))
    }

    @Test
    fun theDatabaseIsWhereTheListOfProjectsSaysItIs() {
        val project = root.resolve("work")
        val elsewhere = root.resolve("elsewhere/data")
        database(elsewhere) { session("mine-session-0001", created = 1_100, updated = 1_200) }
        Files.writeString(projects, """{"projects":[{"path":"$project","data_dir":"$elsewhere","last_accessed":"2026-10-21T12:38:11Z"}]}""")

        assertEquals("mine-session-0001", store().sessionFor(project.toString(), 1_000_000))
    }

    @Test
    fun noDatabaseOrOneThatIsNotCrushsIsNoReading() {
        val project = root.resolve("work")
        assertNull(store().sessionFor(project.toString(), 0))

        Files.createDirectories(project.resolve(".crush"))
        SqliteDatabase.open(project.resolve(".crush/crush.db"), readOnly = false).use { it.execute("CREATE TABLE other (x integer)") }
        assertNull(store().sessionFor(project.toString(), 0))
    }
}
