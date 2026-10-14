package app.katty.terminal.compose

import app.katty.terminal.TerminalMouseButton
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContextMenuGestureTest {
    private val gesture = ContextMenuGesture()
    private val opened = mutableListOf<Pair<Float, Float>>()

    private fun handle(event: PointerInfo, tracking: Boolean) = gesture.handle(event, tracking) { x, y -> opened += x to y }

    private fun press(button: TerminalMouseButton = TerminalMouseButton.RIGHT, shift: Boolean = false) =
        PointerInfo(PointerInfo.Kind.PRESS, 12f, 34f, button, held = setOf(button), shift = shift)

    private fun release(button: TerminalMouseButton = TerminalMouseButton.RIGHT) =
        PointerInfo(PointerInfo.Kind.RELEASE, 12f, 34f, button)

    @Test
    fun aRightPressIsTheHostsWhenTheProgramDidNotAskForTheMouse() {
        assertTrue(handle(press(), tracking = false))

        assertEquals(listOf(12f to 34f), opened)
    }

    @Test
    fun aRightPressIsTheProgramsWhenItAskedForTheMouse() {
        assertFalse(handle(press(), tracking = true))
        assertFalse(handle(release(), tracking = true))

        assertEquals(emptyList(), opened)
    }

    @Test
    fun shiftKeepsTheMenuForTheHostWhateverTheProgramAskedFor() {
        assertTrue(handle(press(shift = true), tracking = true))

        assertEquals(1, opened.size)
    }

    @Test
    fun theReleaseOfATakenPressIsTakenToo() {
        handle(press(), tracking = false)

        assertTrue(handle(release(), tracking = false))
        assertFalse(handle(release(), tracking = false))
    }

    @Test
    fun otherButtonsAreNotTheMenus() {
        assertFalse(handle(press(TerminalMouseButton.LEFT), tracking = false))
        assertFalse(handle(press(TerminalMouseButton.MIDDLE), tracking = false))

        assertEquals(emptyList(), opened)
    }
}
