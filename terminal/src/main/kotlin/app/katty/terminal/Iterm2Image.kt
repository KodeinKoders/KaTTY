package app.katty.terminal

import java.io.ByteArrayInputStream
import java.util.Base64
import javax.imageio.ImageIO

/** What an iTerm2 inline image asks for: how many cells wide and high, `null` for what the image's own size gives. */
internal class Iterm2Picture(val image: DecodedImage, val columns: Int?, val rows: Int?, val keepCursor: Boolean)

/**
 * Reads an iTerm2 inline image: `OSC 1337 ; File = key=value ; ... : <base64 of the file> BEL`.
 *
 * - `inline=1` shows it, and 0 (the default) is a download, which is not what a terminal does here.
 * - `width` and `height` are `N` (cells), `Npx` (pixels), `N%` (of the terminal) or `auto`. With both and `preserveAspectRatio` (which is
 *   on unless it is 0) the image is fitted in the box: only the dimension that limits it is given, and the other follows. Without it the
 *   image is stretched to both.
 * - `doNotMoveCursor=1` leaves the cursor where it is.
 * - The file is decoded by `javax.imageio`: PNG, JPEG, GIF (its first frame), BMP and TIFF, which chafa sends.
 *
 * It is `null` for anything that is not an image that is shown.
 */
internal object Iterm2Image {
    private const val PREFIX = "\u001b]1337;File="
    private const val MAX_PIXELS = 16L * 1024 * 1024

    private class Size(val value: Double, val unit: Char)

    /**
     * Reads the string [data], from [from] to [to]: `ESC ] 1337 ; File=` and what follows, without the terminator.
     */
    fun parse(data: ByteArray, from: Int, to: Int, geometry: CellGeometry): Iterm2Picture? {
        val prefix = PREFIX.toByteArray(Charsets.ISO_8859_1)
        if (to - from < prefix.size) return null
        val colon = (from + prefix.size until to).firstOrNull { data[it] == ':'.code.toByte() } ?: return null
        val arguments = String(data, from + prefix.size, colon - from - prefix.size, Charsets.ISO_8859_1)
            .split(';')
            .mapNotNull { argument -> argument.indexOf('=').takeIf { it > 0 }?.let { argument.substring(0, it).lowercase() to argument.substring(it + 1) } }
            .toMap()
        if (arguments["inline"] != "1") return null

        val file = try {
            Base64.getMimeDecoder().decode(data.copyOfRange(colon + 1, to))
        } catch (e: IllegalArgumentException) {
            return null
        }
        val picture = try {
            ImageIO.read(ByteArrayInputStream(file))
        } catch (e: Exception) {
            null
        } ?: return null
        val width = picture.width
        val height = picture.height
        if (width <= 0 || height <= 0 || width.toLong() * height > MAX_PIXELS) return null

        val argb = picture.getRGB(0, 0, width, height, null, 0, width)
        val rgba = ByteArray(width * height * 4)
        for (k in argb.indices) {
            val pixel = argb[k]
            rgba[k * 4] = (pixel shr 16).toByte()
            rgba[k * 4 + 1] = (pixel shr 8).toByte()
            rgba[k * 4 + 2] = pixel.toByte()
            rgba[k * 4 + 3] = (pixel ushr 24).toByte()
        }
        val image = DecodedImage(width, height, rgba)

        val wantedWidth = cells(arguments["width"], geometry.cellWidthPx, geometry.cols)
        val wantedHeight = cells(arguments["height"], geometry.cellHeightPx, geometry.rows)
        val keepCursor = arguments["donotmovecursor"] == "1"
        val preserve = arguments["preserveaspectratio"] != "0"

        return when {
            wantedWidth == null && wantedHeight == null -> Iterm2Picture(image, null, null, keepCursor)
            // One of them: the other follows the ratio.
            wantedHeight == null -> Iterm2Picture(image, wantedWidth, null, keepCursor)
            wantedWidth == null -> Iterm2Picture(image, null, wantedHeight, keepCursor)
            !preserve -> Iterm2Picture(image, wantedWidth, wantedHeight, keepCursor)
            else -> {
                // Fitted in the box: the dimension that limits it is given.
                val boxWidth = wantedWidth * geometry.cellWidthPx.toDouble()
                val boxHeight = wantedHeight * geometry.cellHeightPx.toDouble()
                if (boxWidth / width <= boxHeight / height) Iterm2Picture(image, wantedWidth, null, keepCursor) else Iterm2Picture(image, null, wantedHeight, keepCursor)
            }
        }
    }

    // A size in cells, or null for "auto".
    private fun cells(text: String?, cellPx: Int, count: Int): Int? {
        if (text == null || text.isEmpty() || text == "auto") return null
        val size = when {
            text.endsWith("px") -> Size(text.removeSuffix("px").toDoubleOrNull() ?: return null, 'p')
            text.endsWith("%") -> Size(text.removeSuffix("%").toDoubleOrNull() ?: return null, '%')
            else -> Size(text.toDoubleOrNull() ?: return null, 'c')
        }
        val result = when (size.unit) {
            'p' -> Math.ceil(size.value / cellPx.coerceAtLeast(1)).toInt()
            '%' -> Math.round(count * size.value / 100.0).toInt()
            else -> size.value.toInt()
        }
        return result.takeIf { it > 0 }
    }
}
