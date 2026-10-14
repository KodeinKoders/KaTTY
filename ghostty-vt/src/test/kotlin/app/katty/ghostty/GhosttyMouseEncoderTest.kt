package app.katty.ghostty

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GhosttyMouseEncoderTest {
    private val terminal = GhosttyTerminal(cols = 80, rows = 24)
    private val encoder = GhosttyMouseEncoder()

    @AfterTest
    fun close() {
        encoder.close()
        terminal.close()
    }

    // A cell is 10 by 20 pixels, so the pixel (35, 45) is in the cell at column 3 and row 2, which a program
    // is told as 4 and 3.
    private fun setup(vararg modes: String) {
        terminal.vtWrite(modes.joinToString("") { "\u001b[?${it}h" })
        encoder.setOptionsFrom(terminal)
        encoder.setSize(cols = 80, rows = 24, cellWidthPx = 10, cellHeightPx = 20)
    }

    private fun encode(
        action: GhosttyMouseAction,
        button: GhosttyMouseButton? = GhosttyMouseButton.LEFT,
        x: Float = 35f,
        y: Float = 45f,
        mods: GhosttyMods = GhosttyMods.NONE,
        anyButtonPressed: Boolean = false,
    ): String = encoder.encode(GhosttyMouseEvent(action, button, mods, x, y), anyButtonPressed).toString(Charsets.ISO_8859_1)

    @Test
    fun reportsAClickInTheSgrFormat() {
        setup("1000", "1006")

        assertEquals("\u001b[<0;4;3M", encode(GhosttyMouseAction.PRESS))
        assertEquals("\u001b[<0;4;3m", encode(GhosttyMouseAction.RELEASE))
        assertEquals("\u001b[<2;4;3M", encode(GhosttyMouseAction.PRESS, GhosttyMouseButton.RIGHT))
        assertEquals("\u001b[<1;4;3M", encode(GhosttyMouseAction.PRESS, GhosttyMouseButton.MIDDLE))
    }

    @Test
    fun reportsTheModifiers() {
        setup("1000", "1006")

        // Shift is 4, Alt 8, Ctrl 16.
        assertEquals("\u001b[<4;4;3M", encode(GhosttyMouseAction.PRESS, mods = GhosttyMods.SHIFT))
        assertEquals("\u001b[<8;4;3M", encode(GhosttyMouseAction.PRESS, mods = GhosttyMods.ALT))
        assertEquals("\u001b[<16;4;3M", encode(GhosttyMouseAction.PRESS, mods = GhosttyMods.CTRL))
    }

    @Test
    fun reportsTheWheelAsButtonsFourToSeven() {
        setup("1000", "1006")

        assertEquals("\u001b[<64;4;3M", encode(GhosttyMouseAction.PRESS, GhosttyMouseButton.WHEEL_UP))
        assertEquals("\u001b[<65;4;3M", encode(GhosttyMouseAction.PRESS, GhosttyMouseButton.WHEEL_DOWN))
        assertEquals("\u001b[<66;4;3M", encode(GhosttyMouseAction.PRESS, GhosttyMouseButton.WHEEL_LEFT))
        assertEquals("\u001b[<67;4;3M", encode(GhosttyMouseAction.PRESS, GhosttyMouseButton.WHEEL_RIGHT))
    }

    @Test
    fun reportsAClickInTheX10Format() {
        setup("1000")

        // ESC [ M, then the button, the column and the row, each plus 32 (and 1 for the cell, which starts at 1).
        assertContentEquals(
            byteArrayOf(0x1b, '['.code.toByte(), 'M'.code.toByte(), (32 + 0).toByte(), (32 + 4).toByte(), (32 + 3).toByte()),
            encoder.encode(GhosttyMouseEvent(GhosttyMouseAction.PRESS, GhosttyMouseButton.LEFT, GhosttyMods.NONE, 35f, 45f), false),
        )
    }

    @Test
    fun reportsAClickInTheUrxvtFormat() {
        setup("1000", "1015")

        assertEquals("\u001b[32;4;3M", encode(GhosttyMouseAction.PRESS))
    }

    @Test
    fun reportsPixelsInTheSgrPixelsFormat() {
        setup("1000", "1016")

        assertEquals("\u001b[<0;35;45M", encode(GhosttyMouseAction.PRESS))
    }

    @Test
    fun reportsNothingToAProgramThatDidNotAsk() {
        setup()

        assertEquals("", encode(GhosttyMouseAction.PRESS))
        assertEquals("", encode(GhosttyMouseAction.MOTION, button = null))
    }

    @Test
    fun reportsMotionAsTheProgramAsked() {
        // Normal tracking does not report motion.
        setup("1000", "1006")
        assertEquals("", encode(GhosttyMouseAction.MOTION, button = null))
        assertEquals("", encode(GhosttyMouseAction.MOTION, GhosttyMouseButton.LEFT, x = 55f, anyButtonPressed = true))
    }

    @Test
    fun reportsMotionWhileAButtonIsHeldForButtonTracking() {
        setup("1002", "1006")

        // 32 is added to the button for a motion.
        assertEquals("\u001b[<32;4;3M", encode(GhosttyMouseAction.MOTION, GhosttyMouseButton.LEFT, anyButtonPressed = true))
        assertEquals("", encode(GhosttyMouseAction.MOTION, button = null, x = 55f))
    }

    @Test
    fun reportsAllMotionForAnyEventTracking() {
        setup("1003", "1006")

        // 35 is a motion with no button.
        assertEquals("\u001b[<35;4;3M", encode(GhosttyMouseAction.MOTION, button = null))
    }

    @Test
    fun reportsAMotionOnceForACell() {
        setup("1003", "1006")

        assertEquals("\u001b[<35;4;3M", encode(GhosttyMouseAction.MOTION, button = null, x = 31f))
        assertEquals("", encode(GhosttyMouseAction.MOTION, button = null, x = 38f), "Still in the cell")
        assertEquals("\u001b[<35;5;3M", encode(GhosttyMouseAction.MOTION, button = null, x = 41f))

        encoder.reset()
        assertEquals("\u001b[<35;5;3M", encode(GhosttyMouseAction.MOTION, button = null, x = 41f), "Forgotten")
    }

    @Test
    fun followsWhatTheProgramAsksForLater() {
        setup("1000", "1006")
        assertTrue(encode(GhosttyMouseAction.PRESS).isNotEmpty())

        terminal.vtWrite("\u001b[?1000l")
        encoder.setOptionsFrom(terminal)

        assertEquals("", encode(GhosttyMouseAction.PRESS))
    }

    @Test
    fun theTerminalSaysWhetherTheProgramAskedForTheMouse() {
        assertEquals(false, terminal.mouseTracking)

        terminal.vtWrite("\u001b[?1000h")
        assertEquals(true, terminal.mouseTracking)

        terminal.vtWrite("\u001b[?1000l")
        assertEquals(false, terminal.mouseTracking)
    }

    @Test
    fun theTerminalSaysWhichShapeTheProgramAskedFor() {
        assertEquals(GhosttyMouseShape.TEXT, terminal.mouseShape, "The text cursor, until it is told otherwise")

        terminal.vtWrite("\u001b]22;pointer\u001b\\")
        assertEquals(GhosttyMouseShape.POINTER, terminal.mouseShape)

        terminal.vtWrite("\u001b]22;crosshair\u001b\\")
        assertEquals(GhosttyMouseShape.CROSSHAIR, terminal.mouseShape)
    }
}
