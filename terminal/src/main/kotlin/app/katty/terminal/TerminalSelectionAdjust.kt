package app.katty.terminal

import app.katty.ghostty.GhosttySelectionAdjust

/** How the keys extend what is selected: see [TerminalSession.adjustSelection]. The end that moves is the one that a drag moves. */
public enum class TerminalSelectionAdjust {
    /** One cell to the left, wrapping upward. */
    LEFT,

    /** One cell to the right, wrapping downward. */
    RIGHT,

    /** One row up. */
    UP,

    /** One row down. */
    DOWN,

    /** To the top left of the screen. */
    HOME,

    /** To the end of the text of the screen. */
    END,

    /** Up by a screen. */
    PAGE_UP,

    /** Down by a screen. */
    PAGE_DOWN,

    /** To the left edge of the line. */
    BEGINNING_OF_LINE,

    /** To the right edge of the line. */
    END_OF_LINE,
}

internal fun TerminalSelectionAdjust.toGhostty(): GhosttySelectionAdjust = GhosttySelectionAdjust.entries[ordinal]
