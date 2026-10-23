package app.katty.terminal.compose

import androidx.compose.runtime.RememberObserver
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
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
internal class ImageBitmapCache : RememberObserver {
    private val bitmaps = HashMap<TerminalImage, ImageBitmap>()

    fun bitmapFor(image: TerminalImage): ImageBitmap = bitmaps.getOrPut(image) { image.toBitmap() }

    /**
     * Forgets the bitmaps of the images that are not in [images], and frees their pixels, which are native memory that the garbage collector
     * would free only when it gets to them. It is called as a frame is drawn, before its images are: none of those is freed.
     */
    fun retain(images: Collection<TerminalImage>) {
        if (bitmaps.isEmpty()) return
        val kept = images.toSet()
        val entries = bitmaps.entries.iterator()
        while (entries.hasNext()) {
            val (image, bitmap) = entries.next()
            if (image in kept) continue
            bitmap.asSkiaBitmap().close()
            entries.remove()
        }
    }

    val size: Int get() = bitmaps.size

    // The view that draws them is gone: so are they.
    private fun clear() {
        bitmaps.values.forEach { it.asSkiaBitmap().close() }
        bitmaps.clear()
    }

    override fun onRemembered() {}

    override fun onForgotten() = clear()

    override fun onAbandoned() = clear()
}

// The pixels are straight RGBA: Skia premultiplies them when it draws. The image is only what the bitmap is made from: it is freed at once.
private fun TerminalImage.toBitmap(): ImageBitmap =
    Image.makeRaster(ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL), rgba, width * 4).use { it.toComposeImageBitmap() }
