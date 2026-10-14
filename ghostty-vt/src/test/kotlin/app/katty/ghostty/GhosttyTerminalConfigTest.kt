package app.katty.ghostty

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GhosttyTerminalConfigTest {
    private fun cursorStyleOf(terminal: GhosttyTerminal, write: String = ""): GhosttyCursorStyle =
        GhosttyRenderState().use { state ->
            if (write.isNotEmpty()) terminal.vtWrite(write)
            state.update(terminal)
            state.cursor.style
        }

    @Test
    fun theDefaultCursorStyleIsTheOneThatIsAskedFor() {
        for (style in listOf(GhosttyCursorStyle.BAR, GhosttyCursorStyle.BLOCK, GhosttyCursorStyle.UNDERLINE)) {
            GhosttyTerminal(cols = 20, rows = 4, defaultCursorStyle = style).use { terminal ->
                assertEquals(style, cursorStyleOf(terminal), "Before any program wrote")
            }
        }
    }

    @Test
    fun aProgramsCursorStyleWinsOverTheDefaultAndItsResetGoesBackToIt() {
        GhosttyTerminal(cols = 20, rows = 4, defaultCursorStyle = GhosttyCursorStyle.BAR).use { terminal ->
            assertEquals(GhosttyCursorStyle.UNDERLINE, cursorStyleOf(terminal, "\u001b[4 q"))
            assertEquals(GhosttyCursorStyle.BLOCK, cursorStyleOf(terminal, "\u001b[2 q"))
            assertEquals(GhosttyCursorStyle.BAR, cursorStyleOf(terminal, "\u001b[0 q"), "Reset: back to the default")
        }
    }

    @Test
    fun withoutAStyleTheCursorIsABlock() {
        GhosttyTerminal(cols = 20, rows = 4).use { terminal ->
            assertEquals(GhosttyCursorStyle.BLOCK, cursorStyleOf(terminal))
        }
    }

    @Test
    fun theHollowBlockIsNotADefaultStyle() {
        assertFailsWith<IllegalArgumentException> { GhosttyTerminal(cols = 20, rows = 4, defaultCursorStyle = GhosttyCursorStyle.BLOCK_HOLLOW) }
    }

    private fun GhosttyTerminal.history(): Long = scrollbar.total

    @Test
    fun loweringTheScrollbackLimitRemovesHistory() {
        GhosttyTerminal(cols = 80, rows = 10, scrollbackMaxBytes = 100_000_000).use { terminal ->
            repeat(30_000) { terminal.vtWrite("line $it\r\n") }
            val before = terminal.history()

            terminal.setScrollbackMaxBytes(1_000_000)

            val after = terminal.history()
            assertTrue(after < before, "The history went from $before rows to $after")
            assertTrue(after > 10, "But a history is still kept")
        }
    }

    @Test
    fun theScrollbackLimitAtTheStartIsTheOneInForce() {
        val small = GhosttyTerminal(cols = 80, rows = 10, scrollbackMaxBytes = 1_000_000)
        val large = GhosttyTerminal(cols = 80, rows = 10, scrollbackMaxBytes = 100_000_000)
        try {
            repeat(30_000) {
                small.vtWrite("line $it\r\n")
                large.vtWrite("line $it\r\n")
            }

            assertTrue(small.history() < large.history(), "${small.history()} rows against ${large.history()}")
        } finally {
            small.close()
            large.close()
        }
    }

    @Test
    fun aNegativeLimitIsRefused() {
        GhosttyTerminal(cols = 20, rows = 4).use { terminal ->
            assertFailsWith<IllegalArgumentException> { terminal.setScrollbackMaxBytes(-1) }
            assertFailsWith<IllegalArgumentException> { terminal.setImageStorageLimit(-1) }
        }
    }

    @Test
    fun loweringTheImageLimitToZeroRemovesTheImages() {
        GhosttyTerminal(cols = 20, rows = 10, imageStorageLimitBytes = 10_000_000).use { terminal ->
            terminal.resize(20, 10, 10, 20)
            val pixels = Base64.getEncoder().encodeToString(ByteArray(16) { 255.toByte() })
            terminal.vtWrite("\u001b_Ga=T,f=32,s=2,v=2,i=1;$pixels\u001b\\")
            assertEquals(1, terminal.kittyPlacements().size)

            terminal.setImageStorageLimit(0)

            assertEquals(emptyList(), terminal.kittyPlacements())
        }
    }
}
