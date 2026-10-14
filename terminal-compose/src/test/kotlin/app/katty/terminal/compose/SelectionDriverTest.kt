package app.katty.terminal.compose

import androidx.compose.ui.input.pointer.PointerIcon
import app.katty.terminal.TerminalMouseButton
import app.katty.terminal.TerminalPointerShape
import kotlin.test.Test
import kotlin.test.assertEquals

class SelectionDriverTest {
    private val driver = SelectionDriver()

    // Cells of 8 by 16 pixels.
    private fun translate(event: PointerInfo, tracking: Boolean = false) = driver.translate(event, 8f, 16f, tracking)

    private val left = TerminalMouseButton.LEFT

    private fun press(button: TerminalMouseButton = left, x: Float = 28f, y: Float = 40f, shift: Boolean = false) =
        PointerInfo(PointerInfo.Kind.PRESS, x, y, button, held = setOf(button), shift = shift)

    private fun move(x: Float = 44f, y: Float = 40f, held: Set<TerminalMouseButton> = setOf(left), alt: Boolean = false, meta: Boolean = false) =
        PointerInfo(PointerInfo.Kind.MOVE, x, y, held = held, alt = alt, meta = meta)

    private fun release(button: TerminalMouseButton = left, x: Float = 44f, y: Float = 40f) =
        PointerInfo(PointerInfo.Kind.RELEASE, x, y, button)

    @Test
    fun aProgramThatDidNotAskForTheMouseHasItsLeftButtonSelect() {
        assertEquals(listOf(SelectionAction.Press(3.5f, 2.5f)), translate(press()))
        assertEquals(listOf(SelectionAction.Drag(5.5f, 2.5f, rectangle = false)), translate(move()))
        assertEquals(listOf(SelectionAction.Release(5.5f, 2.5f)), translate(release()))
    }

    @Test
    fun theOtherButtonsDoNotSelect() {
        assertEquals(emptyList(), translate(press(TerminalMouseButton.RIGHT)))
        assertEquals(emptyList(), translate(press(TerminalMouseButton.MIDDLE)))
        assertEquals(false, driver.selecting)
    }

    @Test
    fun aProgramThatHasTheMouseHasItsLeftButtonForItself() {
        assertEquals(emptyList(), translate(press(), tracking = true))
        assertEquals(false, driver.selecting)
        assertEquals(emptyList(), translate(move(), tracking = true))
    }

    @Test
    fun shiftMakesTheLeftButtonSelectEvenWhenTheProgramHasTheMouse() {
        assertEquals(listOf(SelectionAction.Press(3.5f, 2.5f)), translate(press(shift = true), tracking = true))
        assertEquals(true, driver.selecting)
        assertEquals(listOf(SelectionAction.Drag(5.5f, 2.5f, rectangle = false)), translate(move(), tracking = true))
        assertEquals(listOf(SelectionAction.Release(5.5f, 2.5f)), translate(release(), tracking = true))
        assertEquals(false, driver.selecting)
    }

    @Test
    fun theGestureIsOverAndNotJustWhenShiftIsLetGo() {
        translate(press(shift = true), tracking = true)

        // Shift is up, and the drag is still the selection's.
        assertEquals(1, translate(move(), tracking = true).size)
        assertEquals(1, translate(release(), tracking = true).size)
    }

    @Test
    fun optionAndCommandSelectABlock() {
        translate(press())

        assertEquals(listOf(SelectionAction.Drag(5.5f, 2.5f, rectangle = true)), translate(move(alt = true, meta = true)))
        assertEquals(listOf(SelectionAction.Drag(5.5f, 2.5f, rectangle = false)), translate(move(alt = true)), "Option alone is not enough")
    }

    @Test
    fun aMoveWithoutTheButtonEndsTheGesture() {
        translate(press())

        // The release happened outside the window and was not seen.
        assertEquals(listOf(SelectionAction.Release(5.5f, 2.5f)), translate(move(held = emptySet())))
        assertEquals(false, driver.selecting)
        assertEquals(emptyList(), translate(move(held = emptySet())))
    }

    @Test
    fun movingAndScrollingDoNotSelectWithoutAGesture() {
        assertEquals(emptyList(), translate(move()))
        assertEquals(emptyList(), translate(PointerInfo(PointerInfo.Kind.SCROLL, 1f, 1f, scrollY = 1f)))
        assertEquals(emptyList(), translate(release()))
    }

    private val none = SelectionModifiers.NONE
    private val shift = SelectionModifiers(shift = true, alt = false, meta = false)
    private val block = SelectionModifiers(shift = false, alt = true, meta = true)
    private val shiftBlock = SelectionModifiers(shift = true, alt = true, meta = true)

    @Test
    fun thePointerIsTheArrowWhenTheProgramHasTheMouse() {
        assertEquals(PointerIcon.Default, terminalPointerIcon(TerminalPointerShape.TEXT, mouseTracking = true, none))
    }

    @Test
    fun thePointerIsTheIBeamWhenShiftIsHeldAndTheProgramHasTheMouse() {
        assertEquals(PointerIcon.Text, terminalPointerIcon(TerminalPointerShape.TEXT, mouseTracking = true, shift))
        assertEquals(PointerIcon.Text, terminalPointerIcon(TerminalPointerShape.POINTER, mouseTracking = true, shift))
    }

    @Test
    fun thePointerIsTheIBeamWhenTheProgramDidNotAskForTheMouse() {
        assertEquals(PointerIcon.Text, terminalPointerIcon(TerminalPointerShape.TEXT, mouseTracking = false, none))
        assertEquals(PointerIcon.Text, terminalPointerIcon(TerminalPointerShape.TEXT, mouseTracking = false, shift))
    }

    @Test
    fun thePointerIsTheShapeThatTheProgramAskedFor() {
        assertEquals(PointerIcon.Hand, terminalPointerIcon(TerminalPointerShape.POINTER, mouseTracking = true, none))
        assertEquals(PointerIcon.Hand, terminalPointerIcon(TerminalPointerShape.POINTER, mouseTracking = false, none))
    }

    @Test
    fun thePointerIsTheCrossWhenADragWouldSelectABlock() {
        // The program did not ask for the mouse: Option and Command are enough.
        assertEquals(PointerIcon.Crosshair, terminalPointerIcon(TerminalPointerShape.TEXT, mouseTracking = false, block))
        assertEquals(PointerIcon.Crosshair, terminalPointerIcon(TerminalPointerShape.POINTER, mouseTracking = false, block))
        // It did, and the drag is the terminal's because Shift is held.
        assertEquals(PointerIcon.Crosshair, terminalPointerIcon(TerminalPointerShape.TEXT, mouseTracking = true, shiftBlock))
    }

    @Test
    fun thePointerIsNotTheCrossWhenTheDragIsTheProgramsOrNotABlock() {
        // The program has the mouse and Shift is not held: the drag is its own, whatever else is held.
        assertEquals(PointerIcon.Default, terminalPointerIcon(TerminalPointerShape.TEXT, mouseTracking = true, block))
        // Option alone, or Command alone, does not select a block.
        assertEquals(PointerIcon.Text, terminalPointerIcon(TerminalPointerShape.TEXT, mouseTracking = false, SelectionModifiers(false, alt = true, meta = false)))
        assertEquals(PointerIcon.Text, terminalPointerIcon(TerminalPointerShape.TEXT, mouseTracking = false, SelectionModifiers(false, alt = false, meta = true)))
    }
}
