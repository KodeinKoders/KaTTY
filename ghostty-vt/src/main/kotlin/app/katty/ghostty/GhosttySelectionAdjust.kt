package app.katty.ghostty

/**
 * How the end of a selection that was last moved is moved by [GhosttyTerminal.adjustSelection]. The order is the library's: the ordinal is
 * the value that it is given.
 */
public enum class GhosttySelectionAdjust {
    /** To the previous cell that has text, wrapping upward. */
    LEFT,

    /** To the next cell that has text, wrapping downward. */
    RIGHT,

    /** One row up, in the same column, or to the start of the line at the top. */
    UP,

    /** To the next row that has text in the same column, or to the end of the line when there is none. */
    DOWN,

    /** To the top left cell of the screen. */
    HOME,

    /** To the right edge of the last row that has text. */
    END,

    /** Up by the height of the screen, or to the top. */
    PAGE_UP,

    /** Down by the height of the screen, or to the bottom. */
    PAGE_DOWN,

    /** To the left edge of the line. */
    BEGINNING_OF_LINE,

    /** To the right edge of the line. */
    END_OF_LINE,
}
