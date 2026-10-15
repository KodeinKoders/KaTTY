package app.katty

import java.awt.Font
import java.awt.GraphicsEnvironment
import java.awt.image.BufferedImage

/**
 * The monospaced font families that are installed: a terminal draws a grid, and cannot use any other. It asks the system for the width of
 * a few characters of very different widths in a proportional font, and keeps the families in which they are all the same.
 */
object SystemFonts {
    private val SAMPLE = "iIlMW.m1".toList()

    /** The names of the families, sorted. Reading the fonts of the system takes a moment: not on the thread that draws. */
    fun monospacedFamilies(): List<String> {
        val graphics = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).createGraphics()
        try {
            return GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames
                .filter { name ->
                    // Not the fonts that macOS keeps for itself, which start with a dot, and not the ones that draw no text.
                    !name.startsWith(".") && isMonospaced(graphics.getFontMetrics(Font(name, Font.PLAIN, 16)))
                }
                .sortedWith(String.CASE_INSENSITIVE_ORDER)
        } finally {
            graphics.dispose()
        }
    }

    private fun isMonospaced(metrics: java.awt.FontMetrics): Boolean {
        val widths = SAMPLE.map { metrics.charWidth(it) }
        return widths.first() > 0 && widths.all { it == widths.first() }
    }
}
