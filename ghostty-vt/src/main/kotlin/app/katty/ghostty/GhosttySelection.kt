package app.katty.ghostty

import app.katty.ghostty.ffm.GhosttyGridRef
import app.katty.ghostty.ffm.GhosttyPointCoordinate
import app.katty.ghostty.ffm.GhosttySelection
import app.katty.ghostty.ffm.GhosttySelectionGestureGeometry
import app.katty.ghostty.ffm.GhosttySurfacePosition
import app.katty.ghostty.ffm.GhosttyVt
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout

/**
 * The state of a text selection that the user makes with the mouse: a press that starts it (a double one selects
 * a word, a triple one a line), a drag that extends it, a release that ends it. The selection is installed in the
 * terminal, which shows it in its render state and keeps it as the content moves.
 *
 * Each call takes a cell and a position in pixels, which the gesture uses to tell a click from a drag and a
 * repeated click from a new one. Not thread-safe: serialize it with [terminal].
 */
public class GhosttySelectionGesture(private val terminal: GhosttyTerminal) : AutoCloseable {
    private val gesture: MemorySegment
    private val pressEvent: MemorySegment
    private val dragEvent: MemorySegment
    private val releaseEvent: MemorySegment
    private val tickEvent: MemorySegment

    private var closed = false

    init {
        NativeLibrary.ensureLoaded()
        gesture = newHandle("a selection gesture") { GhosttyVt.ghostty_selection_gesture_new(MemorySegment.NULL, it) }
        val events = ArrayList<MemorySegment>()
        try {
            pressEvent = newEvent(GhosttyVt.GHOSTTY_SELECTION_GESTURE_EVENT_TYPE_PRESS()).also { events += it }
            dragEvent = newEvent(GhosttyVt.GHOSTTY_SELECTION_GESTURE_EVENT_TYPE_DRAG()).also { events += it }
            releaseEvent = newEvent(GhosttyVt.GHOSTTY_SELECTION_GESTURE_EVENT_TYPE_RELEASE()).also { events += it }
            tickEvent = newEvent(GhosttyVt.GHOSTTY_SELECTION_GESTURE_EVENT_TYPE_AUTOSCROLL_TICK()).also { events += it }
        } catch (e: Throwable) {
            events.forEach { GhosttyVt.ghostty_selection_gesture_event_free(it) }
            GhosttyVt.ghostty_selection_gesture_free(gesture, terminal.handle)
            throw e
        }
    }

    /**
     * A button goes down on the cell at [column] and [row] (from the top left of the screen, in the viewport),
     * at [xPx] and [yPx] pixels.
     *
     * A single click selects nothing yet: it is what a drag starts from. A double click selects the word under
     * the pointer, and a triple click the line. Clicks are told from one another by their [timeNs] (a monotonic
     * clock), their distance ([repeatDistancePx]) and the time between them ([repeatIntervalNs]).
     *
     * @return Whether there is a selection now, installed in the terminal. `false` means that nothing is
     * selected by this press: the caller clears the selection that was there.
     */
    public fun press(
        column: Int,
        row: Int,
        xPx: Double,
        yPx: Double,
        timeNs: Long,
        repeatDistancePx: Double,
        repeatIntervalNs: Long,
    ): Boolean {
        requireOpen()
        return Arena.ofConfined().use { arena ->
            setRef(arena, pressEvent, column, row)
            setPosition(arena, pressEvent, xPx, yPx)
            setLong(arena, pressEvent, GhosttyVt.GHOSTTY_SELECTION_GESTURE_EVENT_OPT_TIME_NS(), timeNs)
            setLong(arena, pressEvent, GhosttyVt.GHOSTTY_SELECTION_GESTURE_EVENT_OPT_REPEAT_INTERVAL_NS(), repeatIntervalNs)
            val distance = arena.allocate(ValueLayout.JAVA_DOUBLE)
            distance.set(ValueLayout.JAVA_DOUBLE, 0, repeatDistancePx)
            checkResult(
                GhosttyVt.ghostty_selection_gesture_event_set(pressEvent, GhosttyVt.GHOSTTY_SELECTION_GESTURE_EVENT_OPT_REPEAT_DISTANCE(), distance),
                "ghostty_selection_gesture_event_set(REPEAT_DISTANCE)",
            )
            apply(arena, pressEvent)
        }
    }

    /**
     * The pointer moves with the button held, to the cell at [column] and [row].
     *
     * @param rectangle Whether to select a block of cells instead of a flow of text.
     * @param cols The width of the screen in cells, and [cellWidthPx] and [screenHeightPx] what the geometry of
     * the screen in pixels is.
     * @return Whether there is a selection now, installed in the terminal.
     */
    public fun drag(
        column: Int,
        row: Int,
        xPx: Double,
        yPx: Double,
        cols: Int,
        cellWidthPx: Int,
        screenHeightPx: Int,
        rectangle: Boolean,
    ): Boolean {
        requireOpen()
        return Arena.ofConfined().use { arena ->
            setRef(arena, dragEvent, column, row)
            setPosition(arena, dragEvent, xPx, yPx)
            setGeometry(arena, dragEvent, cols, cellWidthPx, screenHeightPx)
            setRectangle(arena, dragEvent, rectangle)
            apply(arena, dragEvent)
        }
    }

    /**
     * Which way the drag that is in progress asks to scroll, which is when the pointer is past the top or the bottom of
     * the screen. Read it after [drag], and call [tick] every so often while it is not [GhosttyAutoscroll.NONE].
     */
    public fun autoscroll(): GhosttyAutoscroll {
        requireOpen()
        return Arena.ofConfined().use { arena ->
            val out = arena.allocate(ValueLayout.JAVA_INT)
            val result = GhosttyVt.ghostty_selection_gesture_get(gesture, terminal.handle, GhosttyVt.GHOSTTY_SELECTION_GESTURE_DATA_AUTOSCROLL(), out)
            if (result == GhosttyVt.GHOSTTY_NO_VALUE()) return@use GhosttyAutoscroll.NONE
            checkResult(result, "ghostty_selection_gesture_get(AUTOSCROLL)")
            when (out.get(ValueLayout.JAVA_INT, 0)) {
                GhosttyVt.GHOSTTY_SELECTION_GESTURE_AUTOSCROLL_UP() -> GhosttyAutoscroll.UP
                GhosttyVt.GHOSTTY_SELECTION_GESTURE_AUTOSCROLL_DOWN() -> GhosttyAutoscroll.DOWN
                else -> GhosttyAutoscroll.NONE
            }
        }
    }

    /**
     * One step of the autoscroll, while the pointer stays at [xPx] and [yPx], past an edge: **the viewport is scrolled by
     * a row** and the selection is extended to the edge row of the viewport, at [column].
     *
     * @param edgeRow The row of the viewport at the edge that the pointer is past: the first or the last.
     * @return Whether there is a selection now, installed in the terminal.
     */
    public fun tick(
        column: Int,
        edgeRow: Int,
        xPx: Double,
        yPx: Double,
        cols: Int,
        cellWidthPx: Int,
        screenHeightPx: Int,
        rectangle: Boolean,
    ): Boolean {
        requireOpen()
        return Arena.ofConfined().use { arena ->
            val coordinate = arena.allocate(GhosttyPointCoordinate.layout())
            GhosttyPointCoordinate.x(coordinate, column.toShort())
            GhosttyPointCoordinate.y(coordinate, edgeRow)
            checkResult(
                GhosttyVt.ghostty_selection_gesture_event_set(tickEvent, GhosttyVt.GHOSTTY_SELECTION_GESTURE_EVENT_OPT_VIEWPORT(), coordinate),
                "ghostty_selection_gesture_event_set(VIEWPORT)",
            )
            setPosition(arena, tickEvent, xPx, yPx)
            setGeometry(arena, tickEvent, cols, cellWidthPx, screenHeightPx)
            setRectangle(arena, tickEvent, rectangle)
            apply(arena, tickEvent)
        }
    }

    /** The button goes up, on the cell at [column] and [row]. It leaves the selection as it is. */
    public fun release(column: Int, row: Int) {
        requireOpen()
        Arena.ofConfined().use { arena ->
            setRef(arena, releaseEvent, column, row)
            val result = GhosttyVt.ghostty_selection_gesture_event(gesture, terminal.handle, releaseEvent, MemorySegment.NULL)
            // A release never produces a selection.
            if (result != GhosttyVt.GHOSTTY_NO_VALUE()) checkResult(result, "ghostty_selection_gesture_event(RELEASE)")
        }
    }

    /** Forgets the click that is in progress, and the clicks that it could repeat. */
    public fun reset() {
        requireOpen()
        GhosttyVt.ghostty_selection_gesture_reset(gesture, terminal.handle)
    }

    /** Releases the gesture: it is to be closed before the terminal it works on. */
    override fun close() {
        if (closed) return
        closed = true
        GhosttyVt.ghostty_selection_gesture_event_free(pressEvent)
        GhosttyVt.ghostty_selection_gesture_event_free(dragEvent)
        GhosttyVt.ghostty_selection_gesture_event_free(releaseEvent)
        GhosttyVt.ghostty_selection_gesture_event_free(tickEvent)
        // The tracked references of the gesture are the terminal's: once it is gone there is nothing to release.
        GhosttyVt.ghostty_selection_gesture_free(gesture, if (terminal.isClosed) MemorySegment.NULL else terminal.handle)
    }

    private fun apply(arena: Arena, event: MemorySegment): Boolean {
        val selection = arena.allocate(GhosttySelection.layout())
        GhosttySelection.size(selection, GhosttySelection.layout().byteSize())
        val result = GhosttyVt.ghostty_selection_gesture_event(gesture, terminal.handle, event, selection)
        if (result == GhosttyVt.GHOSTTY_NO_VALUE()) return false
        checkResult(result, "ghostty_selection_gesture_event")
        terminal.installSelection(selection)
        return true
    }

    private fun setRef(arena: Arena, event: MemorySegment, column: Int, row: Int) {
        val ref = terminal.gridRef(arena, column, row)
        checkResult(
            GhosttyVt.ghostty_selection_gesture_event_set(event, GhosttyVt.GHOSTTY_SELECTION_GESTURE_EVENT_OPT_REF(), ref),
            "ghostty_selection_gesture_event_set(REF)",
        )
    }

    private fun setPosition(arena: Arena, event: MemorySegment, xPx: Double, yPx: Double) {
        val position = arena.allocate(GhosttySurfacePosition.layout())
        GhosttySurfacePosition.x(position, xPx)
        GhosttySurfacePosition.y(position, yPx)
        checkResult(
            GhosttyVt.ghostty_selection_gesture_event_set(event, GhosttyVt.GHOSTTY_SELECTION_GESTURE_EVENT_OPT_POSITION(), position),
            "ghostty_selection_gesture_event_set(POSITION)",
        )
    }

    private fun setGeometry(arena: Arena, event: MemorySegment, cols: Int, cellWidthPx: Int, screenHeightPx: Int) {
        val geometry = arena.allocate(GhosttySelectionGestureGeometry.layout())
        GhosttySelectionGestureGeometry.columns(geometry, cols)
        GhosttySelectionGestureGeometry.cell_width(geometry, cellWidthPx)
        GhosttySelectionGestureGeometry.padding_left(geometry, 0)
        GhosttySelectionGestureGeometry.screen_height(geometry, screenHeightPx)
        checkResult(
            GhosttyVt.ghostty_selection_gesture_event_set(event, GhosttyVt.GHOSTTY_SELECTION_GESTURE_EVENT_OPT_GEOMETRY(), geometry),
            "ghostty_selection_gesture_event_set(GEOMETRY)",
        )
    }

    private fun setRectangle(arena: Arena, event: MemorySegment, rectangle: Boolean) {
        val flag = arena.allocate(ValueLayout.JAVA_BOOLEAN)
        flag.set(ValueLayout.JAVA_BOOLEAN, 0, rectangle)
        checkResult(
            GhosttyVt.ghostty_selection_gesture_event_set(event, GhosttyVt.GHOSTTY_SELECTION_GESTURE_EVENT_OPT_RECTANGLE(), flag),
            "ghostty_selection_gesture_event_set(RECTANGLE)",
        )
    }

    private fun setLong(arena: Arena, event: MemorySegment, option: Int, value: Long) {
        val segment = arena.allocate(ValueLayout.JAVA_LONG)
        segment.set(ValueLayout.JAVA_LONG, 0, value)
        checkResult(GhosttyVt.ghostty_selection_gesture_event_set(event, option, segment), "ghostty_selection_gesture_event_set($option)")
    }

    private fun requireOpen() {
        check(!closed) { "The selection gesture is closed" }
        terminal.requireOpen()
    }

    private fun newHandle(what: String, create: (MemorySegment) -> Int): MemorySegment =
        Arena.ofConfined().use { arena ->
            val out = arena.allocate(ValueLayout.ADDRESS)
            checkResult(create(out), "creating $what")
            out.get(ValueLayout.ADDRESS, 0)
        }

    private fun newEvent(type: Int): MemorySegment =
        newHandle("a selection gesture event") { GhosttyVt.ghostty_selection_gesture_event_new(MemorySegment.NULL, it, type) }
}
