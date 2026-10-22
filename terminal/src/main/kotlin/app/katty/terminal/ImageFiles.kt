package app.katty.terminal

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import javax.imageio.stream.MemoryCacheImageInputStream

/**
 * Reads the image file [bytes] (whatever `javax.imageio` reads: its first frame), or `null` when it cannot be read or has more than
 * [maxPixels] pixels.
 *
 * The size is read from the header before anything is decoded: a file of a few hundred bytes can say that it is 20000 × 20000, and
 * `ImageIO.read` makes the image of that size before it reads a pixel. The file is read in memory: `ImageIO.read` would copy it to a
 * temporary file first, with its default cache.
 */
internal fun readImage(bytes: ByteArray, maxPixels: Long): BufferedImage? = try {
    MemoryCacheImageInputStream(ByteArrayInputStream(bytes)).use { input ->
        val reader = ImageIO.getImageReaders(input).asSequence().firstOrNull() ?: return null
        try {
            reader.setInput(input, true, true)
            val width = reader.getWidth(0)
            val height = reader.getHeight(0)
            if (width <= 0 || height <= 0 || width.toLong() * height > maxPixels) null else reader.read(0)
        } finally {
            reader.dispose()
        }
    }
} catch (_: Exception) {
    null
} catch (_: OutOfMemoryError) {
    null
}
