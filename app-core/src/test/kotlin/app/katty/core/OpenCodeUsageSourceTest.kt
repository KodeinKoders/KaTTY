package app.katty.core

import app.katty.core.sqlite.SqliteDatabase
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OpenCodeUsageSourceTest {
    private val directory: Path = Files.createTempDirectory("katty-opencode-spend")
    private val databasePath = directory.resolve("opencode.db")
    private val now = Instant.parse("2026-10-20T15:00:00Z")

    @AfterTest
    fun cleanUp() {
        directory.toFile().deleteRecursively()
    }

    private fun source() = OpenCodeUsageSource(OpenCodeStore(databasePath, ModelLimits(directory.resolve("models.json"))), { now }, ZoneOffset.UTC)

    private fun at(text: String) = Instant.parse(text).toEpochMilli()

    @Test
    fun readsWhatWasSpentTodayOverSevenDaysAndThisMonth() {
        SqliteDatabase.open(databasePath, readOnly = false).use { db ->
            db.execute("CREATE TABLE session (id text PRIMARY KEY, parent_id text, directory text NOT NULL, time_created integer NOT NULL, time_updated integer NOT NULL, cost real)")
            db.execute("CREATE TABLE message (id text PRIMARY KEY, session_id text NOT NULL, time_created integer NOT NULL, time_updated integer NOT NULL, data text NOT NULL)")
            db.execute("INSERT INTO session VALUES ('ses_a', NULL, '/p', ?, ?, 0)", at("2026-09-01T00:00:00Z"), at("2026-10-20T01:00:00Z"))
            for ((id, time, cost) in listOf(
                Triple("m1", "2026-10-20T01:00:00Z", 1),
                Triple("m2", "2026-10-18T12:00:00Z", 10),
                Triple("m3", "2026-10-05T12:00:00Z", 100),
                Triple("m4", "2026-09-30T12:00:00Z", 1000),
            )) {
                db.execute("INSERT INTO message VALUES (?, 'ses_a', ?, ?, ?)", id, at(time), at(time), """{"role":"assistant","cost":$cost}""")
            }
        }

        val reading = runBlocking { source().read() }

        assertEquals(
            UsageReading(Agent.OPENCODE, emptyList(), listOf(UsageSpend("Today", 1.0), UsageSpend("7 days", 11.0), UsageSpend("This month", 111.0))),
            reading,
        )
    }

    @Test
    fun noDatabaseIsNoReading() {
        assertNull(runBlocking { source().read() })
    }

    @Test
    fun openCodeIsASourceOnlyWhenItIsInstalled() {
        assertEquals(setOf(Agent.OPENCODE), usageSources(mapOf(Agent.OPENCODE to Path.of("/bin/opencode")), directory).keys)
        assertEquals(emptySet(), usageSources(emptyMap(), directory).keys)
    }
}
