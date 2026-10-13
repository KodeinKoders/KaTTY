package app.katty.terminal.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import kotlin.math.ceil

private const val FONTS = "app/katty/terminal/compose/fonts"

/** JetBrains Mono, which is bundled so that the metrics are the same on every machine. */
private fun terminalFontFamily() = FontFamily(
    Font("$FONTS/JetBrainsMono-Regular.ttf", FontWeight.Normal, FontStyle.Normal),
    Font("$FONTS/JetBrainsMono-Bold.ttf", FontWeight.Bold, FontStyle.Normal),
    Font("$FONTS/JetBrainsMono-Italic.ttf", FontWeight.Normal, FontStyle.Italic),
    Font("$FONTS/JetBrainsMono-BoldItalic.ttf", FontWeight.Bold, FontStyle.Italic),
)

/**
 * The font of the terminal, and the size of a cell, which is derived from it.
 */
internal class TerminalTypography(
    val measurer: TextMeasurer,
    private val density: Density,
    private val fontFamily: FontFamily,
    private val fontSize: TextUnit,
) {
    /**
     * The width of a cell in pixels: the advance of the font, which is not a whole number of pixels. It is not
     * rounded, so that a run of text that is drawn in one go lines up with the cells that it covers.
     */
    val cellWidth: Float

    /** The height of a cell in pixels, which is a whole number of them so that rows do not blur. */
    val cellHeight: Float

    private val baseStyle = TextStyle(fontFamily = fontFamily, fontSize = fontSize, letterSpacing = TextUnit.Unspecified)

    init {
        // Measuring many characters gives the advance more precisely than one, whose width is a whole number.
        val sample = measurer.measure(
            text = "M".repeat(MEASURED_CHARS),
            style = baseStyle,
            softWrap = false,
            constraints = Constraints(),
        )
        cellWidth = sample.getLineRight(0) / MEASURED_CHARS
        cellHeight = ceil(sample.getLineBottom(0) - sample.getLineTop(0))
    }

    private fun style(bold: Boolean, italic: Boolean): TextStyle = baseStyle.copy(
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
        // The line is as high as a cell whatever it is drawn with: a fallback font for a character that
        // JetBrains Mono does not have would otherwise make it taller, and move the baseline.
        lineHeight = with(density) { cellHeight.toSp() },
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
    )

    fun layout(text: String, bold: Boolean, italic: Boolean): TextLayoutResult =
        measurer.measure(text = text, style = style(bold, italic), softWrap = false, constraints = Constraints())

    private companion object {
        const val MEASURED_CHARS = 32
    }
}

@Composable
internal fun rememberTerminalTypography(fontSize: TextUnit): TerminalTypography {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val fontFamily = remember { terminalFontFamily() }
    return remember(measurer, density, fontFamily, fontSize) { TerminalTypography(measurer, density, fontFamily, fontSize) }
}
