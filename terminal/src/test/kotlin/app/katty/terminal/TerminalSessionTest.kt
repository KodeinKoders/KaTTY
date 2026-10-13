package app.katty.terminal

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

class TerminalSessionTest {
    private val sessions = mutableListOf<TerminalSession>()

    @AfterTest
    fun close() {
        sessions.forEach { it.close() }
    }

    // A shell script instead of the login shell, so that what the program does is known.
    private fun start(script: String, cols: Int = 80, rows: Int = 5): TerminalSession =
        TerminalSession.start(cols, rows, command = listOf("/bin/sh", "-c", script)).also { sessions += it }

    private fun TerminalFrame.text(row: Int): String =
        rows[row].cells.joinToString("") { it.text.ifEmpty { " " } }.trimEnd()

    private fun TerminalFrame.text(): String = rows.indices.joinToString("\n") { text(it) }

    private fun TerminalSession.awaitFrame(condition: (TerminalFrame) -> Boolean): TerminalFrame = runBlocking {
        try {
            withTimeout(TIMEOUT_MS) { frames.first(condition) }
        } catch (e: TimeoutCancellationException) {
            throw AssertionError("No frame matched in ${TIMEOUT_MS}ms. The last one:\n${frames.value.text()}", e)
        }
    }

    private fun TerminalSession.awaitExit(): Int = runBlocking {
        withTimeout(TIMEOUT_MS) { exited.filterNotNull().first() }
    }

    @Test
    fun publishesWhatTheProgramWrites() {
        val session = start("printf hello")

        val frame = session.awaitFrame { it.text(0) == "hello" }

        assertEquals(80, frame.cols)
        assertEquals(5, frame.rows.size)
        assertEquals(0, session.awaitExit())
    }

    @Test
    fun runsTheProgramInATerminalThatSupportsColors() {
        val session = start("""printf 'a\033[1;31mb\033[0m'; echo; echo "${'$'}TERM ${'$'}COLORTERM"; tput colors""")

        val frame = session.awaitFrame { it.text(2) == "256" }

        assertEquals("xterm-256color truecolor", frame.text(1).trimEnd())
        val (plain, styled) = frame.rows[0].cells
        assertEquals(TerminalStyle.NONE, plain.style)
        assertTrue(styled.style.bold)
        assertNotNull(styled.foreground)
    }

    @Test
    fun reflectsAResize() {
        val session = start("sleep 30")

        session.resize(cols = 40, rows = 10, cellWidthPx = 8, cellHeightPx = 16)

        val frame = session.awaitFrame { it.cols == 40 && it.rows.size == 10 }
        assertEquals(10, frame.rows.size)
    }

    @Test
    fun tellsTheProgramTheSizeOfItsTerminal() {
        val session = start("""sleep 0.5; stty size""")

        // The size is changed while the program sleeps: it is the new one that it reads.
        session.resize(cols = 33, rows = 7, cellWidthPx = 8, cellHeightPx = 16)

        session.awaitFrame { frame -> frame.rows.indices.any { frame.text(it) == "7 33" } }
    }

    @Test
    fun sendsEnterAsACarriageReturn() {
        // In raw mode, the program gets exactly the bytes that are sent. "ready" means it is in raw mode.
        val session = start("stty raw -echo; echo ready; head -c1 | od -An -tx1")
        session.awaitFrame { "ready" in it.text() }

        session.sendKey(KeyInput(TerminalKey.ENTER))

        session.awaitFrame { "0d" in it.text() }
    }

    @Test
    fun encodesKeysAccordingToTheModesTheProgramSet() {
        // The program asks for the Kitty keyboard protocol (CSI > 1 u) before it reads.
        val session = start("""printf '\033[>1u'; stty raw -echo; echo ready; head -c7 | od -An -tx1""")
        session.awaitFrame { "ready" in it.text() }

        session.sendKey(KeyInput(TerminalKey.ENTER, mods = setOf(TerminalModifier.SHIFT)))

        // ESC [ 1 3 ; 2 u, which od separates with more than a space.
        session.awaitFrame { "1b 5b 31 33 3b 32 75" in it.text().replace(Regex("\\s+"), " ") }
    }

    @Test
    fun keepsTheRowsThatDidNotChange() {
        val session = start("printf one; sleep 0.5; printf '\\r\\ntwo'; sleep 30")

        val first = session.awaitFrame { it.text(0) == "one" && it.text(1).isEmpty() }
        val second = session.awaitFrame { it.text(1) == "two" }

        assertSame(first.rows[0], second.rows[0], "A row that did not change is the same object")
        assertEquals(first.rows[0].id, second.rows[0].id)
    }

    @Test
    fun limitsTheFrameRateWhenTheOutputFloods() {
        val started = TimeSource.Monotonic.markNow()
        val session = start("seq 1 300000")

        var published = 0
        val last = runBlocking {
            withTimeout(TIMEOUT_MS) { session.frames.onEach { published++ }.first { "300000" in it.text() } }
        }
        val elapsed = started.elapsedNow()

        // The last output is published, not merged away by the frames that came before it.
        assertTrue("300000" in last.text())
        // An upper bound that does not depend on the speed of the machine: a frame is produced at most
        // every 1/60 s, and the one of the start of the session comes on top.
        val bound = (elapsed / (1.seconds / 60)).toInt() + 2
        assertTrue(published <= bound, "$published frames in $elapsed, at most $bound are allowed")
    }

    @Test
    fun endsTheProgramWhenClosed() {
        val session = start("sleep 30")
        session.awaitFrame { true }

        session.close()

        // 0 would be the exit code of a program that ran to its end.
        assertNotEquals(0, session.awaitExit())
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
