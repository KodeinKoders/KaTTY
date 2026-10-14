package app.katty.terminal.compose

import app.katty.terminal.TerminalCell
import app.katty.terminal.TerminalCellWidth
import app.katty.terminal.TerminalColor
import app.katty.terminal.TerminalRow
import app.katty.terminal.TerminalUnderline

// What a row is drawn as: runs of cells that share their colors, and runs of text that share their font.
// This is pure logic, so that it can be tested without drawing anything.

/**
 * The colors a cell is drawn with, once its attributes are applied: inverse swaps them, invisible hides the
 * text, and faint blends the text into the background. Both are `0xRRGGBB`.
 *
 * @param boldForeground What bold text that has the default foreground is drawn in, if not the foreground.
 */
internal data class CellColors(val foreground: Int, val background: Int)

internal fun resolveColors(
    cell: TerminalCell,
    defaultForeground: TerminalColor,
    defaultBackground: TerminalColor,
    boldForeground: TerminalColor? = null,
): CellColors {
    // The bold color is for text that has the default foreground: a program's own color is not touched.
    val defaultOfCell = if (cell.style.bold && boldForeground != null) boldForeground else defaultForeground
    var foreground = (cell.foreground ?: defaultOfCell).rgb
    var background = (cell.background ?: defaultBackground).rgb
    if (cell.style.inverse) {
        foreground = background.also { background = foreground }
    }
    if (cell.style.invisible) {
        foreground = background
    } else if (cell.style.faint) {
        foreground = blend(foreground, background)
    }
    return CellColors(foreground, background)
}

/** The color halfway between the two. */
private fun blend(a: Int, b: Int): Int {
    fun channel(shift: Int) = (((a shr shift and 0xFF) + (b shr shift and 0xFF)) / 2) shl shift
    return channel(16) or channel(8) or channel(0)
}

/**
 * Consecutive cells of a row that have the same background. The default background is not drawn: the
 * whole terminal is filled with it first.
 */
internal data class BackgroundRun(val startCol: Int, val cols: Int, val rgb: Int)

internal fun backgroundRuns(
    row: TerminalRow,
    defaultForeground: TerminalColor,
    defaultBackground: TerminalColor,
    boldForeground: TerminalColor? = null,
): List<BackgroundRun> {
    val runs = ArrayList<BackgroundRun>()
    row.cells.forEachIndexed { col, cell ->
        val background = resolveColors(cell, defaultForeground, defaultBackground, boldForeground).background
        if (background == defaultBackground.rgb) return@forEachIndexed
        val last = runs.lastOrNull()
        if (last != null && last.rgb == background && last.startCol + last.cols == col) {
            runs[runs.lastIndex] = last.copy(cols = last.cols + 1)
        } else {
            runs += BackgroundRun(col, 1, background)
        }
    }
    return runs
}

/**
 * Text that is drawn in one go: it shares a font, a color and decorations.
 */
internal data class TextRun(
    val startCol: Int,
    /** The number of cells it covers, which is not the length of the text for a wide character. */
    val cols: Int,
    val text: String,
    val foreground: Int,
    val bold: Boolean,
    val italic: Boolean,
    val underline: TerminalUnderline,
    val strikethrough: Boolean,
    val overline: Boolean,
)

/**
 * Splits a row in the text that is drawn together.
 *
 * Only plain ASCII is drawn as a run: a font draws it with exactly the width of a cell, so that a whole run
 * lines up with the grid. Anything else (wide characters, accents, box drawing, emoji) may come from another
 * font, whose widths do not match: each such cell is drawn on its own, at the position of its cell.
 * Cells without text break a run.
 */
internal fun textRuns(
    row: TerminalRow,
    defaultForeground: TerminalColor,
    defaultBackground: TerminalColor,
    boldForeground: TerminalColor? = null,
): List<TextRun> {
    val runs = ArrayList<TextRun>()
    row.cells.forEachIndexed { col, cell ->
        if (cell.text.isEmpty() || cell.width == TerminalCellWidth.SPACER_TAIL || cell.width == TerminalCellWidth.SPACER_HEAD) {
            return@forEachIndexed
        }
        val foreground = resolveColors(cell, defaultForeground, defaultBackground, boldForeground).foreground
        val style = cell.style
        val plain = cell.text.length == 1 && cell.text[0] in ' '..'~' && cell.width == TerminalCellWidth.NARROW

        val last = runs.lastOrNull()
        if (plain && last != null && last.isPlain() &&
            last.startCol + last.cols == col &&
            last.foreground == foreground &&
            last.bold == style.bold &&
            last.italic == style.italic &&
            last.underline == style.underline &&
            last.strikethrough == style.strikethrough &&
            last.overline == style.overline
        ) {
            runs[runs.lastIndex] = last.copy(cols = last.cols + 1, text = last.text + cell.text)
        } else {
            runs += TextRun(
                startCol = col,
                cols = if (cell.width == TerminalCellWidth.WIDE) 2 else 1,
                text = cell.text,
                foreground = foreground,
                bold = style.bold,
                italic = style.italic,
                underline = style.underline,
                strikethrough = style.strikethrough,
                overline = style.overline,
            )
        }
    }
    return runs
}

// A run of several cells is plain, and a run of a single one is plain if its cell is: only those merge.
private fun TextRun.isPlain(): Boolean = cols == text.length && text.all { it in ' '..'~' }
