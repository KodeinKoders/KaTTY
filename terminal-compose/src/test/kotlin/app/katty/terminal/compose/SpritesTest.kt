package app.katty.terminal.compose

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The pixels that the rectangles of a sprite fill, as lines of `#` and `.`: what is drawn, in a form that can be written down. */
private fun art(sprite: Sprite, width: Int, height: Int): List<String> =
    (0 until height).map { y ->
        (0 until width).joinToString("") { x ->
            if (sprite.rects.any { x >= it.left && x < it.right && y >= it.top && y < it.bottom }) "#" else "."
        }
    }

private fun art(codepoint: Char, width: Int, height: Int) = art(sprite(codepoint.code, width, height)!!, width, height)

class SpritesTest {
    @Test
    fun aLightCrossIsOneLineEachWayThroughTheMiddle() {
        assertEquals(
            listOf(
                "...#....", "...#....", "...#....",
                "########",
                "...#....", "...#....", "...#....", "...#....",
            ),
            art('┼', 8, 8),
        )
    }

    @Test
    fun aDoubleCornerHasAnOuterLineThatTurnsAndAnInnerOneThatMeetsIt() {
        assertEquals(
            listOf(
                "........",
                "........",
                "..######",
                "..#.....",
                "..#.####",
                "..#.#...",
                "..#.#...",
                "..#.#...",
            ),
            art('╔', 8, 8),
        )
    }

    @Test
    fun aCornerIsFilledWhereTheTwoLinesMeet() {
        assertEquals(
            listOf(
                "........", "........", "........",
                "...#####",
                "...#....", "...#....", "...#....", "...#....",
            ),
            art('┌', 8, 8),
        )
    }

    @Test
    fun aHeavyLineIsTwiceAsThick() {
        val light = art('─', 8, 8).count { '#' in it }
        val heavy = art('━', 8, 8).count { '#' in it }

        assertEquals(1, light)
        assertEquals(2, heavy)
    }

    @Test
    fun aLineIsWholeWhateverTheWidthOfTheCell() {
        // The two halves meet in the middle of a cell that has an odd number of pixels, and leave no seam.
        for (width in 5..21) {
            val row = art('─', width, 18).single { '#' in it }
            assertEquals("#".repeat(width), row, "In a cell $width pixels wide")
        }
    }

    @Test
    fun aLineMeetsTheEdgesSoThatTheCellsAroundItJoin() {
        for (cell in listOf('│', '┃', '║')) {
            val rows = art(cell, 9, 19)
            assertTrue(rows.first().contains('#'), "$cell reaches the top")
            assertTrue(rows.last().contains('#'), "$cell reaches the bottom")
            assertTrue(rows.all { '#' in it }, "$cell has no gap")
        }
    }

    @Test
    fun theDashesOfALineAreEvenlySpaced() {
        assertEquals(".##.##.##".length, 9)
        val dashes = art('┄', 9, 8).single { '#' in it }
        // Three dashes, and half a gap at each end: the dashes of the next cell go on with the pattern.
        assertEquals(3, Regex("#+").findAll(dashes).count())
        assertEquals(6, dashes.count { it == '#' })
    }

    @Test
    fun theHalvesAndEighthsOfABlockAreWhatTheirNamesSay() {
        assertEquals(listOf("########", "########", "........", "........"), art('▀', 8, 4))
        assertEquals(listOf("........", "........", "########", "########"), art('▄', 8, 4))
        assertEquals(listOf("####....", "####....", "####....", "####...."), art('▌', 8, 4))
        assertEquals(listOf("....####", "....####", "....####", "....####"), art('▐', 8, 4))
        assertEquals(List(4) { "########" }, art('█', 8, 4))
        // One eighth of 16 rows is two.
        assertEquals(List(14) { "........" } + List(2) { "########" }, art('▁', 8, 16))
    }

    @Test
    fun aShadeIsTheColorOfTheTextPartlyOverWhatIsThere() {
        assertEquals(listOf(0.25f, 0.5f, 0.75f), listOf('░', '▒', '▓').map { sprite(it.code, 8, 8)!!.rects.single().alpha })
    }

    @Test
    fun theQuadrantsAreFilledAsTheyAreNamed() {
        assertEquals(listOf("....", "....", "##..", "##.."), art('▖', 4, 4))
        assertEquals(listOf("##..", "##..", "..##", "..##"), art('▚', 4, 4))
        assertEquals(listOf("####", "####", "..##", "..##").map { it }, art('▜', 4, 4))
    }

    @Test
    fun aBraillePatternHasADotForEachBitOfItsCodePoint() {
        assertTrue(sprite(0x2800, 8, 16)!!.rects.isEmpty())
        assertEquals(8, sprite(0x28FF, 8, 16)!!.rects.size)
        // Dot 1 is the upper left, dot 8 the lower right.
        val one = sprite(0x2801, 8, 16)!!.rects.single()
        val eight = sprite(0x2880, 8, 16)!!.rects.single()
        assertTrue(one.left < 4 && one.top < 4)
        assertTrue(eight.left > 4 && eight.top > 12)
    }

    @Test
    fun theRoundedCornersAndTheDiagonalsAreLines() {
        for (cell in listOf('╭', '╮', '╯', '╰', '╱', '╲', '╳')) {
            assertTrue(sprite(cell.code, 8, 16)!!.strokes.isNotEmpty(), "$cell is a line")
        }
        assertEquals(2, sprite('╳'.code, 8, 16)!!.strokes.size)
    }

    @Test
    fun thePowerlineArrowsFillTheCell() {
        val triangle = sprite(0xE0B0, 8, 16)!!.polygons.single()

        assertEquals(listOf(0f to 0f, 8f to 8f, 0f to 16f), triangle)
    }

    @Test
    fun everyCharacterOfTheBoxRangeIsDrawnAndOthersAreLeftToTheFont() {
        for (codepoint in 0x2500..0x257F) {
            assertNotNull(sprite(codepoint, 9, 18), "U+%04X".format(codepoint))
        }
        assertNull(sprite('a'.code, 9, 18))
        assertTrue(isSprite('┼'.code))
        assertTrue(!isSprite('a'.code))
    }

    @Test
    fun noRectangleGoesOutsideTheCell() {
        for (codepoint in (0x2500..0x259F) + (0x2800..0x28FF)) {
            for ((w, h) in listOf(8 to 16, 9 to 19, 17 to 34)) {
                for (r in sprite(codepoint, w, h)!!.rects) {
                    assertTrue(r.left >= 0f && r.top >= 0f && r.right <= w && r.bottom <= h && r.left < r.right && r.top < r.bottom, "U+%04X %s".format(codepoint, r))
                }
            }
        }
    }

    @Test
    fun aGlyphThatIsTooWideIsMadeToFitAndOneThatIsTooNarrowIsCentered() {
        assertEquals(GlyphFit(0.5f, 0f), fitGlyph(natural = 40f, available = 20f))
        assertEquals(GlyphFit(1f, 4f), fitGlyph(natural = 12f, available = 20f))
        // The font's own rounding is not a difference.
        assertEquals(GlyphFit.NONE, fitGlyph(natural = 20.4f, available = 20f))
        assertEquals(GlyphFit.NONE, fitGlyph(natural = 19.6f, available = 20f))
        assertEquals(GlyphFit.NONE, fitGlyph(natural = 0f, available = 20f))
    }
}
