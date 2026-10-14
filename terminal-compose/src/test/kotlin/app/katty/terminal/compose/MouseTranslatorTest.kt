package app.katty.terminal.compose

import app.katty.terminal.MouseInput
import app.katty.terminal.TerminalModifier
import app.katty.terminal.TerminalMouseAction
import app.katty.terminal.TerminalMouseButton
import app.katty.terminal.TerminalPointerShape
import androidx.compose.ui.input.pointer.PointerIcon
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class MouseTranslatorTest {
    private val translator = MouseTranslator()

    // Cells of 8 by 16 pixels, which is not what the terminal measures: positions are divided by what it is given.
    private fun translate(event: PointerInfo) = translator.translate(event, cellWidth = 8f, cellHeight = 16f)

    private fun press(button: TerminalMouseButton = TerminalMouseButton.LEFT, x: Float = 28f, y: Float = 40f, shift: Boolean = false) =
        PointerInfo(PointerInfo.Kind.PRESS, x, y, button, held = setOf(button), shift = shift)

    private fun release(button: TerminalMouseButton = TerminalMouseButton.LEFT, x: Float = 28f, y: Float = 40f, shift: Boolean = false, held: Set<TerminalMouseButton> = emptySet()) =
        PointerInfo(PointerInfo.Kind.RELEASE, x, y, button, held = held, shift = shift)

    private fun move(x: Float = 28f, y: Float = 40f, held: Set<TerminalMouseButton> = emptySet(), shift: Boolean = false) =
        PointerInfo(PointerInfo.Kind.MOVE, x, y, held = held, shift = shift)

    private fun scroll(x: Float = 0f, y: Float = 0f, shift: Boolean = false, px: Float = 28f, py: Float = 40f) =
        PointerInfo(PointerInfo.Kind.SCROLL, px, py, shift = shift, scrollX = x, scrollY = y)

    @Test
    fun aPressIsAtTheCellUnderThePointer() {
        val input = translate(press()).single()

        assertEquals(TerminalMouseAction.PRESS, input.action)
        assertEquals(TerminalMouseButton.LEFT, input.button)
        assertEquals(3.5f, input.column)
        assertEquals(2.5f, input.row)
    }

    @Test
    fun aReleaseFollowsItsPress() {
        translate(press())

        val input = translate(release()).single()

        assertEquals(TerminalMouseAction.RELEASE, input.action)
        assertEquals(TerminalMouseButton.LEFT, input.button)
        assertEquals(false, input.anyButtonPressed)
    }

    @Test
    fun aReleaseWhileAnotherButtonIsHeldSaysSo() {
        translate(press(TerminalMouseButton.LEFT))
        translate(press(TerminalMouseButton.RIGHT))

        val input = translate(release(TerminalMouseButton.LEFT, held = setOf(TerminalMouseButton.RIGHT))).single()

        assertEquals(true, input.anyButtonPressed)
    }

    @Test
    fun aReleaseWithoutAPressIsNotSent() {
        assertEquals(emptyList(), translate(release()))
    }

    @Test
    fun theModifiersAreSentExceptShift() {
        val input = translate(
            PointerInfo(PointerInfo.Kind.PRESS, 1f, 1f, TerminalMouseButton.LEFT, ctrl = true, alt = true, meta = true),
        ).single()

        assertEquals(setOf(TerminalModifier.CTRL, TerminalModifier.ALT, TerminalModifier.SUPER), input.mods)
    }

    @Test
    fun shiftSwallowsAPressItsDragAndItsRelease() {
        assertEquals(emptyList(), translate(press(shift = true)))
        assertEquals(emptyList(), translate(move(held = setOf(TerminalMouseButton.LEFT))))
        // Shift is let go before the button: the release is still the one of a swallowed press.
        assertEquals(emptyList(), translate(release(shift = false)))

        // Nothing is left of it: the next click is sent.
        assertEquals(1, translate(press()).size)
    }

    @Test
    fun aPressThatWasSentHasItsReleaseSentWhateverIsDoneToShift() {
        translate(press())

        val input = translate(release(shift = true)).single()

        assertEquals(TerminalMouseAction.RELEASE, input.action)
        assertEquals(emptySet(), input.mods, "Shift is not told to the program")
    }

    @Test
    fun aDragIsMotionWithTheButtonThatIsHeld() {
        translate(press())

        val input = translate(move(x = 44f, held = setOf(TerminalMouseButton.LEFT))).single()

        assertEquals(TerminalMouseAction.MOTION, input.action)
        assertEquals(TerminalMouseButton.LEFT, input.button)
        assertEquals(true, input.anyButtonPressed)
        assertEquals(5.5f, input.column)
    }

    @Test
    fun hoveringIsMotionWithNoButton() {
        val input = translate(move()).single()

        assertEquals(TerminalMouseAction.MOTION, input.action)
        assertEquals(null, input.button)
        assertEquals(false, input.anyButtonPressed)
    }

    @Test
    fun hoveringWithShiftHeldIsSwallowed() {
        assertEquals(emptyList(), translate(move(shift = true)))
    }

    @Test
    fun aNotchOfTheWheelIsAPressOfItsButton() {
        assertEquals(TerminalMouseButton.WHEEL_DOWN, translate(scroll(y = 1f)).single().button)
        assertEquals(TerminalMouseButton.WHEEL_UP, translate(scroll(y = -1f)).single().button)
        assertEquals(TerminalMouseButton.WHEEL_RIGHT, translate(scroll(x = 1f)).single().button)
        assertEquals(TerminalMouseButton.WHEEL_LEFT, translate(scroll(x = -1f)).single().button)
        assertEquals(TerminalMouseAction.PRESS, translate(scroll(y = 1f)).single().action)
    }

    @Test
    fun theWheelIsAtTheCellUnderThePointer() {
        val input = translate(scroll(y = 1f, px = 60f, py = 8f)).single()

        assertEquals(7.5f, input.column)
        assertEquals(0.5f, input.row)
    }

    @Test
    fun aTrackpadsFractionsAreAddedUpToWholeNotches() {
        assertEquals(0, translate(scroll(y = 0.3f)).size)
        assertEquals(0, translate(scroll(y = 0.3f)).size)
        assertEquals(0, translate(scroll(y = 0.3f)).size)
        assertEquals(1, translate(scroll(y = 0.3f)).size, "1.2 notches")
        // 0.2 is left.
        assertEquals(1, translate(scroll(y = 0.8f)).size)
    }

    @Test
    fun severalNotchesAreSeveralPresses() {
        val inputs = translate(scroll(y = -2.5f))

        assertEquals(listOf(TerminalMouseButton.WHEEL_UP, TerminalMouseButton.WHEEL_UP), inputs.map { it.button })
        // The half that is left goes with the next one.
        assertEquals(1, translate(scroll(y = -0.5f)).size)
    }

    @Test
    fun aSpinIsLimitedAndTheRestIsDropped() {
        assertEquals(10, translate(scroll(y = 80f)).size)
        assertEquals(0, translate(scroll(y = 0.1f)).size, "It does not go on after it stopped")
    }

    @Test
    fun shiftOnTheWheelIsTheAxisAndNotABypass() {
        // AWT turns a scroll with Shift into a horizontal one.
        val input = translate(scroll(x = 1f, shift = true)).single()

        assertEquals(TerminalMouseButton.WHEEL_RIGHT, input.button)
        assertEquals(emptySet(), input.mods)
    }

    @Test
    fun resetForgetsTheGesture() {
        translate(press())
        translate(scroll(y = 0.6f))

        translator.reset()

        assertEquals(emptyList(), translate(release()), "The press is forgotten")
        assertEquals(0, translate(scroll(y = 0.6f)).size, "So is the half notch")
    }

    @Test
    fun everyShapeHasAPointer() {
        for (shape in TerminalPointerShape.entries) pointerIconFor(shape)
        assertEquals(PointerIcon.Text, pointerIconFor(TerminalPointerShape.TEXT))
        assertEquals(PointerIcon.Hand, pointerIconFor(TerminalPointerShape.POINTER))
        assertEquals(PointerIcon.Crosshair, pointerIconFor(TerminalPointerShape.CROSSHAIR))
        assertEquals(PointerIcon.Default, pointerIconFor(TerminalPointerShape.DEFAULT))
        assertNotEquals(PointerIcon.Default, pointerIconFor(TerminalPointerShape.WAIT))
        assertNotEquals(PointerIcon.Default, pointerIconFor(TerminalPointerShape.NS_RESIZE))
    }
}
