package app.katty.terminal.compose

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.Typeface
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.FontStyle
import org.jetbrains.skia.Typeface as SkiaTypeface

// The font of a character that the terminal's font does not have. The system picks it, and for a character
// that can be an emoji it picks the emoji font, which draws a colored picture: a pause button that is blue, a
// red heart. Unless the program asks for the emoji, with the variation selector 16, it is a text symbol:
// what is drawn is a font that has it as one.

/**
 * Whether [text] is a character that is drawn as a symbol of the text unless it is asked to be an emoji:
 * `⏸`, `❤`, `⏺`. An emoji that is drawn as such by default (`😀`), and a character followed by the
 * variation selector 16 (`⏸️`), are not.
 */
internal fun wantsTextPresentation(text: String): Boolean {
    if (text.isEmpty() || text.contains(VARIATION_SELECTOR_16)) return false
    val codePoint = text.codePointAt(0)
    return codePoint >= 0x80 && Character.isEmoji(codePoint) && !Character.isEmojiPresentation(codePoint)
}

private const val VARIATION_SELECTOR_16 = '️'

// The fonts that are tried first, because they are meant for symbols and look right in a terminal. Any other
// font that has the character as text is used if none of them has it.
private val PREFERRED_FONTS = listOf("Menlo", "Apple Symbols", "STIX Two Math", "Arial Unicode MS", "DejaVu Sans", "Noto Sans Symbols 2", "Segoe UI Symbol")

// The tables of a font that draws in color.
private val COLOR_TABLES = setOf("sbix", "CBDT", "COLR", "SVG ")

/**
 * Finds the font that draws a character as text when the terminal's font does not have it. It does not
 * depend on which font that is: it is given the typefaces of the font that the terminal draws with.
 *
 * What it found is kept: looking is a walk through the fonts of the system, which is only done once for each
 * character. Not thread-safe: it is used from the UI thread.
 *
 * @param terminalFaces The typefaces of the terminal's font: it has a character if one of them has it.
 */
internal class TextFallback(private val terminalFaces: List<SkiaTypeface>) {
    private val found = HashMap<Int, FontFamily?>()

    /** The font to draw [text] with, or `null` to leave it to the system, which is what is right for most. */
    fun familyFor(text: String): FontFamily? {
        if (!wantsTextPresentation(text)) return null
        val codePoint = text.codePointAt(0)
        if (codePoint in found) return found[codePoint]
        return find(codePoint)?.let { FontFamily(Typeface(it)) }.also { found[codePoint] = it }
    }

    private fun find(codePoint: Int): SkiaTypeface? {
        if (terminalFaces.any { it.has(codePoint) }) return null
        val manager = FontMgr.default
        for (name in PREFERRED_FONTS) {
            val typeface = manager.matchFamilyStyle(name, FontStyle.NORMAL) ?: continue
            // Some systems answer with another font when they do not have the one that is asked for.
            if (typeface.familyName.equals(name, ignoreCase = true) && typeface.has(codePoint)) return typeface
        }
        for (i in 0 until manager.familiesCount) {
            val typeface = manager.makeStyleSet(i)?.matchStyle(FontStyle.NORMAL) ?: continue
            if (typeface.has(codePoint) && COLOR_TABLES.none { it in typeface.tableTags }) return typeface
        }
        return null
    }

    private fun SkiaTypeface.has(codePoint: Int) = getUTF32Glyph(codePoint).toInt() != 0
}
