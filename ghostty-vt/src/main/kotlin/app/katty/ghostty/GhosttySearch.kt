package app.katty.ghostty

import app.katty.ghostty.ffm.GhosttyPointCoordinate
import app.katty.ghostty.ffm.GhosttySelection
import app.katty.ghostty.ffm.GhosttySelectionBuffer
import app.katty.ghostty.ffm.GhosttyString
import app.katty.ghostty.ffm.GhosttyVt
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout

/** How far a search is. */
public enum class GhosttySearchStatus {
    /** Ticking gets on with it. */
    RUNNING,

    /** The search needs [GhosttySearch.feed] to go on: it is also where it is right after a needle was set. */
    FEED_REQUIRED,

    /** The pass is finished. Feeding goes on, since it is how a search learns what changed in the terminal. */
    COMPLETE,
}

/**
 * Where a match is in the viewport, both ends included. A match that goes past the top or the bottom of it is cut at the edge.
 */
public data class GhosttyViewportMatch(val startColumn: Int, val startRow: Int, val endColumn: Int, val endRow: Int)

/**
 * A search for a text in the history and the screen of a [terminal], both screens of it. It finds what is written, as ASCII letters
 * are compared without their case and every other byte as it is: there is no regular expression nor whole word.
 *
 * It is driven in two parts: [feed] reads the terminal and does a bounded amount of work, [tick] goes on with what has been read and
 * never touches the terminal. Results are only up to date as of the last feed, so a search is fed again and again while it is used.
 *
 * **Threads**: like the terminal, it is not thread-safe. Creating it, [feed], [setNeedle], [selectNext], [selectPrevious],
 * [viewportMatches], [selectedMatch] and [close] read the terminal and must be serialized with every other access to it, with the
 * same lock. [tick], [totalMatches] and [selectedIndex] only read what the search has, and may run while another thread uses the
 * terminal, but not at the same time as another call on the search.
 */
public class GhosttySearch(private val terminal: GhosttyTerminal) : AutoCloseable {
    private val handle: MemorySegment
    private var closed = false

    init {
        handle = Arena.ofConfined().use { arena ->
            val out = arena.allocate(ValueLayout.ADDRESS)
            checkResult(GhosttyVt.ghostty_search_new(MemorySegment.NULL, out, terminal.handle), "ghostty_search_new")
            out.get(ValueLayout.ADDRESS, 0)
        }
    }

    /**
     * Searches for [text], from the start: what was found is forgotten, unless it is the needle that is already there. An empty
     * text stops the search.
     */
    public fun setNeedle(text: String) {
        requireOpen()
        Arena.ofConfined().use { arena ->
            if (text.isEmpty()) {
                checkResult(GhosttyVt.ghostty_search_set(handle, GhosttyVt.GHOSTTY_SEARCH_OPT_NEEDLE(), MemorySegment.NULL), "ghostty_search_set(NEEDLE)")
                return
            }
            // Copied by the search.
            val bytes = arena.allocateFrom(text)
            val value = arena.allocate(GhosttyString.layout())
            GhosttyString.ptr(value, bytes)
            GhosttyString.len(value, bytes.byteSize() - 1)
            checkResult(GhosttyVt.ghostty_search_set(handle, GhosttyVt.GHOSTTY_SEARCH_OPT_NEEDLE(), value), "ghostty_search_set(NEEDLE)")
        }
    }

    /**
     * Selects the next match toward **older** content: from the bottom of the screen up into the history, and round to the newest once
     * past the oldest. The viewport scrolls to bring it into view. `false` when there is none.
     */
    public fun selectNext(): Boolean = select(GhosttyVt.GHOSTTY_SEARCH_OPT_SELECT_NEXT(), "SELECT_NEXT")

    /** Selects the next match toward **newer** content, and round to the oldest once past the newest. `false` when there is none. */
    public fun selectPrevious(): Boolean = select(GhosttyVt.GHOSTTY_SEARCH_OPT_SELECT_PREV(), "SELECT_PREV")

    private fun select(option: Int, name: String): Boolean {
        requireOpen()
        val result = GhosttyVt.ghostty_search_set(handle, option, MemorySegment.NULL)
        if (result == GhosttyVt.GHOSTTY_NO_VALUE()) return false
        checkResult(result, "ghostty_search_set($name)")
        return true
    }

    /** Reads the terminal and does a bounded amount of the search. */
    public fun feed() {
        requireOpen()
        checkResult(GhosttyVt.ghostty_search_feed(handle), "ghostty_search_feed")
    }

    /** Goes on with what was read, and says where the search is. */
    public fun tick(): GhosttySearchStatus {
        requireOpen()
        return Arena.ofConfined().use { arena ->
            val out = arena.allocate(ValueLayout.JAVA_INT)
            checkResult(GhosttyVt.ghostty_search_tick(handle, out), "ghostty_search_tick")
            when (out.get(ValueLayout.JAVA_INT, 0)) {
                GhosttyVt.GHOSTTY_SEARCH_STATUS_RUNNING() -> GhosttySearchStatus.RUNNING
                GhosttyVt.GHOSTTY_SEARCH_STATUS_FEED_REQUIRED() -> GhosttySearchStatus.FEED_REQUIRED
                else -> GhosttySearchStatus.COMPLETE
            }
        }
    }

    /** How many matches there are, as of the last feed. */
    public val totalMatches: Int
        get() {
            requireOpen()
            return Arena.ofConfined().use { arena ->
                val out = arena.allocate(ValueLayout.JAVA_LONG)
                checkResult(GhosttyVt.ghostty_search_get(handle, GhosttyVt.GHOSTTY_SEARCH_DATA_TOTAL_MATCHES(), out), "ghostty_search_get(TOTAL_MATCHES)")
                out.get(ValueLayout.JAVA_LONG, 0).toInt()
            }
        }

    /** Which match is selected, `0` being the **newest**, or `null` when none is. */
    public val selectedIndex: Int?
        get() {
            requireOpen()
            return Arena.ofConfined().use { arena ->
                val out = arena.allocate(ValueLayout.JAVA_LONG)
                val result = GhosttyVt.ghostty_search_get(handle, GhosttyVt.GHOSTTY_SEARCH_DATA_SELECTED_INDEX(), out)
                if (result == GhosttyVt.GHOSTTY_NO_VALUE()) return@use null
                checkResult(result, "ghostty_search_get(SELECTED_INDEX)")
                out.get(ValueLayout.JAVA_LONG, 0).toInt()
            }
        }

    /**
     * The matches that are in view, as of the last feed. It may leave out one that is on the edge. Every match is on one or more rows: a
     * match that wraps is one that goes from [GhosttyViewportMatch.startRow] to [GhosttyViewportMatch.endRow].
     */
    public fun viewportMatches(): List<GhosttyViewportMatch> {
        requireOpen()
        return Arena.ofConfined().use { arena ->
            val buffer = arena.allocate(GhosttySelectionBuffer.layout())
            // Asking how many there are: nothing fits.
            GhosttySelectionBuffer.ptr(buffer, MemorySegment.NULL)
            GhosttySelectionBuffer.cap(buffer, 0)
            val query = GhosttyVt.ghostty_search_get(handle, GhosttyVt.GHOSTTY_SEARCH_DATA_VIEWPORT_MATCHES(), buffer)
            if (query == GhosttyVt.GHOSTTY_SUCCESS() && GhosttySelectionBuffer.len(buffer) == 0L) return@use emptyList()
            if (query != GhosttyVt.GHOSTTY_OUT_OF_SPACE() && query != GhosttyVt.GHOSTTY_SUCCESS()) checkResult(query, "ghostty_search_get(VIEWPORT_MATCHES)")
            val count = GhosttySelectionBuffer.len(buffer)
            if (count == 0L) return@use emptyList()

            val size = GhosttySelection.layout().byteSize()
            val selections = arena.allocate(count * size)
            for (i in 0 until count) GhosttySelection.size(selections.asSlice(i * size, size), size)
            GhosttySelectionBuffer.ptr(buffer, selections)
            GhosttySelectionBuffer.cap(buffer, count)
            GhosttySelectionBuffer.len(buffer, 0)
            checkResult(GhosttyVt.ghostty_search_get(handle, GhosttyVt.GHOSTTY_SEARCH_DATA_VIEWPORT_MATCHES(), buffer), "ghostty_search_get(VIEWPORT_MATCHES)")

            (0 until GhosttySelectionBuffer.len(buffer)).mapNotNull { i -> toViewport(arena, selections.asSlice(i * size, size)) }
        }
    }

    /** The match that is selected, where it is in view, or `null` when none is or it is not. */
    public fun selectedMatch(): GhosttyViewportMatch? {
        requireOpen()
        return Arena.ofConfined().use { arena ->
            val selection = arena.allocate(GhosttySelection.layout())
            GhosttySelection.size(selection, GhosttySelection.layout().byteSize())
            val result = GhosttyVt.ghostty_search_get(handle, GhosttyVt.GHOSTTY_SEARCH_DATA_SELECTED_MATCH(), selection)
            if (result == GhosttyVt.GHOSTTY_NO_VALUE()) return@use null
            checkResult(result, "ghostty_search_get(SELECTED_MATCH)")
            toViewport(arena, selection)
        }
    }

    // Where a match is in the viewport. A reference that is not in it is above it or below it: the match is cut at the edge, and skipped
    // when neither end is in it.
    private fun toViewport(arena: Arena, selection: MemorySegment): GhosttyViewportMatch? {
        val start = viewportPoint(arena, GhosttySelection.start(selection))
        val end = viewportPoint(arena, GhosttySelection.end(selection))
        val rows = terminal.rows
        val cols = terminal.cols
        val first = start ?: if (end != null) 0 to 0 else return null
        val last = end ?: if (start != null) (cols - 1) to (rows - 1) else return null
        if (first.second >= rows || last.second >= rows) return null
        return GhosttyViewportMatch(first.first, first.second, last.first, last.second)
    }

    private fun viewportPoint(arena: Arena, ref: MemorySegment): Pair<Int, Int>? {
        val out = arena.allocate(GhosttyPointCoordinate.layout())
        val result = GhosttyVt.ghostty_terminal_point_from_grid_ref(terminal.handle, ref, GhosttyVt.GHOSTTY_POINT_TAG_VIEWPORT(), out)
        if (result == GhosttyVt.GHOSTTY_NO_VALUE()) return null
        checkResult(result, "ghostty_terminal_point_from_grid_ref")
        return (GhosttyPointCoordinate.x(out).toInt() and 0xFFFF) to GhosttyPointCoordinate.y(out)
    }

    private fun requireOpen() = check(!closed) { "The search is closed" }

    override fun close() {
        if (closed) return
        closed = true
        GhosttyVt.ghostty_search_free(handle)
    }
}
