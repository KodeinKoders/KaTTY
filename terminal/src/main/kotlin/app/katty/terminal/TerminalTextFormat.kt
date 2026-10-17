package app.katty.terminal

import app.katty.ghostty.GhosttyTextFormat

/** How the text of what is selected is written: see [TerminalSession.selectedText]. */
public enum class TerminalTextFormat {
    /** The characters only. */
    PLAIN,

    /** The characters with the escape sequences that give them their styles and colors. */
    VT,

    /** HTML, with the styles and colors of the cells. */
    HTML,
}

internal fun TerminalTextFormat.toGhostty(): GhosttyTextFormat = when (this) {
    TerminalTextFormat.PLAIN -> GhosttyTextFormat.PLAIN
    TerminalTextFormat.VT -> GhosttyTextFormat.VT
    TerminalTextFormat.HTML -> GhosttyTextFormat.HTML
}
