package app.katty.terminal.compose

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.katty.terminal.TerminalAutoscroll
import app.katty.terminal.TerminalMouseButton
import app.katty.terminal.TerminalScrollbar
import app.katty.terminal.TerminalSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.awt.event.KeyEvent as AwtKeyEvent
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** How many rows a notch of the wheel scrolls. */
internal const val ROWS_PER_NOTCH = 3

/**
 * Turns the scrolls of the wheel into whole rows. A trackpad gives fractions of a notch, which are added up until they
 * make a row, and the direction can change in between. Down is positive.
 *
 * Not thread-safe: it is used from the UI thread.
 */
internal class WheelScroller {
    private var remainder = 0f

    /**
     * @param notches The scroll of an event, from AWT: down is positive.
     * @param maxRows The most that is scrolled at once, a screen: what is above it is dropped, so that a spin does not
     * go on after it stopped.
     */
    fun add(notches: Float, maxRows: Int): Int {
        remainder += notches * ROWS_PER_NOTCH
        val whole = remainder.toInt()
        remainder -= whole
        return whole.coerceIn(-maxRows, maxRows)
    }

    fun reset() {
        remainder = 0f
    }
}

/**
 * Where the thumb of the scrollbar is on a track of [trackHeight] pixels, and the row that a position of it is.
 *
 * @param minThumbHeight The thumb is never shorter, however much history there is.
 */
internal class ScrollbarGeometry(
    val trackHeight: Float,
    private val scrollbar: TerminalScrollbar,
    minThumbHeight: Float,
) {
    // The rows that the viewport can move over.
    private val scrollableRows = max(0L, scrollbar.total - scrollbar.len)

    val thumbHeight: Float = (trackHeight * scrollbar.len / max(1L, scrollbar.total)).coerceIn(minOf(minThumbHeight, trackHeight), trackHeight)

    /** The distance that the thumb can move. */
    private val travel = trackHeight - thumbHeight

    val thumbTop: Float = if (scrollableRows == 0L) 0f else (travel * scrollbar.offset / scrollableRows).coerceIn(0f, travel)

    /** The row at the top of the viewport for a thumb whose top is at [thumbTop]. */
    fun rowAt(thumbTop: Float): Long =
        if (travel <= 0f) 0L else ((thumbTop / travel).coerceIn(0f, 1f) * scrollableRows).roundToInt().toLong()
}

/**
 * What the user does to the scrollbar with the pointer: drags its thumb, or presses the track to put the thumb
 * there. It is in the strip along the right edge of the terminal, which the program is told nothing about.
 *
 * Not thread-safe: it is used from the UI thread.
 */
internal class ScrollbarInteraction {
    /** Whether the pointer is over the strip: the scrollbar then shows. */
    var hovered: Boolean by mutableStateOf(false)
        private set

    var dragging: Boolean by mutableStateOf(false)
        private set

    // Where in the thumb it was grabbed.
    private var grab = 0f

    /** The pointer left the terminal. */
    fun exit() {
        if (!dragging) hovered = false
    }

    /**
     * @return Whether the event is the scrollbar's: it is then not for the terminal.
     */
    fun handle(event: PointerInfo, geometry: ScrollbarGeometry, width: Float, stripWidth: Float, scrollToRow: (Long) -> Unit): Boolean {
        val inStrip = event.x >= width - stripWidth
        when (event.kind) {
            PointerInfo.Kind.PRESS -> {
                if (!inStrip) return false
                if (event.button != TerminalMouseButton.LEFT) return true
                if (event.y in geometry.thumbTop..(geometry.thumbTop + geometry.thumbHeight)) {
                    grab = event.y - geometry.thumbTop
                } else {
                    // A press on the track puts the middle of the thumb there.
                    grab = geometry.thumbHeight / 2
                    scrollToRow(geometry.rowAt(event.y - grab))
                }
                dragging = true
                hovered = true
                return true
            }

            PointerInfo.Kind.MOVE -> {
                if (dragging) {
                    // A release that was not seen, outside the window for instance: the drag is over.
                    if (TerminalMouseButton.LEFT !in event.held) {
                        dragging = false
                        hovered = inStrip
                        return inStrip
                    }
                    scrollToRow(geometry.rowAt(event.y - grab))
                    return true
                }
                hovered = inStrip
                return inStrip
            }

            PointerInfo.Kind.RELEASE -> {
                if (!dragging) return false
                dragging = false
                hovered = inStrip
                return true
            }

            PointerInfo.Kind.SCROLL -> return false
        }
    }
}

/** The AWT key codes that scroll the history with Shift. */
internal fun historyKeyCode(keyCode: Int): Boolean =
    keyCode == AwtKeyEvent.VK_PAGE_UP || keyCode == AwtKeyEvent.VK_PAGE_DOWN || keyCode == AwtKeyEvent.VK_HOME || keyCode == AwtKeyEvent.VK_END

/**
 * Whether a key is one that scrolls the history (Shift and PageUp, PageDown, Home or End, and nothing else held): it
 * is for the terminal, and the program is told nothing of it, its release included.
 */
internal fun isHistoryKey(key: KeyInfo): Boolean =
    key.kind != KeyInfo.Kind.TYPED && historyKeyCode(key.keyCode) && key.shift && !key.ctrl && !key.alt && !key.meta


internal const val SCROLLBAR_VISIBLE_MS = 1000L
internal const val SCROLLBAR_FADE_MS = 250

/**
 * What a drag that selects does past the top or the bottom of the screen: the history scrolls by a row every so often,
 * with the selection, for as long as the pointer stays out, whether it moves or not.
 */
internal class SelectionAutoscroll(private val scope: CoroutineScope, private val session: TerminalSession) {
    private var job: Job? = null

    // Where the pointer last was, and whether it selects a block: what the next tick extends the selection with.
    private var column = 0f
    private var row = 0f
    private var rectangle = false

    /** The pointer is at [column] and [row] with the button held. */
    fun drag(column: Float, row: Float, rectangle: Boolean, onScrolled: () -> Unit) {
        this.column = column
        this.row = row
        this.rectangle = rectangle
        val direction = session.selectionDrag(column, row, rectangle)
        if (direction == TerminalAutoscroll.NONE) {
            stop()
        } else if (job == null) {
            job = scope.launch {
                while (true) {
                    delay(INTERVAL_MS)
                    if (session.selectionAutoscroll(this@SelectionAutoscroll.column, this@SelectionAutoscroll.row, this@SelectionAutoscroll.rectangle) == TerminalAutoscroll.NONE) break
                    onScrolled()
                }
                job = null
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private companion object {
        const val INTERVAL_MS = 50L
    }
}
