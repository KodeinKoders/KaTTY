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
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import kotlin.math.ceil

/**
 * The font of the terminal, and the size of a cell, which is derived from it.
 */
internal class TerminalTypography(
    val measurer: TextMeasurer,
    private val density: Density,
    val font: TerminalFont,
    private val fontSize: TextUnit,
    private val ligatures: Boolean = true,
) {
    /**
     * The width of a cell in pixels: the advance of the font, which is not a whole number of pixels. It is not
     * rounded, so that a run of text that is drawn in one go lines up with the cells that it covers.
     */
    val cellWidth: Float

    /** The height of a cell in pixels, which is a whole number of them so that rows do not blur. */
    val cellHeight: Float

    // The font joins sequences such as => and != into one glyph with its `liga` and `calt` features, which are on unless a style turns them off.
    // Compose reads the settings without quotes: with them (`'liga' 0`, the CSS way) they are ignored and the font still joins.
    private val baseStyle = TextStyle(
        fontFamily = font.family,
        fontSize = fontSize,
        letterSpacing = TextUnit.Unspecified,
        fontFeatureSettings = if (ligatures) null else "liga 0, calt 0",
    )

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

    private fun style(bold: Boolean, italic: Boolean, family: FontFamily?): TextStyle = baseStyle.copy(
        fontFamily = family ?: font.family,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
        // The line is as high as a cell whatever it is drawn with: a fallback font for a character that
        // the font does not have would otherwise make it taller, and move the baseline.
        lineHeight = with(density) { cellHeight.toSp() },
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
    )

    fun layout(text: String, bold: Boolean, italic: Boolean): TextLayoutResult =
        measurer.measure(text = text, style = style(bold, italic, font.fallback.familyFor(text)), softWrap = false, constraints = Constraints())

    private companion object {
        const val MEASURED_CHARS = 32
    }
}

@Composable
internal fun rememberTerminalTypography(
    fontSize: TextUnit,
    font: TerminalFont = TerminalFont.bundled(),
    ligatures: Boolean = true,
): TerminalTypography {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    return remember(measurer, density, font, fontSize, ligatures) { TerminalTypography(measurer, density, font, fontSize, ligatures) }
}
