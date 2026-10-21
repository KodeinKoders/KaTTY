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

class OmpUsageSourceTest {
    private val directory: Path = Files.createTempDirectory("katty-omp-usage")
    private val database = directory.resolve("stats.db")
    private val now = Instant.parse("2026-10-20T15:00:00Z")
    private val executable = Path.of("/opt/omp/bin/omp")

    @AfterTest
    fun cleanUp() {
        directory.toFile().deleteRecursively()
    }

    private fun at(text: String) = Instant.parse(text).toEpochMilli()

    // The table that `omp stats` fills from the files of its sessions: a row for each request, with its time in milliseconds and what it cost.
    private fun fill(vararg requests: Pair<String, Double>) {
        SqliteDatabase.open(database, readOnly = false).use { db ->
            db.execute("CREATE TABLE messages (id INTEGER PRIMARY KEY AUTOINCREMENT, timestamp INTEGER NOT NULL, cost_total REAL NOT NULL, agent_type TEXT NOT NULL DEFAULT 'main')")
            // The cost is in the statement: a parameter is a text or a whole number.
            for ((time, cost) in requests) db.execute("INSERT INTO messages (timestamp, cost_total) VALUES (?, $cost)", at(time))
        }
    }

    private class Recorder(private val result: CommandResult?) : CommandRunner {
        val commands = mutableListOf<Pair<List<String>, Path>>()

        override fun run(command: List<String>, directory: Path, timeoutMs: Long): CommandResult? {
            commands += command to directory
            return result
        }
    }

    private fun source(runner: CommandRunner) = OmpUsageSource(executable, directory, database, runner, { now }, ZoneOffset.UTC)

    @Test
    fun readsWhatWasSpentTodayOverSevenDaysAndThisMonthAfterItsCommandBroughtTheDatabaseUpToDate() {
        fill(
            "2026-10-20T01:00:00Z" to 1.0,
            "2026-10-18T12:00:00Z" to 10.0,
            "2026-10-05T12:00:00Z" to 100.0,
            "2026-09-30T12:00:00Z" to 1000.0,
        )
        val runner = Recorder(CommandResult(0, """{"overall":{"totalCost":1}}"""))

        val reading = runBlocking { source(runner).read() }

        assertEquals(
            UsageReading(Agent.OMP, emptyList(), listOf(UsageSpend("Today", 1.0), UsageSpend("7 days", 11.0), UsageSpend("This month", 111.0))),
            reading,
        )
        // Its output is left alone: it has the last 24 hours and no other range. The command is run where no project is.
        assertEquals(listOf(listOf("/opt/omp/bin/omp", "stats", "-j") to directory), runner.commands)
    }

    @Test
    fun anEmptyDatabaseIsNothingSpent() {
        fill()

        assertEquals(
            UsageReading(Agent.OMP, emptyList(), listOf(UsageSpend("Today", 0.0), UsageSpend("7 days", 0.0), UsageSpend("This month", 0.0))),
            runBlocking { source(Recorder(CommandResult(0, ""))).read() },
        )
    }

    @Test
    fun aCommandThatFailsOrDoesNotRunIsNoReadingEvenWithADatabaseThatMayBeOutOfDate() {
        fill("2026-10-20T01:00:00Z" to 1.0)

        assertNull(runBlocking { source(Recorder(CommandResult(1, ""))).read() })
        assertNull(runBlocking { source(Recorder(null)).read() })
    }

    @Test
    fun noDatabaseOrOneThatIsNotOmpsIsNoReading() {
        val runner = Recorder(CommandResult(0, ""))
        assertNull(runBlocking { source(runner).read() })

        SqliteDatabase.open(database, readOnly = false).use { it.execute("CREATE TABLE other (x integer)") }
        assertNull(runBlocking { source(runner).read() })
    }

    @Test
    fun ompIsASourceOnlyWhenItIsInstalled() {
        assertEquals(setOf(Agent.OMP), usageSources(mapOf(Agent.OMP to executable), directory).keys)
    }
}
