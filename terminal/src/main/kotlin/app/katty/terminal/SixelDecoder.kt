package app.katty.terminal

/** A decoded image: straight (not premultiplied) RGBA, row after row, from the top left. */
internal class DecodedImage(val width: Int, val height: Int, val rgba: ByteArray)

/**
 * Decodes the string of a Sixel image, which is what a program sends with `DCS P1 ; P2 ; P3 q <data> ST`: the pixels of a picture in
 * bands of six rows, with a palette of color registers.
 *
 * - The data characters (0x3F to 0x7E) are six pixels of a column: bit 0 is the top one. `!n` repeats the next one, `$` goes back to the
 *   left of the band (to draw another color over it) and `-` goes down to the next band.
 * - `#n` selects a color register and `#n;2;r;g;b` (percent) or `#n;1;h;l;s` (hue as DEC counts it, which starts at blue, lightness and
 *   saturation in percent) defines it. The first sixteen registers start with the VT340's colors.
 * - `"Pan;Pad;Ph;Pv` says how big the picture is. It does not have to be, and what is drawn past it is kept.
 * - The pixels that are not drawn are transparent, whatever P2 says: the picture is drawn over the cells' background. The aspect of the
 *   pixels is 1:1, whatever P1 and the raster attributes say, as it is in the terminals that are used today.
 *
 * It does not throw: something that is not a Sixel image, or is too big, is `null`.
 */
internal object SixelDecoder {
    // What a picture may be: past it the data is refused, since the picture is as big in memory as it has pixels (4 bytes each).
    private const val MAX_PIXELS = 16L * 1024 * 1024
    private const val REGISTERS = 1024
    private const val MAX_SIDE = 16384

    // The VT340's colors, in percent.
    private val DEFAULT_PALETTE = intArrayOf(
        0, 0, 0, 20, 20, 80, 80, 13, 13, 20, 80, 20, 80, 20, 80, 20, 80, 80, 80, 80, 20, 53, 53, 53,
        26, 26, 26, 33, 33, 60, 60, 26, 26, 33, 60, 33, 60, 33, 60, 33, 60, 60, 60, 60, 33, 80, 80, 80,
    )

    /**
     * Decodes [data], from [from] to [to]: what follows `ESC P` up to the terminator, the parameters and the `q` included.
     */
    fun decode(data: ByteArray, from: Int, to: Int): DecodedImage? {
        var i = from
        // The parameters, up to the final byte.
        while (i < to && data[i] != 'q'.code.toByte()) {
            val b = data[i]
            if (b != ';'.code.toByte() && (b < '0'.code.toByte() || b > '9'.code.toByte())) return null
            i++
        }
        if (i >= to) return null
        i++

        val palette = IntArray(REGISTERS) { index ->
            if (index < 16) rgb(DEFAULT_PALETTE[index * 3], DEFAULT_PALETTE[index * 3 + 1], DEFAULT_PALETTE[index * 3 + 2]) else 0xFF000000.toInt()
        }
        var pixels = IntArray(0)
        var capacityWidth = 0
        var capacityHeight = 0
        var width = 0
        var height = 0
        var declaredWidth = 0
        var declaredHeight = 0

        var x = 0
        var band = 0
        var color = 0
        var repeat = 1

        // Makes room for the pixel at [px], [py].
        fun reserve(px: Int, py: Int): Boolean {
            if (px < capacityWidth && py < capacityHeight) return true
            val newWidth = maxOf(capacityWidth, if (px >= capacityWidth) maxOf(px + 1, capacityWidth * 2, 64) else capacityWidth)
            val newHeight = maxOf(capacityHeight, if (py >= capacityHeight) maxOf(py + 1, capacityHeight * 2, 64) else capacityHeight)
            if (newWidth.toLong() * newHeight > MAX_PIXELS) return false
            val grown = IntArray(newWidth * newHeight)
            for (row in 0 until height) System.arraycopy(pixels, row * capacityWidth, grown, row * newWidth, minOf(width, capacityWidth))
            pixels = grown
            capacityWidth = newWidth
            capacityHeight = newHeight
            return true
        }

        fun number(): Int? {
            var value = -1
            while (i < to && data[i] >= '0'.code.toByte() && data[i] <= '9'.code.toByte()) {
                value = (if (value < 0) 0 else value) * 10 + (data[i] - '0'.code.toByte())
                if (value > 100_000_000) value = 100_000_000
                i++
            }
            return if (value < 0) null else value
        }

        while (i < to) {
            val b = data[i].toInt() and 0xFF
            when {
                b in 0x3F..0x7E -> {
                    i++
                    val bits = b - 0x3F
                    val count = repeat
                    repeat = 1
                    if (bits == 0) {
                        x += count
                        if (x > width) width = x
                        continue
                    }
                    if (!reserve(x + count - 1, band * 6 + 5)) return null
                    for (column in x until x + count) {
                        for (bit in 0 until 6) {
                            if (bits and (1 shl bit) != 0) pixels[(band * 6 + bit) * capacityWidth + column] = palette[color]
                        }
                    }
                    x += count
                    if (x > width) width = x
                    if (band * 6 + 6 > height) height = band * 6 + 6
                }

                b == '!'.code -> {
                    i++
                    repeat = (number() ?: 1).coerceAtLeast(1)
                }

                b == '$'.code -> { i++; x = 0 }
                b == '-'.code -> { i++; x = 0; band++ }

                b == '#'.code -> {
                    i++
                    val register = (number() ?: 0).coerceIn(0, REGISTERS - 1)
                    if (i < to && data[i] == ';'.code.toByte()) {
                        i++
                        val system = number() ?: 0
                        val parameters = IntArray(3)
                        for (k in 0 until 3) {
                            if (i < to && data[i] == ';'.code.toByte()) i++
                            parameters[k] = number() ?: 0
                        }
                        palette[register] = when (system) {
                            1 -> hls(parameters[0], parameters[1], parameters[2])
                            else -> rgb(parameters[0], parameters[1], parameters[2])
                        }
                    }
                    color = register
                }

                b == '"'.code -> {
                    i++
                    number()
                    // Pad, then the width and the height that the picture is said to have.
                    val fields = IntArray(3)
                    for (k in 0 until 3) {
                        if (i < to && data[i] == ';'.code.toByte()) {
                            i++
                            fields[k] = number() ?: 0
                        }
                    }
                    declaredWidth = fields[1]
                    declaredHeight = fields[2]
                }

                // Anything else (line breaks, stray bytes) is skipped.
                else -> i++
            }
        }

        val finalWidth = maxOf(width, declaredWidth.coerceAtMost(MAX_SIDE))
        val finalHeight = maxOf(height, declaredHeight.coerceAtMost(MAX_SIDE))
        if (finalWidth <= 0 || finalHeight <= 0 || finalWidth.toLong() * finalHeight > MAX_PIXELS) return null

        val rgba = ByteArray(finalWidth * finalHeight * 4)
        for (row in 0 until minOf(height, finalHeight)) {
            for (column in 0 until minOf(width, finalWidth)) {
                val pixel = pixels[row * capacityWidth + column]
                if (pixel == 0) continue
                val offset = (row * finalWidth + column) * 4
                rgba[offset] = (pixel shr 16).toByte()
                rgba[offset + 1] = (pixel shr 8).toByte()
                rgba[offset + 2] = pixel.toByte()
                rgba[offset + 3] = 0xFF.toByte()
            }
        }
        return DecodedImage(finalWidth, finalHeight, rgba)
    }

    // Percent to a pixel: 0 is the "not drawn" of the grid, so a color is never all zeros with no alpha.
    private fun rgb(r: Int, g: Int, b: Int): Int =
        0xFF000000.toInt() or (percent(r) shl 16) or (percent(g) shl 8) or percent(b)

    private fun percent(value: Int) = (value.coerceIn(0, 100) * 255 + 50) / 100

    // Hue as DEC counts it: 0 is blue, 120 red, 240 green.
    private fun hls(hue: Int, lightness: Int, saturation: Int): Int {
        val h = ((hue % 360) + 360 + 240) % 360 / 360.0
        val l = lightness.coerceIn(0, 100) / 100.0
        val s = saturation.coerceIn(0, 100) / 100.0
        if (s == 0.0) return rgb((l * 100).toInt(), (l * 100).toInt(), (l * 100).toInt())
        val q = if (l < 0.5) l * (1 + s) else l + s - l * s
        val p = 2 * l - q
        fun channel(t0: Double): Int {
            var t = t0
            if (t < 0) t += 1.0
            if (t > 1) t -= 1.0
            val v = when {
                t < 1.0 / 6 -> p + (q - p) * 6 * t
                t < 0.5 -> q
                t < 2.0 / 3 -> p + (q - p) * (2.0 / 3 - t) * 6
                else -> p
            }
            return (v * 100).let { Math.round(it).toInt() }
        }
        return rgb(channel(h + 1.0 / 3), channel(h), channel(h - 1.0 / 3))
    }
}
