package app.katty.terminal

/**
 * An RGB color.
 */
@JvmInline
public value class TerminalColor(
    /** `0xRRGGBB`. */
    public val rgb: Int,
) {
    public val r: Int get() = rgb shr 16 and 0xFF
    public val g: Int get() = rgb shr 8 and 0xFF
    public val b: Int get() = rgb and 0xFF

    override fun toString(): String = "#%06x".format(rgb)
}

public enum class TerminalUnderline {
    NONE,
    SINGLE,
    DOUBLE,
    CURLY,
    DOTTED,
    DASHED,
}

public data class TerminalStyle(
    public val bold: Boolean = false,
    public val italic: Boolean = false,
    public val faint: Boolean = false,
    public val blink: Boolean = false,
    public val inverse: Boolean = false,
    public val invisible: Boolean = false,
    public val strikethrough: Boolean = false,
    public val overline: Boolean = false,
    public val underline: TerminalUnderline = TerminalUnderline.NONE,
) {
    public companion object {
        /** No attribute at all, which is most cells. */
        public val NONE: TerminalStyle = TerminalStyle()
    }
}

public enum class TerminalCellWidth {
    NARROW,

    /** The left half of a wide character: the next cell is its [SPACER_TAIL]. */
    WIDE,

    /** The right half of a wide character, which draws nothing. */
    SPACER_TAIL,

    /** The end of a line that a wide character did not fit in, which draws nothing. */
    SPACER_HEAD,
}

public data class TerminalCell(
    /** The grapheme cluster, or an empty string for a cell without text. */
    public val text: String,
    /** `null` for the default foreground. Bold is not applied to it. */
    public val foreground: TerminalColor?,
    /** `null` for the default background. */
    public val background: TerminalColor?,
    public val style: TerminalStyle,
    public val width: TerminalCellWidth,
)

/**
 * The identity of a row, which follows it when it moves, for instance when scrolling. An id is never
 * reused.
 */
public data class TerminalRowId(
    public val bits0: Long,
    public val bits1: Long,
)

/**
 * A row of the screen.
 *
 * A row that did not change from one frame to the next is **the same object** in both, so a renderer can
 * keep what it prepared for a row (its text layout, for instance) in a cache keyed by the row itself,
 * or by its [id]. Equality is by identity, on purpose: comparing the cells of a whole screen would defeat
 * the point.
 */
public class TerminalRow internal constructor(
    public val id: TerminalRowId,
    public val cells: List<TerminalCell>,
) {
    override fun toString(): String = "TerminalRow($id, ${cells.joinToString("") { it.text.ifEmpty { " " } }.trimEnd()})"
}

public enum class TerminalCursorShape {
    BAR,
    BLOCK,
    UNDERLINE,
    BLOCK_HOLLOW,
}

public data class TerminalCursor(
    /** `null` when the cursor is not on the screen, for instance when it is scrolled into the history. */
    public val x: Int?,
    public val y: Int?,
    public val visible: Boolean,
    public val blinking: Boolean,
    public val shape: TerminalCursorShape,
)

/**
 * What the screen looks like at some point: everything it takes to draw it.
 */
public data class TerminalFrame(
    public val cols: Int,
    public val rows: List<TerminalRow>,
    public val background: TerminalColor,
    public val foreground: TerminalColor,
    /** `null` when the program did not set a cursor color: use the foreground. */
    public val cursorColor: TerminalColor?,
    public val cursor: TerminalCursor,
)
