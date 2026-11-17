package app.katty.terminal

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SessionProcessesTest {
    private val sessions = mutableListOf<TerminalSession>()

    @AfterTest
    fun close() {
        sessions.forEach { it.closeAndWait(timeoutMs = 0) }
    }

    // `/bin/sh -c script`, once it has [processes] processes in its session: the shell and what it started.
    private fun started(script: String, processes: Int): TerminalSession {
        val session = TerminalSession.start(80, 10, command = listOf("/bin/sh", "-c", script), shellIntegration = false).also { sessions += it }
        val deadline = System.nanoTime() + TIMEOUT_MS * 1_000_000
        while (sessionPids(session.programPid()).size < processes) {
            check(System.nanoTime() < deadline) { "Still waiting for $processes processes, the session has ${sessionPids(session.programPid())}" }
            Thread.sleep(20)
        }
        return session
    }

    @Test
    fun theSessionHasTheProgramAndWhatItStarted() {
        val session = started("sleep 30 & sleep 31 & wait", processes = 3)

        val pids = sessionPids(session.programPid())

        assertEquals(3, pids.size, "The shell and its two sleeps: $pids")
        assertTrue(session.programPid() in pids)
    }

    @Test
    fun closingWaitsForWhatTheProgramStartedThatIgnoresTheHangUp() {
        // The shell is killed after the grace of the hang-up, with its process group; its sleep is in a group of its own (job control), ignores the hang-up, and ends by itself a second later.
        val session = started("set -m; trap '' HUP; sleep 1.5 & wait", processes = 2)
        val pid = session.programPid()
        val start = System.nanoTime()

        val ended = session.closeAndWait()

        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertTrue(ended, "It ended by itself")
        assertTrue(elapsedMs >= 800, "It waited for the sleep: ${elapsedMs}ms")
        assertTrue(sessionPids(pid).isEmpty(), "Nothing is left: ${sessionPids(pid)}")
    }

    @Test
    fun whatIsStillThereAfterTheTimeoutIsKilled() {
        val session = started("set -m; trap '' HUP; sleep 30 & wait", processes = 2)
        val pid = session.programPid()
        val start = System.nanoTime()

        val ended = session.closeAndWait(timeoutMs = 500)

        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertFalse(ended, "It was killed")
        assertTrue(elapsedMs < 3_000, "It did not wait for the sleep: ${elapsedMs}ms")
        assertTrue(sessionPids(pid).isEmpty(), "Nothing is left: ${sessionPids(pid)}")
    }

    @Test
    fun aProgramThatEndsOnTheHangUpIsNotWaitedFor() {
        val session = started("sleep 30 & wait", processes = 2)
        val start = System.nanoTime()

        assertTrue(session.closeAndWait())

        assertTrue((System.nanoTime() - start) / 1_000_000 < 2_000, "It ended with the hang-up")
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
