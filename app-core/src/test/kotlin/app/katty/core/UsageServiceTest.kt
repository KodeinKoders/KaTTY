package app.katty.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class UsageServiceTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val directory: Path = Files.createTempDirectory("katty-usage")

    @AfterTest
    fun cleanUp() {
        scope.cancel()
        directory.toFile().deleteRecursively()
    }

    private fun reading(agent: Agent, percent: Int) = UsageReading(agent, listOf(UsageWindow("Session", percent, null)))

    private fun await(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue(condition(), what)
    }

    @Test
    fun readsTheAgentsThatAreWantedAndOnlyThose() {
        val read = mutableListOf<Agent>()
        val service = UsageService(
            scope,
            mapOf(
                Agent.CLAUDE to UsageSource { synchronized(read) { read += Agent.CLAUDE }; reading(Agent.CLAUDE, 82) },
                Agent.CODEX to UsageSource { synchronized(read) { read += Agent.CODEX }; reading(Agent.CODEX, 41) },
            ),
            interval = 60_000.milliseconds,
        )

        service.want(setOf(Agent.CLAUDE))
        await("claude is read") { service.readings.value[Agent.CLAUDE] != null }

        assertEquals(82, service.readings.value.getValue(Agent.CLAUDE).primary!!.percentUsed)
        assertNull(service.readings.value[Agent.CODEX])
        assertEquals(listOf(Agent.CLAUDE), synchronized(read) { read.toList() })
        service.stop()
    }

    @Test
    fun anAgentWithoutASourceHasNoMeter() {
        val service = UsageService(scope, mapOf(Agent.CLAUDE to UsageSource { reading(Agent.CLAUDE, 1) }), interval = 60_000.milliseconds)

        service.want(setOf(Agent.COPILOT))
        Thread.sleep(100)

        assertEquals(emptyMap(), service.readings.value)
        service.stop()
    }

    @Test
    fun readsAgainAfterTheInterval() {
        val count = AtomicInteger()
        val service = UsageService(scope, mapOf(Agent.CLAUDE to UsageSource { reading(Agent.CLAUDE, count.incrementAndGet()) }), interval = 50.milliseconds)

        service.want(setOf(Agent.CLAUDE))

        await("a later reading") { (service.readings.value[Agent.CLAUDE]?.primary?.percentUsed ?: 0) >= 3 }
        service.stop()
    }

    @Test
    fun refreshingNowReadsAgainAtOnceAndSaysSoWhileItReads() {
        val count = AtomicInteger()
        val gate = java.util.concurrent.Semaphore(1)
        val service = UsageService(
            scope,
            mapOf(Agent.CLAUDE to UsageSource { gate.acquire(); try { reading(Agent.CLAUDE, count.incrementAndGet()) } finally { gate.release() } }),
            interval = 60_000.milliseconds,
        )
        service.want(setOf(Agent.CLAUDE))
        await("the first reading") { service.readings.value[Agent.CLAUDE] != null && !service.refreshing.value }

        // Held in the middle of a reading: it is refreshing, and asking again changes nothing.
        gate.acquire()
        service.refreshNow()
        await("it is refreshing") { service.refreshing.value }
        service.refreshNow()
        gate.release()
        await("the second reading, and no more") { count.get() == 2 && !service.refreshing.value }

        Thread.sleep(100)
        assertEquals(2, count.get())
        service.stop()
    }

    @Test
    fun withNothingWantedThereIsNothingToRefresh() {
        val service = UsageService(scope, mapOf(Agent.CLAUDE to UsageSource { reading(Agent.CLAUDE, 1) }), interval = 60_000.milliseconds)

        service.refreshNow()
        Thread.sleep(100)

        assertEquals(false, service.refreshing.value)
        assertEquals(emptyMap(), service.readings.value)
    }

    @Test
    fun aSourceThatFailsLeavesNoReadingAndDoesNotStopTheOthers() {
        val service = UsageService(
            scope,
            mapOf(
                Agent.CLAUDE to UsageSource { throw IllegalStateException("broken") },
                Agent.CODEX to UsageSource { reading(Agent.CODEX, 10) },
                Agent.ANTIGRAVITY to UsageSource { null },
            ),
            interval = 60_000.milliseconds,
        )

        service.want(setOf(Agent.CLAUDE, Agent.CODEX, Agent.ANTIGRAVITY))
        await("codex is read") { service.readings.value[Agent.CODEX] != null }

        assertEquals(setOf(Agent.CODEX), service.readings.value.keys)
        service.stop()
    }

    @Test
    fun anAgentThatIsNoLongerWantedLosesItsReading() {
        val service = UsageService(
            scope,
            mapOf(Agent.CLAUDE to UsageSource { reading(Agent.CLAUDE, 1) }, Agent.CODEX to UsageSource { reading(Agent.CODEX, 2) }),
            interval = 60_000.milliseconds,
        )
        service.want(setOf(Agent.CLAUDE, Agent.CODEX))
        await("both") { service.readings.value.size == 2 }

        service.want(setOf(Agent.CODEX))

        assertEquals(setOf(Agent.CODEX), service.readings.value.keys)
        service.stop()
    }

    @Test
    fun theProcessRunnerGivesWhatAProgramPrintedAndItsCode() {
        val result = ProcessCommandRunner.run(listOf("/bin/sh", "-c", "printf hello; exit 2"), directory, 5_000)!!

        assertEquals("hello", result.output)
        assertEquals(2, result.exitCode)
    }

    @Test
    fun aProgramThatTakesTooLongIsEnded() {
        assertNull(ProcessCommandRunner.run(listOf("/bin/sleep", "30"), directory, 300))
    }

    @Test
    fun aProgramThatIsNotThereGivesNothing() {
        assertNull(ProcessCommandRunner.run(listOf("/nonexistent/program"), directory, 1_000))
    }

    @Test
    fun theCommandsRunInTheDirectoryOfTheApplicationAndNotInAProject() {
        val result = ProcessCommandRunner.run(listOf("/bin/pwd"), directory, 5_000)!!

        assertEquals(directory.toRealPath().toString(), result.output.trim())
    }

    @Test
    fun claudeIsReadFromItsOwnCommand() = runBlocking {
        val seen = mutableListOf<List<String>>()
        val source = ClaudeUsageSource(
            Path.of("/bin/claude"), directory,
            runner = { command, _, _ -> seen += command; CommandResult(0, """{"result":"Current session: 26% used · resets 5:49am (Europe/Paris)"}""") },
        )

        val reading = source.read()!!

        assertEquals(listOf(listOf("/bin/claude", "-p", "/usage", "--output-format", "json")), seen)
        assertEquals(26, reading.primary!!.percentUsed)
    }

    @Test
    fun claudeFailingIsNoReading() = runBlocking {
        assertNull(ClaudeUsageSource(Path.of("/bin/claude"), directory, runner = { _, _, _ -> CommandResult(1, "") }).read())
        assertNull(ClaudeUsageSource(Path.of("/bin/claude"), directory, runner = { _, _, _ -> null }).read())
    }

    @Test
    fun antigravityIsOnlyAskedWhenItsVersionCanAnswerForFree() = runBlocking {
        val seen = mutableListOf<List<String>>()
        fun source(version: String) = AntigravityUsageSource(
            Path.of("/bin/agy"), directory,
            runner = { command, _, _ ->
                seen += command
                if (command.contains("--version")) CommandResult(0, version) else CommandResult(0, """{"command":{"data":{"groups":[{"name":"G","buckets":[{"remaining_fraction":0.5,"reset_time":"2026-10-21T16:53:27Z"}]}]}}}""")
            },
        )

        assertNull(source("1.1.10").read())
        assertEquals(listOf(listOf("/bin/agy", "--version")), seen)
        seen.clear()
        assertEquals(50, source("1.3.5").read()!!.primary!!.percentUsed)
        assertEquals(listOf("/bin/agy", "-p", "/usage", "--output-format", "json", "--print-timeout", "20s"), seen.last())
    }

    @Test
    fun codexIsAskedOverJsonRpc() = runBlocking {
        // A program that answers as the Codex server does: to initialize, then to the request for the rate limits.
        val server = directory.resolve("server.sh")
        server.writeText(
            """
            |#!/bin/sh
            |read init
            |printf '%s\n' '{"jsonrpc":"2.0","id":1,"result":{}}'
            |read initialized
            |read request
            |printf '%s\n' '{"jsonrpc":"2.0","id":2,"result":{"rateLimits":{"primary":{"usedPercent":41,"windowDurationMins":300,"resetsAt":1792000000}}}}'
            |sleep 30
            """.trimMargin(),
        )
        server.toFile().setExecutable(true)

        val reading = CodexUsageSource(listOf(server.toString()), directory).read()!!

        assertEquals(41, reading.primary!!.percentUsed)
        assertEquals("Session", reading.primary!!.label)
    }

    @Test
    fun codexThatDoesNotAnswerIsNoReading() = runBlocking {
        val server = directory.resolve("silent.sh")
        server.writeText("#!/bin/sh\nsleep 30\n")
        server.toFile().setExecutable(true)

        assertNull(CodexUsageSource(listOf(server.toString()), directory, timeoutMs = 500).read())
    }

    @Test
    fun theSourcesAreOnlyForTheAgentsThatHaveACommand() {
        val detected = Agent.entries.associateWith { Path.of("/bin/${it.executable}") }

        assertEquals(setOf(Agent.CLAUDE, Agent.CODEX, Agent.ANTIGRAVITY), usageSources(detected, directory).keys)
        assertEquals(emptySet(), usageSources(emptyMap(), directory).keys)
    }
}
