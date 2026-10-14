package app.katty.terminal

import kotlin.math.abs
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

// What Ghostty's tests of generate256Color check, which this is a port of.
class PaletteTest {
    private val black = TerminalColor(0x000000)
    private val white = TerminalColor(0xFFFFFF)

    private val ansi = listOf(
        0x000000, 0xCD0000, 0x00CD00, 0xCDCD00, 0x0000EE, 0xCD00CD, 0x00CDCD, 0xE5E5E5,
        0x7F7F7F, 0xFF0000, 0x00FF00, 0xFFFF00, 0x5C5CFF, 0xFF00FF, 0x00FFFF, 0xFFFFFF,
    ).map(::TerminalColor)

    private fun theme(background: TerminalColor, foreground: TerminalColor) = TerminalTheme(background, foreground, ansi)

    private fun luminance(color: TerminalColor): Double {
        fun linear(channel: Int): Double {
            val c = channel / 255.0
            return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * linear(color.r) + 0.7152 * linear(color.g) + 0.0722 * linear(color.b)
    }

    @Test
    fun hasTheThemesColorsThenTheGeneratedOnes() {
        val palette = generatePalette(theme(black, white))

        assertEquals(256, palette.size)
        assertEquals(ansi, palette.take(16))
    }

    @Test
    fun theCubeRunsFromTheBackgroundToTheForegroundInADarkTheme() {
        val palette = generatePalette(theme(black, white))

        assertEquals(black, palette[16])
        assertEquals(white, palette[231])
    }

    @Test
    fun theCubeStillRunsFromBlackToWhiteInALightTheme() {
        val palette = generatePalette(theme(background = white, foreground = black))

        assertEquals(black, palette[16])
        assertEquals(white, palette[231])
    }

    @Test
    fun theCubesCornersAreTheThemesColors() {
        val theme = TerminalTheme(
            background = TerminalColor(0x101820),
            foreground = TerminalColor(0xE0E8F0),
            ansi = List(16) { TerminalColor(0x102030 + it * 0x0A0B0C) },
        )
        val palette = generatePalette(theme)

        // Cube index 16 + 36 r + 6 g + b: red is the red of the theme, and so on.
        fun cube(r: Int, g: Int, b: Int) = palette[16 + 36 * r + 6 * g + b]
        assertEquals(theme.background, cube(0, 0, 0))
        assertEquals(theme.ansi[1], cube(5, 0, 0))
        assertEquals(theme.ansi[2], cube(0, 5, 0))
        assertEquals(theme.ansi[3], cube(5, 5, 0))
        assertEquals(theme.ansi[4], cube(0, 0, 5))
        assertEquals(theme.ansi[5], cube(5, 0, 5))
        assertEquals(theme.ansi[6], cube(0, 5, 5))
        assertEquals(theme.foreground, cube(5, 5, 5))
    }

    @Test
    fun theGrayRampGetsLighter() {
        for (theme in listOf(theme(black, white), theme(white, black))) {
            val ramp = generatePalette(theme).drop(232).map(::luminance)

            assertEquals(24, ramp.size)
            ramp.zipWithNext { before, after -> assertTrue(after >= before, "$before then $after") }
        }
    }

    @Test
    fun differsFromTheXtermPalette() {
        // The xterm cube's (0, 0, 1) is 0x00005f.
        assertNotEquals(TerminalColor(0x00005F), generatePalette(theme(TerminalColor(0x282A36), TerminalColor(0xF8F8F2)))[17])
    }

    @Test
    fun convertsToLabAndBack() {
        val colors = listOf(0xFFFFFF, 0x000000, 0xFF0000, 0x008000, 0x0000FF, 0x808080, 0x40E0D0)
        for (rgb in colors) {
            val color = TerminalColor(rgb)
            assertEquals(color, Lab.of(color).toColor())
        }
    }

    @Test
    fun convertsToLabAsExpected() {
        fun assertLab(rgb: Int, l: Float, a: Float, b: Float) {
            val lab = Lab.of(TerminalColor(rgb))
            assertTrue(abs(lab.l - l) < 0.5f && abs(lab.a - a) < 0.5f && abs(lab.b - b) < 0.5f, "$rgb is $lab")
        }
        assertLab(0xFFFFFF, 100f, 0f, 0f)
        assertLab(0x000000, 0f, 0f, 0f)
        assertLab(0xFF0000, 53.23f, 80.11f, 67.22f)
        assertLab(0x008000, 46.23f, -51.70f, 49.90f)
        assertLab(0x0000FF, 32.30f, 79.20f, -107.86f)
    }
}
