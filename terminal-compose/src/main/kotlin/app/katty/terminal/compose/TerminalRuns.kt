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
    minimumContrast: Float = 1f,
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
    } else {
        // The text is made readable first, and then dimmed: faint is meant to be dim.
        if (minimumContrast > 1f) foreground = withMinimumContrast(foreground, background, minimumContrast)
        if (cell.style.faint) foreground = blend(foreground, background)
    }
    return CellColors(foreground, background)
}

/**
 * [foreground] moved towards white or black, whichever it can be most readable on [background], until the contrast between them (the
 * WCAG ratio: 1 for equal colors, 21 for black on white) is at least [minimum], or as far as it goes. It is [foreground] itself when it is
 * readable already.
 */
internal fun withMinimumContrast(foreground: Int, background: Int, minimum: Float): Int {
    if (contrastRatio(foreground, background) >= minimum) return foreground
    val white = 0xFFFFFF
    val black = 0x000000
    val target = if (contrastRatio(white, background) >= contrastRatio(black, background)) white else black
    if (contrastRatio(target, background) < minimum) return target
    // The least that is moved: the contrast only grows as the color goes to the target.
    var low = 0f
    var high = 1f
    repeat(CONTRAST_STEPS) {
        val middle = (low + high) / 2
        if (contrastRatio(mix(foreground, target, middle), background) >= minimum) high = middle else low = middle
    }
    return mix(foreground, target, high)
}

private const val CONTRAST_STEPS = 12

/** The WCAG contrast ratio of two `0xRRGGBB` colors, from 1 to 21. */
internal fun contrastRatio(a: Int, b: Int): Float {
    val la = luminance(a)
    val lb = luminance(b)
    return (maxOf(la, lb) + 0.05f) / (minOf(la, lb) + 0.05f)
}

private fun luminance(rgb: Int): Float {
    fun channel(shift: Int): Float {
        val c = (rgb shr shift and 0xFF) / 255f
        return if (c <= 0.03928f) c / 12.92f else Math.pow(((c + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
    }
    return 0.2126f * channel(16) + 0.7152f * channel(8) + 0.0722f * channel(0)
}

private fun mix(from: Int, to: Int, amount: Float): Int {
    fun channel(shift: Int): Int {
        val a = from shr shift and 0xFF
        val b = to shr shift and 0xFF
        return (a + (b - a) * amount).toInt().coerceIn(0, 255) shl shift
    }
    return channel(16) or channel(8) or channel(0)
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
    /** `0xRRGGBB`, `null` for the color of the text. */
    val underlineColor: Int? = null,
    /** Whether it is the text that blinks (SGR 5): it is hidden half of the time. */
    val blink: Boolean = false,
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
    minimumContrast: Float = 1f,
    builtinGlyphs: Boolean = false,
): List<TextRun> {
    val runs = ArrayList<TextRun>()
    row.cells.forEachIndexed { col, cell ->
        if (cell.text.isEmpty() || cell.width == TerminalCellWidth.SPACER_TAIL || cell.width == TerminalCellWidth.SPACER_HEAD) {
            return@forEachIndexed
        }
        // Drawn as a shape: see [spriteCells].
        if (builtinGlyphs && cell.spriteCodepoint() != null) return@forEachIndexed
        val foreground = resolveColors(cell, defaultForeground, defaultBackground, boldForeground, minimumContrast).foreground
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
            last.overline == style.overline &&
            last.underlineColor == style.underlineColor?.rgb &&
            last.blink == style.blink
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
                underlineColor = style.underlineColor?.rgb,
                blink = style.blink,
            )
        }
    }
    return runs
}

// A run of several cells is plain, and a run of a single one is plain if its cell is: only those merge.
private fun TextRun.isPlain(): Boolean = cols == text.length && text.all { it in ' '..'~' }

/** The code point of the cell's character if it is one that is drawn as a shape (see [isSprite]), `null` if it is for the font. */
internal fun TerminalCell.spriteCodepoint(): Int? {
    if (text.isEmpty() || width != TerminalCellWidth.NARROW) return null
    val codepoint = text.codePointAt(0)
    // A character with marks over it (a variation selector, a combining mark) is not the plain shape.
    if (Character.charCount(codepoint) != text.length) return null
    return codepoint.takeIf(::isSprite)
}

/** A character of a row that is drawn as a shape: where it is, and in what color. */
internal data class SpriteCell(val col: Int, val codepoint: Int, val foreground: Int, val blink: Boolean = false)

/** The characters of a row that are drawn as shapes that fill their cell, not with the font. */
internal fun spriteCells(
    row: TerminalRow,
    defaultForeground: TerminalColor,
    defaultBackground: TerminalColor,
    boldForeground: TerminalColor? = null,
    minimumContrast: Float = 1f,
): List<SpriteCell> {
    val cells = ArrayList<SpriteCell>()
    row.cells.forEachIndexed { col, cell ->
        val codepoint = cell.spriteCodepoint() ?: return@forEachIndexed
        val foreground = resolveColors(cell, defaultForeground, defaultBackground, boldForeground, minimumContrast).foreground
        cells += SpriteCell(col, codepoint, foreground, cell.style.blink)
    }
    return cells
}
