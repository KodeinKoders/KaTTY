package app.katty.terminal

/**
 * The colors of a terminal.
 *
 * A theme only has the 16 colors that color schemes define: the rest of the 256-color palette is
 * generated from them when the theme is applied.
 */
public data class TerminalTheme(
    public val background: TerminalColor,
    public val foreground: TerminalColor,
    /** The 16 colors of the terminal: the 8 normal ones, then the 8 bright ones. */
    public val ansi: List<TerminalColor>,
    /** `null` to draw the cursor in the foreground. */
    public val cursor: TerminalColor? = null,
    /** The color of the character under a block cursor, `null` for the background. */
    public val cursorText: TerminalColor? = null,
    /** The color of bold text that has the default foreground, `null` for the foreground. */
    public val bold: TerminalColor? = null,
    /** The background of what is selected. */
    public val selection: TerminalColor? = null,
    /** The color of what is selected, `null` to keep the text's own. */
    public val selectedText: TerminalColor? = null,
) {
    init {
        require(ansi.size == ANSI_COLORS) { "A theme has $ANSI_COLORS ANSI colors, not ${ansi.size}" }
    }

    public companion object {
        public const val ANSI_COLORS: Int = 16
    }
}
