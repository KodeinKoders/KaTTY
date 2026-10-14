@file:OptIn(ExperimentalComposeUiApi::class)

package app.katty.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import java.io.File

/** An image that was drawn, with the pixels to look at. */
class Rendered(val image: Image) {
    private val bitmap = Bitmap.makeFromImage(image)

    /** The color of the pixel at [x], [y] (in pixels of the image) as `0xRRGGBB`. */
    fun pixel(x: Int, y: Int): Int = bitmap.getColor(x, y) and 0xFFFFFF

    /** The pixels of the image that are [color] (as `0xRRGGBB`), within [tolerance] on each channel. */
    fun count(color: Int, tolerance: Int = 8): Int {
        var count = 0
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
            val p = pixel(x, y)
            if ((0..2).all { shift -> kotlin.math.abs((p shr (shift * 8) and 0xFF) - (color shr (shift * 8) and 0xFF)) <= tolerance }) count++
        }
        return count
    }

    fun bytes(): ByteArray = image.encodeToData(EncodedImageFormat.PNG)!!.bytes
}

/**
 * Draws [content] in the theme of the application, at a density of 2 as a screen of a Mac has, and keeps the image in the build
 * directory, to look at.
 *
 * @param nanos When the scene is drawn: what spins is in another place at another time.
 */
fun render(
    name: String,
    width: Int,
    height: Int,
    dark: Boolean = false,
    reducedMotion: Boolean = false,
    nanos: Long = 0,
    content: @Composable () -> Unit,
): Rendered {
    val scene = ImageComposeScene(width = width, height = height, density = Density(2f)) {
        KattyTheme(dark, reducedMotion, content)
    }
    try {
        val image = scene.render(nanos)
        File("build/renders").apply { mkdirs() }.resolve("$name.png").writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        return Rendered(image)
    } finally {
        scene.close()
    }
}
