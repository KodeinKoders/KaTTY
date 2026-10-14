package app.katty.ghostty

/** The number of colors in a palette. */
internal const val PALETTE_SIZE = 256

/**
 * An RGB color.
 */
@JvmInline
public value class GhosttyRgb(
    /** `0xRRGGBB`. */
    public val rgb: Int,
) {
    public val r: Int get() = rgb shr 16 and 0xFF
    public val g: Int get() = rgb shr 8 and 0xFF
    public val b: Int get() = rgb and 0xFF

    override fun toString(): String = "#%06x".format(rgb)

    public companion object {
        public fun of(r: Int, g: Int, b: Int): GhosttyRgb = GhosttyRgb((r and 0xFF shl 16) or (g and 0xFF shl 8) or (b and 0xFF))
    }
}

/**
 * What changed since the last [GhosttyRenderState.clean].
 */
public enum class GhosttyDirty {
    /** Nothing changed. */
    CLEAN,

    /** Some rows changed: those that are [GhosttyRenderRow.dirty]. */
    PARTIAL,

    /** Everything must be redrawn. */
    FULL,
}

public data class GhosttyRenderColors(
    public val background: GhosttyRgb,
    public val foreground: GhosttyRgb,
    /** `null` when the program did not set a cursor color: use the foreground. */
    public val cursor: GhosttyRgb?,
)

public enum class GhosttyCursorStyle {
    BAR,
    BLOCK,
    UNDERLINE,
    BLOCK_HOLLOW,
}

public data class GhosttyRenderCursor(
    /** `null` when the cursor is not in the viewport, for instance when scrolled into the scrollback. */
    public val x: Int?,
    public val y: Int?,
    public val visible: Boolean,
    public val blinking: Boolean,
    public val style: GhosttyCursorStyle,
    /** Whether the cursor is on the right half of a wide character. */
    public val wideTail: Boolean,
    /** Whether the program is reading a password: the typed characters should not be shown. */
    public val passwordInput: Boolean,
)

public enum class GhosttyUnderline {
    NONE,
    SINGLE,
    DOUBLE,
    CURLY,
    DOTTED,
    DASHED,
}

public data class GhosttyCellStyle(
    public val bold: Boolean = false,
    public val italic: Boolean = false,
    public val faint: Boolean = false,
    public val blink: Boolean = false,
    public val inverse: Boolean = false,
    public val invisible: Boolean = false,
    public val strikethrough: Boolean = false,
    public val overline: Boolean = false,
    public val underline: GhosttyUnderline = GhosttyUnderline.NONE,
) {
    public companion object {
        /** No attribute at all, which is most cells. */
        public val NONE: GhosttyCellStyle = GhosttyCellStyle()
    }
}

public enum class GhosttyCellWidth {
    NARROW,

    /** The left half of a wide character: the next cell is its [SPACER_TAIL]. */
    WIDE,

    /** The right half of a wide character, which draws nothing. */
    SPACER_TAIL,

    /** The end of a line that a wide character did not fit in, which draws nothing. */
    SPACER_HEAD,
}

public data class GhosttyRenderCell(
    /** The grapheme cluster, or an empty string for a cell without text. */
    public val text: String,
    /** `null` for the default foreground. Bold is not applied to it. */
    public val foreground: GhosttyRgb?,
    /** `null` for the default background. */
    public val background: GhosttyRgb?,
    public val style: GhosttyCellStyle,
    public val width: GhosttyCellWidth,
    /** Whether the cell is part of a hyperlink (OSC 8). The address is [GhosttyTerminal.hyperlinkAt]. */
    public val hyperlink: Boolean = false,
)

/**
 * The identity of a row, which follows it when it moves, for instance when scrolling. An id is never
 * reused: it is gone once its row is gone or modified in place.
 */
public data class GhosttyRowId(
    public val bits0: Long,
    public val bits1: Long,
)

public data class GhosttyRenderRow(
    /** The position in the viewport, from the top. */
    public val y: Int,
    public val id: GhosttyRowId,
    /** Whether the row changed since the last [GhosttyRenderState.clean]. Conservative: it may not have. */
    public val dirty: Boolean,
    /** The selected columns, `null` if none of the row is selected. */
    public val selection: IntRange?,
    /** `null` when the cells of this row were not asked for, see [GhosttyRenderState.readRows]. */
    public val cells: List<GhosttyRenderCell>?,
)
