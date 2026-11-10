package app.katty.terminal

import app.katty.ghostty.GhosttyTextFormat

/** How text is written: see [TerminalSession.selectedText] and [TerminalSession.history]. */
public enum class TerminalTextFormat {
    /** The characters only. */
    PLAIN,

    /** The characters with the escape sequences that give them their styles and colors. */
    VT,

    /** HTML, with the styles and colors of the cells. */
    HTML,
}

/**
 * Everything that a terminal keeps, as [TerminalSession.history] gives it.
 *
 * @param text Its history and its screen, in the format that was asked for. In HTML, a fragment that defines the colors of the palette and that
 * the host puts in a document.
 * @param foreground The color of its text by default then, `null` when it has none.
 * @param background The color of its background by default then, `null` when it has none.
 */
public data class TerminalHistory(
    public val text: String,
    public val foreground: TerminalColor?,
    public val background: TerminalColor?,
)

internal fun TerminalTextFormat.toGhostty(): GhosttyTextFormat = when (this) {
    TerminalTextFormat.PLAIN -> GhosttyTextFormat.PLAIN
    TerminalTextFormat.VT -> GhosttyTextFormat.VT
    TerminalTextFormat.HTML -> GhosttyTextFormat.HTML
}
