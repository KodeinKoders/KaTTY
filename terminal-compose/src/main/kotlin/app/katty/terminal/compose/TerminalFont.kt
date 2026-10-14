package app.katty.terminal.compose

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.text.platform.Typeface
import org.jetbrains.skia.Data
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.Typeface as SkiaTypeface

private const val FONTS = "app/katty/terminal/compose/fonts"

/**
 * A font of the terminal: what Compose draws with, and the faces of it, to ask which characters it has.
 *
 * Whatever the font is, a character that it does not have is drawn with another one, which is chosen by
 * [fallback] and never depends on which font it is.
 *
 * @param family What the text is drawn with.
 * @param faces The typefaces of [family], which Compose does not give back: it has a character if one of them
 * has it.
 */
internal class TerminalFont(val family: FontFamily, val faces: List<SkiaTypeface>) {
    init {
        require(faces.isNotEmpty()) { "A font has at least a face" }
    }

    /** The font for the characters that this one does not have. */
    val fallback = TextFallback(faces)

    companion object {
        /** A font from the system, by the name of its family, or `null` if there is none. */
        fun system(name: String): TerminalFont? {
            val typeface = FontMgr.default.matchFamilyStyle(name, org.jetbrains.skia.FontStyle.NORMAL) ?: return null
            // Some systems answer with another font when they do not have the one that is asked for.
            if (!typeface.familyName.equals(name, ignoreCase = true)) return null
            return TerminalFont(FontFamily(Typeface(typeface)), listOf(typeface))
        }

        /** JetBrains Mono, which is bundled so that the metrics are the same on every machine. */
        fun bundled(): TerminalFont {
            val faces = listOf(
                Face("JetBrainsMono-Regular.ttf", FontWeight.Normal, FontStyle.Normal),
                Face("JetBrainsMono-Bold.ttf", FontWeight.Bold, FontStyle.Normal),
                Face("JetBrainsMono-Italic.ttf", FontWeight.Normal, FontStyle.Italic),
                Face("JetBrainsMono-BoldItalic.ttf", FontWeight.Bold, FontStyle.Italic),
            )
            return TerminalFont(
                family = FontFamily(*faces.map { Font("$FONTS/${it.file}", it.weight, it.style) }.toTypedArray()),
                faces = faces.map { load(it.file) },
            )
        }

        private class Face(val file: String, val weight: FontWeight, val style: FontStyle)

        private fun load(file: String): SkiaTypeface {
            val bytes = checkNotNull(TerminalFont::class.java.classLoader.getResourceAsStream("$FONTS/$file")) {
                "The font $file is missing from the resources"
            }.use { it.readBytes() }
            return checkNotNull(FontMgr.default.makeFromData(Data.makeFromBytes(bytes))) { "The font $file cannot be read" }
        }
    }
}
