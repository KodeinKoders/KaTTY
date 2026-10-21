package app.katty.core

import app.katty.core.sqlite.SqliteDatabase
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CodexStoreTest {
    private val root: Path = Files.createTempDirectory("katty-codex")

    @AfterTest
    fun cleanUp() {
        root.toFile().deleteRecursively()
    }

    private fun store() = CodexStore(root)

    private val full = """CREATE TABLE threads (id TEXT PRIMARY KEY, cwd TEXT NOT NULL, archived INTEGER NOT NULL DEFAULT 0, first_user_message TEXT NOT NULL DEFAULT '',
        created_at_ms INTEGER, updated_at_ms INTEGER, agent_role TEXT, originator TEXT)"""
    private val older = """CREATE TABLE threads (id TEXT PRIMARY KEY, cwd TEXT NOT NULL, archived INTEGER NOT NULL DEFAULT 0, first_user_message TEXT NOT NULL DEFAULT '',
        created_at_ms INTEGER, updated_at_ms INTEGER)"""

    private fun database(schema: String = full, file: String = "state_5.sqlite", fill: SqliteDatabase.() -> Unit) {
        SqliteDatabase.open(root.resolve(file), readOnly = false).use { db ->
            db.execute(schema)
            db.fill()
        }
    }

    private fun SqliteDatabase.thread(
        id: String, cwd: String = "/work", created: Long, updated: Long = created, message: String = "hello", archived: Boolean = false,
        agent: String? = null, originator: String = "codex-tui",
    ) {
        execute("INSERT INTO threads (id, cwd, archived, first_user_message, created_at_ms, updated_at_ms) VALUES (?, ?, ?, ?, ?, ?)", id, cwd, if (archived) 1 else 0, message, created, updated)
        if (agent != null) execute("UPDATE threads SET agent_role = ? WHERE id = ?", agent, id)
        // The older schema has no such column.
        runCatching { execute("UPDATE threads SET originator = ? WHERE id = ?", originator, id) }
    }

    private val a = "01a15f22-2335-77c3-ad26-c8387ae29a6b"
    private val b = "01a15f23-0000-77c3-ad26-c8387ae29a6c"
    private val old = "01a159d9-e9ea-7152-b7dc-ebe80f05bbc2"

    @Test
    fun aNewCodexIsInTheThreadMadeASecondAfterItStarted() {
        database {
            thread(old, created = 100, updated = 200)
            thread(a, created = 1_001_200)
            thread(b, created = 1_300_000)
        }

        assertEquals(a, store().sessionFor("/work", 1_000_000))
        assertEquals(b, store().sessionFor("/work", 1_299_000))
    }

    @Test
    fun twoNewCodexesOfOneDirectoryAreToldApartByWhenTheyStarted() {
        database {
            thread(a, created = 1_001_200)
            thread(b, created = 1_061_100)
        }

        assertEquals(a, store().sessionFor("/work", 1_000_000))
        assertEquals(b, store().sessionFor("/work", 1_060_000))
    }

    @Test
    fun theIdOnTheCommandLineIsTheThreadAndCannotBeMistakenForAnother() {
        database {
            thread(old, created = 100, updated = 1_000_500)
            thread(a, created = 1_000_900)
        }

        // `codex resume <id>`, as KaTTY types it: the thread of the id, though another one was made just after the process started.
        assertEquals(old, store().sessionFor("/work", 1_000_000, listOf("codex", "resume", old)))
        assertEquals(old, store().sessionFor("/work", 1_000_000, listOf("codex", "--model", "gpt-5", "resume", old)))
        // An id that there is no thread of, or an option where an id would be, is not one.
        assertEquals(a, store().sessionFor("/work", 1_000_000, listOf("codex", "resume", "01a15f99-0000-0000-0000-000000000000")))
        assertEquals(a, store().sessionFor("/work", 1_000_000, listOf("codex", "resume", "--last")))
    }

    @Test
    fun aResumedCodexWithNoIdIsInTheThreadOfTheDirectoryUpdatedSinceItStarted() {
        database {
            thread(old, created = 100, updated = 1_000_500)
            thread(a, created = 200, updated = 900)
        }

        assertEquals(old, store().sessionFor("/work", 1_000_000, listOf("codex", "resume", "--last")))
        assertNull(store().sessionFor("/work", 2_000_000))
    }

    @Test
    fun anotherDirectoryAnArchivedOneANewOneWithNoMessageAndAnAgentsOwnAreNotTheTerminals() {
        database {
            thread("other-dir", cwd = "/other", created = 1_001_000)
            thread("archived", created = 1_001_100, archived = true)
            thread("empty", created = 1_001_200, message = "")
            thread("sub-agent", created = 1_001_300, agent = "explorer")
            thread("desktop", created = 1_001_400, originator = "codex_desktop")
            thread(a, created = 1_001_500)
        }

        assertEquals(a, store().sessionFor("/work", 1_000_000))
    }

    @Test
    fun aDatabaseWithNoColumnOfOriginAndAgentIsReadWithoutThem() {
        database(older) {
            execute("INSERT INTO threads (id, cwd, first_user_message, created_at_ms, updated_at_ms) VALUES (?, '/work', 'hi', 1001000, 1001000)", a)
        }

        assertEquals(a, store().sessionFor("/work", 1_000_000))
    }

    @Test
    fun theNewestVersionOfTheDatabaseIsTheOneThatIsRead() {
        database(file = "state_4.sqlite") { thread("in-the-old-one", created = 1_001_000) }
        database(file = "state_5.sqlite") { thread(a, created = 1_001_200) }

        assertEquals(a, store().sessionFor("/work", 1_000_000))
    }

    @Test
    fun noDatabaseOrOneThatIsNotCodexsIsNoReading() {
        assertNull(store().sessionFor("/work", 0))

        SqliteDatabase.open(root.resolve("state_5.sqlite"), readOnly = false).use { it.execute("CREATE TABLE other (x integer)") }
        assertNull(store().sessionFor("/work", 0))
    }
}
