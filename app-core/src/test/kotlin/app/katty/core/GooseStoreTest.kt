package app.katty.core

import app.katty.core.sqlite.SqliteDatabase
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GooseStoreTest {
    private val root: Path = Files.createTempDirectory("katty-goose")
    private val databasePath = root.resolve("sessions.db")

    @AfterTest
    fun cleanUp() {
        root.toFile().deleteRecursively()
    }

    private fun store() = GooseStore(databasePath)

    private val full = """CREATE TABLE sessions (id TEXT PRIMARY KEY, name TEXT NOT NULL DEFAULT '', session_type TEXT NOT NULL DEFAULT 'user',
        working_dir TEXT NOT NULL, created_at TIMESTAMP, updated_at TIMESTAMP, parent_session_id TEXT, archived_at TIMESTAMP)"""
    private val older = """CREATE TABLE sessions (id TEXT PRIMARY KEY, name TEXT NOT NULL DEFAULT '', session_type TEXT NOT NULL DEFAULT 'user',
        working_dir TEXT NOT NULL, created_at TIMESTAMP, updated_at TIMESTAMP)"""
    private val messages = "CREATE TABLE messages (id INTEGER PRIMARY KEY AUTOINCREMENT, session_id TEXT NOT NULL, role TEXT NOT NULL)"

    private fun database(schema: String = full, fill: SqliteDatabase.() -> Unit) {
        SqliteDatabase.open(databasePath, readOnly = false).use { db ->
            db.execute(schema)
            db.execute(messages)
            db.fill()
        }
    }

    // The times are the epoch seconds that SQLite formats the way Goose stores them: text, in UTC.
    private fun SqliteDatabase.session(
        id: String, dir: String, created: Long, updated: Long, type: String = "user", messages: Int = 2, parent: String? = null, archived: Boolean = false,
    ) {
        execute(
            "INSERT INTO sessions (id, session_type, working_dir, created_at, updated_at) VALUES (?, ?, ?, datetime(?, 'unixepoch'), datetime(?, 'unixepoch'))",
            id, type, dir, created, updated,
        )
        if (parent != null) execute("UPDATE sessions SET parent_session_id = ? WHERE id = ?", parent, id)
        if (archived) execute("UPDATE sessions SET archived_at = datetime('now') WHERE id = ?", id)
        repeat(messages) { execute("INSERT INTO messages (session_id, role) VALUES (?, 'user')", id) }
    }

    @Test
    fun theSessionIsTheNewestOneOfTheDirectorySinceTheProcessStarted() {
        database {
            session("20261020_1", "/work", created = 100, updated = 900)
            session("20261021_1", "/work", created = 1_100, updated = 1_200)
            session("20261021_2", "/work", created = 1_150, updated = 1_300)
            session("20261021_3", "/other", created = 1_150, updated = 1_400)
        }

        assertEquals("20261021_2", store().sessionFor("/work", 1_000_000))
    }

    @Test
    fun aSessionOfBeforeTheProcessThatWasUpdatedSinceIsItsToo() {
        database { session("20261020_1", "/work", created = 100, updated = 1_200) }

        assertEquals("20261020_1", store().sessionFor("/work", 1_000_000))
    }

    @Test
    fun aSessionWithNoMessageAChildAndAnArchivedOneAreNotTheTerminals() {
        database {
            session("20261021_1", "/work", created = 1_100, updated = 1_200)
            session("20261021_2", "/work", created = 1_150, updated = 1_300, messages = 0)
            session("20261021_3", "/work", created = 1_150, updated = 1_400, parent = "20261021_1")
            session("20261021_4", "/work", created = 1_150, updated = 1_500, archived = true)
            session("20261021_5", "/work", created = 1_150, updated = 1_600, type = "scheduled")
        }

        assertEquals("20261021_1", store().sessionFor("/work", 1_000_000))
    }

    @Test
    fun aDatabaseWithNoColumnsOfParentAndArchiveIsReadWithoutThem() {
        database(older) { session("20261021_1", "/work", created = 1_100, updated = 1_200) }

        assertEquals("20261021_1", store().sessionFor("/work", 1_000_000))
    }

    @Test
    fun noSessionSinceTheProcessStartedIsNone() {
        database { session("20261020_1", "/work", created = 100, updated = 900) }

        assertNull(store().sessionFor("/work", 1_000_000))
    }

    @Test
    fun noDatabaseOrOneThatIsNotGoosesIsNoReading() {
        assertNull(store().sessionFor("/work", 0))

        SqliteDatabase.open(databasePath, readOnly = false).use { it.execute("CREATE TABLE other (x integer)") }
        assertNull(store().sessionFor("/work", 0))
    }
}
