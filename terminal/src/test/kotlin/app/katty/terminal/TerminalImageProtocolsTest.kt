package app.katty.terminal

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// The images of the Kitty protocol, the only ones that libghostty draws, through a real program and pty.
class TerminalImageProtocolsTest {
    private val sessions = mutableListOf<TerminalSession>()

    @AfterTest
    fun close() {
        sessions.forEach { it.close() }
    }

    private fun start(script: String, rows: Int = 5): TerminalSession =
        TerminalSession.start(80, rows, command = listOf("/bin/sh", "-c", script), shellIntegration = false).also { sessions += it }

    private fun TerminalFrame.text(row: Int): String = rows[row].cells.joinToString("") { it.text.ifEmpty { " " } }.trimEnd()

    private fun TerminalFrame.text(): String = rows.indices.joinToString("\n") { text(it) }

    private fun TerminalSession.awaitFrame(condition: (TerminalFrame) -> Boolean): TerminalFrame = runBlocking {
        try {
            withTimeout(TIMEOUT_MS) { frames.first(condition) }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("No frame matched in ${TIMEOUT_MS}ms. The last one:\n${frames.value.text()}", e)
        }
    }

    // A 2 by 2 image of red pixels, put on `columns` by `rows` cells; q=2 turns the answer off, which a program would read as input.
    private fun kitty(columns: Int, rows: Int): String {
        val pixels = Base64.getEncoder().encodeToString(ByteArray(16) { if (it % 4 == 0 || it % 4 == 3) 255.toByte() else 0 })
        return """\033_Ga=T,f=32,s=2,v=2,c=$columns,r=$rows,q=2;$pixels\033\\"""
    }

    @Test
    fun anImageIsShownWhereTheCursorWas() {
        val session = start("""printf 'ab'; printf '${kitty(2, 1)}'; printf 'X'; sleep 30""")

        val frame = session.awaitFrame { it.images.isNotEmpty() && "X" in it.text() }

        val image = frame.images.single()
        assertEquals(2f, image.left, "After ab")
        assertEquals(0f, image.top)
        assertEquals(2, image.image.width)
    }

    @Test
    fun anImageScrollsWithTheText() {
        val session = start("""printf '${kitty(2, 3)}'; seq 1 5; sleep 30""", rows = 6)

        val frame = session.awaitFrame { frame -> frame.images.isNotEmpty() && frame.rows.indices.any { frame.text(it) == "5" } }

        assertTrue(frame.images.single().top < 0f, "It went up with the lines under it: ${frame.images.single().top}")
    }

    @Test
    fun clearingTheScreenRemovesTheImages() {
        val session = start("""stty raw -echo; printf '${kitty(2, 1)}'; echo ready; head -c1 >/dev/null; printf '\033[2J\033[3J\033[H'; sleep 30""")
        session.awaitFrame { it.images.isNotEmpty() && "ready" in it.text() }

        session.paste("x")

        session.awaitFrame { it.images.isEmpty() }
    }

    private companion object {
        const val TIMEOUT_MS = 15_000L
    }
}
