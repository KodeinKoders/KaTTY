@file:OptIn(ExperimentalTextApi::class)

package app.katty.terminal.compose

import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.text.platform.SystemFont
import java.io.File
import org.jetbrains.skia.Data
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.Typeface as SkiaTypeface

private const val FONTS = "app/katty/terminal/compose/fonts"

// Characters of very different widths in a proportional font: "i" and "." are narrow in it, and "M" and "W" are wide.
private val MONOSPACE_SAMPLE = listOf("i", "l", "M", "W", ".", "m")

/**
 * A font of the terminal: what Compose draws with, and the faces of it, to ask which characters it has.
 *
 * It has to be **monospaced**: the screen is a grid of cells, which are as wide as the font's advance. [system] and [files] refuse
 * a font that is not. A font that has no bold or italic face gets what Compose makes of the regular one.
 *
 * Whatever the font is, a character that it does not have is drawn with another one, which is chosen by a fallback and never
 * depends on which font it is. A font can be shared by any number of terminals, and is: that is what [bundled] gives.
 */
public class TerminalFont internal constructor(
    /** What the text is drawn with. */
    internal val family: FontFamily,
    /**
     * The typefaces of [family], which Compose does not give back: it has a character if one of them has it.
     */
    internal val faces: List<SkiaTypeface>,
) {
    init {
        require(faces.isNotEmpty()) { "A font has at least a face" }
    }

    /** The font for the characters that this one does not have. */
    internal val fallback = TextFallback(faces)

    public companion object {
        private val bundledFont: TerminalFont by lazy { loadBundled() }

        /**
         * JetBrains Mono, which is bundled so that the metrics are the same on every machine. It is loaded once, and every terminal
         * that asks for it shares it.
         */
        public fun bundled(): TerminalFont = bundledFont

        /**
         * A font from the system, by the name of its family, with its four faces (regular, bold, italic and bold italic: the ones it
         * does not have are what the system gives closest). `null` if there is no such family.
         *
         * @throws IllegalArgumentException If the font is not monospaced.
         */
        public fun system(family: String): TerminalFont? = systemUnchecked(family)?.also { it.requireMonospaced(family) }

        /**
         * A font from files. A face that is not given is not there: Compose makes it from the regular one.
         *
         * @throws IllegalArgumentException If a file is not a font, or if the font is not monospaced.
         */
        public fun files(regular: File, bold: File? = null, italic: File? = null, boldItalic: File? = null): TerminalFont {
            val given = listOf(
                Face(regular, FontWeight.Normal, FontStyle.Normal),
                bold?.let { Face(it, FontWeight.Bold, FontStyle.Normal) },
                italic?.let { Face(it, FontWeight.Normal, FontStyle.Italic) },
                boldItalic?.let { Face(it, FontWeight.Bold, FontStyle.Italic) },
            ).filterNotNull()
            val faces = given.map { face ->
                require(face.file.isFile) { "${face.file} is not a file" }
                requireNotNull(FontMgr.default.makeFromFile(face.file.path)) { "${face.file} is not a font" }
            }
            return TerminalFont(
                family = FontFamily(*given.map { Font(it.file, it.weight, it.style) }.toTypedArray()),
                faces = faces,
            ).also { it.requireMonospaced(regular.name) }
        }

        // A system font, whatever it is: what the tests that need a font that is not monospaced use.
        internal fun systemUnchecked(family: String): TerminalFont? {
            val manager = FontMgr.default
            val regular = manager.matchFamilyStyle(family, org.jetbrains.skia.FontStyle.NORMAL) ?: return null
            // Some systems answer with another font when they do not have the one that is asked for.
            if (!regular.familyName.equals(family, ignoreCase = true)) return null
            val faces = listOf(
                org.jetbrains.skia.FontStyle.NORMAL, org.jetbrains.skia.FontStyle.BOLD,
                org.jetbrains.skia.FontStyle.ITALIC, org.jetbrains.skia.FontStyle.BOLD_ITALIC,
            ).mapNotNull { style -> manager.matchFamilyStyle(family, style)?.takeIf { it.familyName.equals(family, ignoreCase = true) } }
            return TerminalFont(
                family = FontFamily(
                    SystemFont(family, FontWeight.Normal, FontStyle.Normal),
                    SystemFont(family, FontWeight.Bold, FontStyle.Normal),
                    SystemFont(family, FontWeight.Normal, FontStyle.Italic),
                    SystemFont(family, FontWeight.Bold, FontStyle.Italic),
                ),
                faces = faces,
            )
        }

        private class Face(val file: File, val weight: FontWeight, val style: FontStyle)

        private class BundledFace(val file: String, val weight: FontWeight, val style: FontStyle)

        private fun loadBundled(): TerminalFont {
            val faces = listOf(
                BundledFace("JetBrainsMono-Regular.ttf", FontWeight.Normal, FontStyle.Normal),
                BundledFace("JetBrainsMono-Bold.ttf", FontWeight.Bold, FontStyle.Normal),
                BundledFace("JetBrainsMono-Italic.ttf", FontWeight.Normal, FontStyle.Italic),
                BundledFace("JetBrainsMono-BoldItalic.ttf", FontWeight.Bold, FontStyle.Italic),
            )
            return TerminalFont(
                family = FontFamily(*faces.map { Font("$FONTS/${it.file}", it.weight, it.style) }.toTypedArray()),
                faces = faces.map { load(it.file) },
            )
        }

        private fun load(file: String): SkiaTypeface {
            val bytes = checkNotNull(TerminalFont::class.java.classLoader.getResourceAsStream("$FONTS/$file")) {
                "The font $file is missing from the resources"
            }.use { it.readBytes() }
            return checkNotNull(FontMgr.default.makeFromData(Data.makeFromBytes(bytes))) { "The font $file cannot be read" }
        }
    }

    private fun requireMonospaced(name: String) {
        for (face in faces) {
            val skiaFont = org.jetbrains.skia.Font(face, MONOSPACE_MEASURE_SIZE)
            val widths = MONOSPACE_SAMPLE.map { skiaFont.measureTextWidth(it) }
            require(widths.max() - widths.min() < MONOSPACE_TOLERANCE) {
                "$name is not monospaced (its characters are from ${widths.min()} to ${widths.max()} wide): the screen is a grid of cells"
            }
        }
    }
}

// Measured big, so that rounding does not hide a difference, and with a margin for the fonts that round their advances.
private const val MONOSPACE_MEASURE_SIZE = 100f
private const val MONOSPACE_TOLERANCE = 1.5f
