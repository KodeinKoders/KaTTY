package app.katty.ghostty

/**
 * Where to move the viewport, which shows a part of the history and of the screen.
 */
public sealed interface GhosttyScrollViewport {
    /** The top of the history. */
    public data object Top : GhosttyScrollViewport

    /** The bottom, where the screen is. */
    public data object Bottom : GhosttyScrollViewport

    /** By [rows] rows: up is negative. */
    public data class Delta(val rows: Long) : GhosttyScrollViewport

    /** To the row [row] of the history and the screen, the first row of the viewport, counted from 0 at the top of the history. */
    public data class Row(val row: Long) : GhosttyScrollViewport
}

/**
 * Where the viewport is in what can be scrolled, in rows: what a scrollbar draws.
 *
 * @param total The rows of the history and of the screen.
 * @param offset The row at the top of the viewport, from 0 at the top of the history: [Row][GhosttyScrollViewport.Row] takes it.
 * @param len The rows of the viewport.
 */
public data class GhosttyScrollbar(val total: Long, val offset: Long, val len: Long)

public enum class GhosttyScreen {
    PRIMARY,
    ALTERNATE,
}

/** Which way a drag that selects asks to scroll, which is when it is past an edge. */
public enum class GhosttyAutoscroll {
    NONE,
    UP,
    DOWN,
}
