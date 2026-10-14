package app.katty.terminal.compose

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import app.katty.terminal.TerminalImage
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo

/**
 * What is made of the pixels of the images that are on the screen, which is only worth doing once for each: the bitmaps
 * that are drawn. An image is the same object in every frame while it does not change, so the object is the key.
 *
 * What is not in a frame any more is dropped (the images that the session has keep their pixels, which are the memory
 * that counts). Not thread-safe: it is used to draw.
 */
internal class ImageBitmapCache {
    private val bitmaps = HashMap<TerminalImage, ImageBitmap>()

    fun bitmapFor(image: TerminalImage): ImageBitmap = bitmaps.getOrPut(image) { image.toBitmap() }

    /** Forgets the bitmaps of the images that are not in [images]. */
    fun retain(images: Collection<TerminalImage>) {
        if (bitmaps.isEmpty()) return
        bitmaps.keys.retainAll(images.toSet())
    }

    val size: Int get() = bitmaps.size
}

// The pixels are straight RGBA: Skia premultiplies them when it draws.
private fun TerminalImage.toBitmap(): ImageBitmap =
    Image.makeRaster(ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL), rgba, width * 4).toComposeImageBitmap()
