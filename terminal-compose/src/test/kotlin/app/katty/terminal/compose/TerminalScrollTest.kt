package app.katty.terminal.compose

import app.katty.terminal.TerminalMouseButton
import app.katty.terminal.TerminalScrollbar
import java.awt.event.KeyEvent as AwtKeyEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WheelScrollerTest {
    private val wheel = WheelScroller()

    @Test
    fun aNotchIsThreeRows() {
        assertEquals(3, wheel.add(1f, maxRows = 24))
        assertEquals(-3, wheel.add(-1f, maxRows = 24))
    }

    @Test
    fun aTrackpadsFractionsAreAddedUpToWholeRows() {
        // 0.1 of a notch is 0.3 of a row.
        assertEquals(0, wheel.add(0.1f, 24))
        assertEquals(0, wheel.add(0.1f, 24))
        assertEquals(0, wheel.add(0.1f, 24))
        assertEquals(1, wheel.add(0.1f, 24), "1.2 rows")
        // 0.2 of a row is left.
        assertEquals(1, wheel.add(0.3f, 24))
    }

    @Test
    fun theDirectionCanChangeInTheMiddleOfARow() {
        assertEquals(0, wheel.add(0.2f, 24))
        assertEquals(0, wheel.add(-0.1f, 24))
        assertEquals(-1, wheel.add(-0.5f, 24))
    }

    @Test
    fun aSpinIsLimitedToAScreenAndTheRestIsDropped() {
        assertEquals(24, wheel.add(100f, maxRows = 24))
        assertEquals(0, wheel.add(0.1f, 24), "It does not go on after it stopped")
        assertEquals(-24, wheel.add(-100f, maxRows = 24))
    }

    @Test
    fun resetForgetsWhatWasLeft() {
        wheel.add(0.3f, 24)

        wheel.reset()

        assertEquals(0, wheel.add(0.3f, 24))
    }
}

class ScrollbarGeometryTest {
    // 1000 rows, 10 of them in view, on a track of 500 pixels.
    private fun geometry(offset: Long, total: Long = 1000, len: Long = 10, minThumb: Float = 20f) =
        ScrollbarGeometry(500f, TerminalScrollbar(total, offset, len), minThumb)

    @Test
    fun theThumbIsAtTheTopForTheTopOfTheHistory() {
        assertEquals(0f, geometry(0).thumbTop)
    }

    @Test
    fun theThumbIsAtTheBottomForTheBottom() {
        val geometry = geometry(990)

        assertEquals(500f, geometry.thumbTop + geometry.thumbHeight, 0.001f)
    }

    @Test
    fun theThumbIsInTheMiddleForTheMiddleOfTheHistory() {
        val geometry = geometry(495)

        assertEquals(250f, geometry.thumbTop + geometry.thumbHeight / 2, 0.5f)
    }

    @Test
    fun theThumbIsAsLongAsTheShareOfWhatIsInView() {
        assertEquals(250f, geometry(0, total = 20, len = 10).thumbHeight, 0.001f)
    }

    @Test
    fun theThumbIsNeverShorterThanItsMinimum() {
        assertEquals(20f, geometry(0).thumbHeight, "10 of 1000 rows would be 5 pixels")
    }

    @Test
    fun aRowIsTheInverseOfThePositionOfTheThumb() {
        for (offset in listOf(0L, 1L, 37L, 495L, 989L, 990L)) {
            val geometry = geometry(offset)

            assertEquals(offset, geometry.rowAt(geometry.thumbTop), "The offset $offset")
        }
    }

    @Test
    fun aRowIsKeptInsideTheHistory() {
        val geometry = geometry(0)

        assertEquals(0L, geometry.rowAt(-100f))
        assertEquals(990L, geometry.rowAt(10_000f))
    }

    @Test
    fun aTrackShorterThanTheMinimumIsAllThumb() {
        val geometry = ScrollbarGeometry(10f, TerminalScrollbar(100, 0, 10), 20f)

        assertEquals(10f, geometry.thumbHeight)
        assertEquals(0L, geometry.rowAt(5f))
    }
}

class ScrollbarInteractionTest {
    private val interaction = ScrollbarInteraction()
    private val rows = mutableListOf<Long>()

    // 1000 rows, 10 of them in view, on a track of 500 pixels, with the thumb at the top: 20 pixels long.
    private val geometry = ScrollbarGeometry(500f, TerminalScrollbar(1000, 0, 10), 20f)

    private fun handle(kind: PointerInfo.Kind, x: Float, y: Float, button: TerminalMouseButton? = null, held: Set<TerminalMouseButton> = emptySet()) =
        interaction.handle(PointerInfo(kind, x, y, button, held), geometry, width = 800f, stripWidth = 12f) { rows += it }

    private val left = TerminalMouseButton.LEFT

    @Test
    fun dragsTheThumb() {
        assertTrue(handle(PointerInfo.Kind.PRESS, 795f, 10f, left, setOf(left)), "On the thumb")
        assertTrue(interaction.dragging)
        assertEquals(emptyList(), rows, "Pressing on the thumb does not move it")

        assertTrue(handle(PointerInfo.Kind.MOVE, 795f, 260f, held = setOf(left)))
        // The thumb was grabbed at 10 pixels from its top: its top is at 250, half the way.
        assertEquals(listOf(geometry.rowAt(250f)), rows)

        assertTrue(handle(PointerInfo.Kind.RELEASE, 795f, 260f, left))
        assertFalse(interaction.dragging)
    }

    @Test
    fun aPressOnTheTrackPutsTheMiddleOfTheThumbThere() {
        assertTrue(handle(PointerInfo.Kind.PRESS, 795f, 300f, left, setOf(left)))

        assertEquals(listOf(geometry.rowAt(300f - 10f)), rows)
        assertTrue(interaction.dragging, "And it can be dragged from there")
    }

    @Test
    fun whatIsNotInTheStripIsNotItsOwn() {
        assertFalse(handle(PointerInfo.Kind.PRESS, 700f, 10f, left, setOf(left)))
        assertFalse(handle(PointerInfo.Kind.MOVE, 700f, 10f))
        assertFalse(interaction.hovered)
        assertFalse(interaction.dragging)
    }

    @Test
    fun hoveringTheStripIsTheScrollbarsAndShowsIt() {
        assertTrue(handle(PointerInfo.Kind.MOVE, 795f, 100f))
        assertTrue(interaction.hovered)

        assertFalse(handle(PointerInfo.Kind.MOVE, 100f, 100f))
        assertFalse(interaction.hovered)
    }

    @Test
    fun aDragKeepsGoingOutsideTheStrip() {
        handle(PointerInfo.Kind.PRESS, 795f, 10f, left, setOf(left))

        assertTrue(handle(PointerInfo.Kind.MOVE, 300f, 260f, held = setOf(left)), "The pointer went to the side: it is still dragging")
        assertEquals(1, rows.size)
    }

    @Test
    fun aMoveWithoutTheButtonEndsTheDrag() {
        handle(PointerInfo.Kind.PRESS, 795f, 10f, left, setOf(left))

        // The release was not seen.
        handle(PointerInfo.Kind.MOVE, 300f, 260f)

        assertFalse(interaction.dragging)
        assertEquals(emptyList(), rows)
    }

    @Test
    fun theOtherButtonsOfTheStripAreSwallowedButScrollNothing() {
        assertTrue(handle(PointerInfo.Kind.PRESS, 795f, 300f, TerminalMouseButton.RIGHT, setOf(TerminalMouseButton.RIGHT)))

        assertEquals(emptyList(), rows)
        assertFalse(interaction.dragging)
    }

    @Test
    fun leavingTheTerminalHidesItUnlessItIsDragged() {
        handle(PointerInfo.Kind.MOVE, 795f, 100f)
        interaction.exit()
        assertFalse(interaction.hovered)

        handle(PointerInfo.Kind.PRESS, 795f, 10f, left, setOf(left))
        interaction.exit()
        assertTrue(interaction.hovered)
    }

    @Test
    fun theWheelIsNotTheScrollbars() {
        assertFalse(handle(PointerInfo.Kind.SCROLL, 795f, 100f))
    }
}

class HistoryKeyTest {
    private fun key(code: Int, shift: Boolean = true, ctrl: Boolean = false, alt: Boolean = false, meta: Boolean = false, kind: KeyInfo.Kind = KeyInfo.Kind.PRESSED) =
        KeyInfo(kind, code, AwtKeyEvent.KEY_LOCATION_STANDARD, AwtKeyEvent.CHAR_UNDEFINED, shift, ctrl, alt, meta)

    @Test
    fun shiftAndPageUpPageDownHomeAndEndScrollTheHistory() {
        for (code in listOf(AwtKeyEvent.VK_PAGE_UP, AwtKeyEvent.VK_PAGE_DOWN, AwtKeyEvent.VK_HOME, AwtKeyEvent.VK_END)) {
            assertTrue(isHistoryKey(key(code)), "$code")
            assertTrue(isHistoryKey(key(code, kind = KeyInfo.Kind.RELEASED)), "The release of $code is the terminal's too")
        }
    }

    @Test
    fun theKeysWithoutShiftOrWithAnotherModifierAreTheProgramsOwn() {
        assertFalse(isHistoryKey(key(AwtKeyEvent.VK_PAGE_UP, shift = false)))
        assertFalse(isHistoryKey(key(AwtKeyEvent.VK_PAGE_UP, ctrl = true)))
        assertFalse(isHistoryKey(key(AwtKeyEvent.VK_PAGE_UP, alt = true)))
        assertFalse(isHistoryKey(key(AwtKeyEvent.VK_PAGE_UP, meta = true)))
    }

    @Test
    fun otherKeysAreTheProgramsOwn() {
        assertFalse(isHistoryKey(key(AwtKeyEvent.VK_UP)))
        assertFalse(isHistoryKey(key(AwtKeyEvent.VK_A)))
        assertFalse(isHistoryKey(key(AwtKeyEvent.VK_PAGE_UP, kind = KeyInfo.Kind.TYPED)))
    }
}
