package app.katty.ghostty

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class GhosttyFocusTest {
    private val terminal = GhosttyTerminal(cols = 20, rows = 4)

    @AfterTest
    fun close() {
        terminal.close()
    }

    private fun report(focused: Boolean) = terminal.encodeFocus(focused).toString(Charsets.UTF_8).replace("\u001b", "ESC")

    @Test
    fun reportsNothingUntilTheProgramAsksToBeTold() {
        assertEquals("", report(true))
        assertEquals("", report(false))
    }

    @Test
    fun reportsTheFocusOnceTheProgramAskedForIt() {
        terminal.vtWrite("\u001b[?1004h")

        assertEquals("ESC[I", report(true))
        assertEquals("ESC[O", report(false))
    }

    @Test
    fun stopsReportingWhenTheProgramTurnsItOff() {
        terminal.vtWrite("\u001b[?1004h")
        terminal.vtWrite("\u001b[?1004l")

        assertContentEquals(ByteArray(0), terminal.encodeFocus(true))
    }
}
