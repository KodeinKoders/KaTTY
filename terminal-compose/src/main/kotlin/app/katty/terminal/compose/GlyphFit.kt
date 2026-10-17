package app.katty.terminal.compose

/** How a glyph is placed in the cells it is drawn in: made smaller by [scale] about the middle of its cells, then moved by [offsetX]. */
internal data class GlyphFit(val scale: Float, val offsetX: Float) {
    companion object {
        val NONE = GlyphFit(1f, 0f)
    }
}

/**
 * How a glyph that is [natural] pixels wide is placed in [available] pixels, which are its cells.
 *
 * A font that is not the terminal's, which draws a character that the terminal's does not have (an emoji, a symbol, a CJK character), has
 * glyphs of its own width: one that is wider than its cells would be drawn over the next cell, so it is made smaller to fit, and one that is
 * narrower is centered, as it would look lopsided at the left of its cells. A difference of half a pixel is the font's rounding, not a
 * difference.
 */
internal fun fitGlyph(natural: Float, available: Float): GlyphFit = when {
    natural <= 0f || available <= 0f -> GlyphFit.NONE
    natural > available + TOLERANCE_PX -> GlyphFit(available / natural, 0f)
    natural < available - TOLERANCE_PX -> GlyphFit(1f, (available - natural) / 2)
    else -> GlyphFit.NONE
}

private const val TOLERANCE_PX = 0.5f
