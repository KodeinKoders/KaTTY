package app.katty.terminal.compose

import androidx.compose.ui.input.pointer.PointerIcon
import app.katty.terminal.MouseInput
import app.katty.terminal.TerminalModifier
import app.katty.terminal.TerminalMouseAction
import app.katty.terminal.TerminalMouseButton
import app.katty.terminal.TerminalPointerShape
import java.awt.Cursor
import kotlin.math.abs

/** What matters of a pointer event, as Compose reports it and AWT gave it. */
internal class PointerInfo(
    val kind: Kind,
    /** Pixels, from the top left of the terminal. */
    val x: Float,
    val y: Float,
    /** The button that was pressed or released: only for those two. */
    val button: TerminalMouseButton? = null,
    /** The buttons that are held, once the event is applied. */
    val held: Set<TerminalMouseButton> = emptySet(),
    val shift: Boolean = false,
    val ctrl: Boolean = false,
    val alt: Boolean = false,
    val meta: Boolean = false,
    /** For a scroll, in notches: positive is down and right. A trackpad gives fractions of one. */
    val scrollX: Float = 0f,
    val scrollY: Float = 0f,
) {
    enum class Kind { PRESS, RELEASE, MOVE, SCROLL }
}

/**
 * Turns the pointer events of Compose into what is sent to the program: it is the state of a gesture.
 *
 * - A press is sent unless Shift is held, which is for selecting and not for the program ([SelectionDriver] takes
 *   the left button then): such a press is swallowed, and so are its drag and its release. A press that was sent
 *   has its release sent, whatever was done to Shift in between, or the program would think that the button is still
 *   down.
 * - The wheel is a button that is pressed once for each notch. A trackpad gives fractions of one, which are added
 *   up until they make one. Shift is not a bypass for it: it is how AWT says that the scroll is horizontal.
 *
 * Not thread-safe: it is used from the UI thread.
 */
internal class MouseTranslator {
    // The buttons whose press was sent, and the ones whose press was swallowed.
    private val sent = HashSet<TerminalMouseButton>()
    private val swallowed = HashSet<TerminalMouseButton>()

    private var remainderX = 0f
    private var remainderY = 0f

    // Where the pointer was at its last event, in cells, and with which modifiers: where the buttons that are still down go up.
    private var lastColumn = 0f
    private var lastRow = 0f

    /**
     * The releases of the buttons whose press was sent and that are still down, where the pointer was last: the program will not see them go up
     * (its terminal is not shown any more). What is down is forgotten.
     */
    fun releaseAll(): List<MouseInput> {
        val releases = sent.map { MouseInput(TerminalMouseAction.RELEASE, it, lastColumn, lastRow, emptySet(), anyButtonPressed = false) }
        reset()
        return releases
    }

    fun reset() {
        sent.clear()
        swallowed.clear()
        remainderX = 0f
        remainderY = 0f
    }

    fun translate(event: PointerInfo, cellWidth: Float, cellHeight: Float): List<MouseInput> {
        val column = event.x / cellWidth
        val row = event.y / cellHeight
        lastColumn = column
        lastRow = row
        // Shift is never told to the program for a button: it either swallowed the event, or only came after the press.
        val mods = buildSet {
            if (event.ctrl) add(TerminalModifier.CTRL)
            if (event.alt) add(TerminalModifier.ALT)
            if (event.meta) add(TerminalModifier.SUPER)
        }

        return when (event.kind) {
            PointerInfo.Kind.PRESS -> {
                val button = event.button ?: return emptyList()
                if (event.shift) {
                    swallowed += button
                    emptyList()
                } else {
                    sent += button
                    listOf(MouseInput(TerminalMouseAction.PRESS, button, column, row, mods, anyButtonPressed = true))
                }
            }

            PointerInfo.Kind.RELEASE -> {
                val button = event.button ?: return emptyList()
                if (swallowed.remove(button)) return emptyList()
                if (!sent.remove(button)) return emptyList()
                listOf(MouseInput(TerminalMouseAction.RELEASE, button, column, row, mods, anyButtonPressed = event.held.isNotEmpty()))
            }

            PointerInfo.Kind.MOVE -> {
                // A drag of a swallowed press, and a hover with Shift held, are for the selection.
                if (swallowed.isNotEmpty() || (event.shift && event.held.isEmpty())) return emptyList()
                val button = DRAG_BUTTONS.firstOrNull { it in event.held }
                listOf(MouseInput(TerminalMouseAction.MOTION, button, column, row, mods, anyButtonPressed = button != null))
            }

            PointerInfo.Kind.SCROLL -> {
                remainderX += event.scrollX
                remainderY += event.scrollY
                val vertical = abs(remainderY).toInt()
                val horizontal = abs(remainderX).toInt()
                val down = remainderY > 0
                val right = remainderX > 0
                // The notches are used up even when there are too many to send: a spin does not go on after it stopped.
                remainderY -= vertical * (if (down) 1 else -1)
                remainderX -= horizontal * (if (right) 1 else -1)
                buildList {
                    repeat(minOf(vertical, MAX_NOTCHES_PER_EVENT)) {
                        add(wheel(if (down) TerminalMouseButton.WHEEL_DOWN else TerminalMouseButton.WHEEL_UP, column, row, mods, event))
                    }
                    repeat(minOf(horizontal, MAX_NOTCHES_PER_EVENT)) {
                        add(wheel(if (right) TerminalMouseButton.WHEEL_RIGHT else TerminalMouseButton.WHEEL_LEFT, column, row, mods, event))
                    }
                }
            }
        }
    }

    private fun wheel(button: TerminalMouseButton, column: Float, row: Float, mods: Set<TerminalModifier>, event: PointerInfo) =
        MouseInput(TerminalMouseAction.PRESS, button, column, row, mods, anyButtonPressed = event.held.isNotEmpty())

    private companion object {
        const val MAX_NOTCHES_PER_EVENT = 10

        // The button that a drag is told for, if several are held.
        val DRAG_BUTTONS = listOf(TerminalMouseButton.LEFT, TerminalMouseButton.RIGHT, TerminalMouseButton.MIDDLE)
    }
}

/**
 * The pointer that matches a shape that the program asked for. AWT only has so many: the others get the nearest.
 */
internal fun pointerIconFor(shape: TerminalPointerShape): PointerIcon = when (shape) {
    TerminalPointerShape.TEXT, TerminalPointerShape.VERTICAL_TEXT -> PointerIcon.Text
    TerminalPointerShape.POINTER, TerminalPointerShape.ALIAS, TerminalPointerShape.COPY, TerminalPointerShape.GRAB -> PointerIcon.Hand
    TerminalPointerShape.CROSSHAIR, TerminalPointerShape.CELL -> PointerIcon.Crosshair
    TerminalPointerShape.WAIT, TerminalPointerShape.PROGRESS -> awt(Cursor.WAIT_CURSOR)
    TerminalPointerShape.MOVE, TerminalPointerShape.ALL_SCROLL, TerminalPointerShape.GRABBING -> awt(Cursor.MOVE_CURSOR)
    TerminalPointerShape.N_RESIZE, TerminalPointerShape.ROW_RESIZE, TerminalPointerShape.NS_RESIZE -> awt(Cursor.N_RESIZE_CURSOR)
    TerminalPointerShape.S_RESIZE -> awt(Cursor.S_RESIZE_CURSOR)
    TerminalPointerShape.E_RESIZE, TerminalPointerShape.COL_RESIZE, TerminalPointerShape.EW_RESIZE -> awt(Cursor.E_RESIZE_CURSOR)
    TerminalPointerShape.W_RESIZE -> awt(Cursor.W_RESIZE_CURSOR)
    TerminalPointerShape.NE_RESIZE, TerminalPointerShape.NESW_RESIZE -> awt(Cursor.NE_RESIZE_CURSOR)
    TerminalPointerShape.NW_RESIZE, TerminalPointerShape.NWSE_RESIZE -> awt(Cursor.NW_RESIZE_CURSOR)
    TerminalPointerShape.SE_RESIZE -> awt(Cursor.SE_RESIZE_CURSOR)
    TerminalPointerShape.SW_RESIZE -> awt(Cursor.SW_RESIZE_CURSOR)
    // No cursor of AWT: the arrow.
    TerminalPointerShape.DEFAULT, TerminalPointerShape.CONTEXT_MENU, TerminalPointerShape.HELP, TerminalPointerShape.NO_DROP,
    TerminalPointerShape.NOT_ALLOWED, TerminalPointerShape.ZOOM_IN, TerminalPointerShape.ZOOM_OUT,
    -> PointerIcon.Default
}

private fun awt(type: Int): PointerIcon = PointerIcon(Cursor.getPredefinedCursor(type))
