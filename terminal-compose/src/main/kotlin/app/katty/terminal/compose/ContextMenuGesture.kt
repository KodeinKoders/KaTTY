package app.katty.terminal.compose

import app.katty.terminal.TerminalMouseButton

/**
 * Tells when a press of the right button is the host's: it is not for the program, which gets it only when it asked for the mouse and
 * Shift is not held (Shift is how a user keeps the terminal's own behavior, as it does for selecting).
 *
 * The release of a press that was taken is taken too: the program was told of neither. Not thread-safe: it is used from the UI thread.
 */
internal class ContextMenuGesture {
    private var down = false

    /**
     * @return Whether the event is the host's, in which case [open] was called for a press and the event is to be consumed.
     */
    fun handle(event: PointerInfo, tracking: Boolean, open: (x: Float, y: Float) -> Unit): Boolean {
        if (event.button != TerminalMouseButton.RIGHT) return false
        return when (event.kind) {
            PointerInfo.Kind.PRESS -> {
                if (tracking && !event.shift) return false
                down = true
                open(event.x, event.y)
                true
            }
            PointerInfo.Kind.RELEASE -> down.also { down = false }
            else -> false
        }
    }
}
