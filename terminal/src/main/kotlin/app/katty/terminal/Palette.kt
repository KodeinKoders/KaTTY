package app.katty.terminal

import kotlin.math.cbrt
import kotlin.math.pow

// The generation of the 256-color palette is a port of `generate256Color` in Ghostty's
// src/terminal/color.zig (MIT, https://github.com/ghostty-org/ghostty), with `harmonious` off. The
// technique is jake-stewart's:
// https://gist.github.com/jake-stewart/0a8ea46159a7da2c808e5be2177e1783

internal const val PALETTE_SIZE = 256

/**
 * The 256 colors of the terminal for [theme]: its 16 ANSI colors, then a 6x6x6 cube and a gray ramp that
 * are interpolated from its background, its foreground and its 8 base colors, so that they fit it.
 *
 * Programs that assume the xterm palette get other colors than they expect, which is the point.
 */
internal fun generatePalette(theme: TerminalTheme): List<TerminalColor> {
    val ansi = theme.ansi
    // The 8 corners of the cube: the background, red, green, yellow, blue, magenta, cyan and the foreground.
    val corners = arrayOf(
        theme.background, ansi[1], ansi[2], ansi[3], ansi[4], ansi[5], ansi[6], theme.foreground,
    ).map { Lab.of(it) }.toTypedArray()

    // In a light theme the foreground is darker than the background, and the cube would run from white to
    // black: swapping them makes it run from black (16) to white (231), as programs expect.
    if (corners[7].l < corners[0].l) {
        val background = corners[0]
        corners[0] = corners[7]
        corners[7] = background
    }

    val palette = ArrayList<TerminalColor>(PALETTE_SIZE)
    palette += ansi

    // The cube, by trilinear interpolation: along red between the corners, then along green, then blue.
    for (r in 0 until 6) {
        val tr = r / 5f
        val c0 = Lab.lerp(tr, corners[0], corners[1])
        val c1 = Lab.lerp(tr, corners[2], corners[3])
        val c2 = Lab.lerp(tr, corners[4], corners[5])
        val c3 = Lab.lerp(tr, corners[6], corners[7])
        for (g in 0 until 6) {
            val tg = g / 5f
            val c4 = Lab.lerp(tg, c0, c1)
            val c5 = Lab.lerp(tg, c2, c3)
            for (b in 0 until 6) {
                palette += Lab.lerp(b / 5f, c4, c5).toColor()
            }
        }
    }

    // The gray ramp, from the background to the foreground, without the ends, which are in the cube.
    for (i in 1..24) {
        palette += Lab.lerp(i / 25f, corners[0], corners[7]).toColor()
    }

    return palette
}

/**
 * A color in CIELAB, where interpolating gives an even change of lightness whatever the hues.
 */
internal data class Lab(val l: Float, val a: Float, val b: Float) {
    fun toColor(): TerminalColor {
        val y = (l + 16f) / 116f
        val x = a / 500f + y
        val z = y - b / 200f

        val x3 = x * x * x
        val y3 = y * y * y
        val z3 = z * z * z
        val xf = (if (x3 > EPSILON) x3 else (x - 16f / 116f) / 7.787f) * 0.95047f
        val yf = if (y3 > EPSILON) y3 else (y - 16f / 116f) / 7.787f
        val zf = (if (z3 > EPSILON) z3 else (z - 16f / 116f) / 7.787f) * 1.08883f

        val r = xf * 3.2404542f - yf * 1.5371385f - zf * 0.4985314f
        val g = -xf * 0.9692660f + yf * 1.8760108f + zf * 0.0415560f
        val bl = xf * 0.0556434f - yf * 0.2040259f + zf * 1.0572252f

        return TerminalColor((encode(r) shl 16) or (encode(g) shl 8) or encode(bl))
    }

    companion object {
        private const val EPSILON = 0.008856f

        fun of(color: TerminalColor): Lab {
            val r = decode(color.r)
            val g = decode(color.g)
            val b = decode(color.b)

            // Linear sRGB to XYZ (D65), normalized by the white point, then the CIE f(t) transform.
            val x = f((r * 0.4124564f + g * 0.3575761f + b * 0.1804375f) / 0.95047f)
            val y = f(r * 0.2126729f + g * 0.7151522f + b * 0.0721750f)
            val z = f((r * 0.0193339f + g * 0.1191920f + b * 0.9503041f) / 1.08883f)

            return Lab(116f * y - 16f, 500f * (x - y), 200f * (y - z))
        }

        fun lerp(t: Float, from: Lab, to: Lab): Lab =
            Lab(from.l + t * (to.l - from.l), from.a + t * (to.a - from.a), from.b + t * (to.b - from.b))

        private fun f(t: Float): Float = if (t > EPSILON) cbrt(t) else 7.787f * t + 16f / 116f

        // The sRGB transfer function and its inverse.
        private fun decode(channel: Int): Float {
            val c = channel / 255f
            return if (c > 0.04045f) ((c + 0.055f) / 1.055f).pow(2.4f) else c / 12.92f
        }

        private fun encode(linear: Float): Int {
            val c = if (linear > 0.0031308f) 1.055f * linear.pow(1f / 2.4f) - 0.055f else 12.92f * linear
            return (c.coerceIn(0f, 1f) * 255f + 0.5f).toInt().coerceAtMost(255)
        }
    }
}
