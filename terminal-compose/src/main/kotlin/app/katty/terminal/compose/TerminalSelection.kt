package app.katty.terminal.compose

import androidx.compose.ui.input.pointer.PointerIcon
import app.katty.terminal.TerminalMouseButton
import app.katty.terminal.TerminalPointerShape

/** What the terminal is asked to do for a selection. Positions are in cells, fractional. */
internal sealed interface SelectionAction {
    data class Press(val column: Float, val row: Float) : SelectionAction

    data class Drag(val column: Float, val row: Float, val rectangle: Boolean) : SelectionAction

    data class Release(val column: Float, val row: Float) : SelectionAction
}

/**
 * Tells which pointer events select text, and turns them into [SelectionAction]s.
 *
 * Text is selected with the left button: always when the program did not ask for the mouse, and with Shift
 * held when it did, since then the other presses are the program's. The gesture is what a press starts, so that
 * its drag and release are the selection's too, whatever Shift does after. A drag with Option and Command held
 * selects a block instead of a flow of text.
 *
 * Not thread-safe: it is used from the UI thread.
 */
internal class SelectionDriver {
    /** Whether a gesture is in progress, which the program is told nothing of. */
    var selecting: Boolean = false
        private set

    fun translate(event: PointerInfo, cellWidth: Float, cellHeight: Float, mouseTracking: Boolean): List<SelectionAction> {
        val column = event.x / cellWidth
        val row = event.y / cellHeight
        return when (event.kind) {
            PointerInfo.Kind.PRESS -> {
                if (event.button != TerminalMouseButton.LEFT || (mouseTracking && !event.shift)) return emptyList()
                selecting = true
                listOf(SelectionAction.Press(column, row))
            }

            PointerInfo.Kind.MOVE -> {
                if (!selecting) return emptyList()
                // A release that was not seen, outside the window for instance: the gesture is over.
                if (TerminalMouseButton.LEFT !in event.held) {
                    selecting = false
                    return listOf(SelectionAction.Release(column, row))
                }
                listOf(SelectionAction.Drag(column, row, rectangle = event.alt && event.meta))
            }

            PointerInfo.Kind.RELEASE -> {
                if (!selecting || event.button != TerminalMouseButton.LEFT) return emptyList()
                selecting = false
                listOf(SelectionAction.Release(column, row))
            }

            PointerInfo.Kind.SCROLL -> emptyList()
        }
    }

    fun reset() {
        selecting = false
    }
}

/** The modifiers that are held and that change what a drag does. */
internal data class SelectionModifiers(val shift: Boolean, val alt: Boolean, val meta: Boolean) {
    /** Whether a drag selects a block of cells instead of a flow of text. */
    val block: Boolean get() = alt && meta

    companion object {
        val NONE = SelectionModifiers(shift = false, alt = false, meta = false)
    }
}

/**
 * The pointer over the terminal.
 *
 * - Over the scrollbar, or while its thumb is dragged, it is the arrow whatever else: the strip is not the program's.
 * - While the modifier that opens links is held over one, it is the hand.
 * - When a drag would select a block (Option and Command held, and the left button selects: the program did not
 *   ask for the mouse, or Shift is held) it is the cross.
 * - When the program asked for the mouse the pointer is the arrow, since the clicks are its own, unless it asked
 *   for a shape, and the I-beam while Shift is held, since then they select text.
 * - Otherwise it is the shape that the program asked for, which is the I-beam until it says otherwise.
 */
internal fun terminalPointerIcon(
    shape: TerminalPointerShape,
    mouseTracking: Boolean,
    modifiers: SelectionModifiers,
    overScrollbar: Boolean = false,
    overLink: Boolean = false,
): PointerIcon = when {
    overScrollbar -> PointerIcon.Default
    overLink -> PointerIcon.Hand
    modifiers.block && (!mouseTracking || modifiers.shift) -> PointerIcon.Crosshair
    mouseTracking && modifiers.shift -> PointerIcon.Text
    mouseTracking && shape == TerminalPointerShape.TEXT -> PointerIcon.Default
    else -> pointerIconFor(shape)
}
