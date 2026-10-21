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

class KiloUsageTest {
    private val directory: Path = Files.createTempDirectory("katty-kilo-usage")
    private val database = directory.resolve("kilo.db")
    private val models = directory.resolve("models.json")
    private val now = Instant.parse("2026-10-20T15:00:00Z")

    @AfterTest
    fun cleanUp() {
        directory.toFile().deleteRecursively()
    }

    private fun store() = OpenCodeStore(database, ModelLimits(models))

    private fun at(text: String) = Instant.parse(text).toEpochMilli()

    // Kilo's sessions and messages as its database has them (OpenCode 1.x's): the answer is what `kilo` 7.8.15 wrote.
    private fun fill() {
        SqliteDatabase.open(database, readOnly = false).use { db ->
            db.execute("CREATE TABLE session (id text PRIMARY KEY, parent_id text, directory text NOT NULL, time_created integer NOT NULL, time_updated integer NOT NULL, cost real DEFAULT 0 NOT NULL)")
            db.execute("CREATE TABLE message (id text PRIMARY KEY, session_id text NOT NULL, time_created integer NOT NULL, time_updated integer NOT NULL, data text NOT NULL)")
            db.execute("INSERT INTO session VALUES ('ses_kilo', NULL, '/work', ?, ?, 0.5)", at("2026-10-20T01:00:00Z"), at("2026-10-20T01:05:00Z"))
            db.execute(
                "INSERT INTO message VALUES ('msg_1', 'ses_kilo', ?, ?, ?)", at("2026-10-20T01:01:00Z"), at("2026-10-20T01:01:00Z"),
                """{"role":"assistant","mode":"code","agent":"code","cost":0.5,"tokens":{"total":21768,"input":19435,"output":67,"reasoning":0,"cache":{"write":0,"read":2266}},"modelID":"kilo-auto/free","providerID":"kilo"}""",
            )
        }
    }

    @Test
    fun whatKiloSpentIsReadFromItsDatabaseAndSaysItIsKilo() {
        fill()
        val source = OpenCodeUsageSource(store(), { now }, ZoneOffset.UTC, Agent.KILO)

        val reading = runBlocking { source.read() }

        assertEquals(UsageReading(Agent.KILO, emptyList(), listOf(UsageSpend("Today", 0.5), UsageSpend("7 days", 0.5), UsageSpend("This month", 0.5))), reading)
    }

    @Test
    fun aSessionOfKiloHasItsCostAndHowFullItsContextIsWhenItsModelIsInItsList() {
        fill()
        models.toFile().writeText("""{"kilo":{"models":{"kilo-auto/free":{"limit":{"context":200000,"output":100}}}}}""")

        val usage = OpenCodeUsageReader(store()).read("ses_kilo")

        // 21 768 tokens of 200 000 is 10.88 %, which is shown cut to 10 as it is for every agent.
        assertEquals(SessionUsage(contextPercent = 10, costUsd = 0.5), usage)
    }

    @Test
    fun aModelThatItsListDoesNotHaveHasACostAndNoRing() {
        fill()

        assertEquals(SessionUsage(contextPercent = null, costUsd = 0.5), OpenCodeUsageReader(store()).read("ses_kilo"))
    }

    @Test
    fun kiloHasAReaderOfItsSessionsAndASourceOfItsSpendOnlyWhenItIsInstalled() {
        assertEquals(setOf(Agent.OPENCODE, Agent.KILO), sessionUsageReaders().keys)
        assertEquals(setOf(Agent.KILO), usageSources(mapOf(Agent.KILO to Path.of("/bin/kilo")), directory).keys)
    }
}
