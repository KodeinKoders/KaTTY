package app.katty.ghostty

public enum class GhosttyImageFormat {
    RGB,
    RGBA,
    PNG,
    GRAY_ALPHA,
    GRAY,
}

/**
 * An image that a program sent with the Kitty graphics protocol, as the terminal stores it: decompressed, and
 * decoded if it was a PNG.
 *
 * @param generation It changes whenever the image does, even if it is replaced by one of the same size: a cache of what
 * is made of the pixels is stale when it differs.
 * @param format Never [GhosttyImageFormat.PNG].
 */
public class GhosttyImage(
    public val id: Int,
    public val generation: Long,
    public val width: Int,
    public val height: Int,
    public val format: GhosttyImageFormat,
    public val bytes: ByteArray,
) {
    /**
     * The pixels as straight (not premultiplied) 8-bit RGBA, row by row from the top left: `width * height * 4` bytes.
     */
    public fun toRgba(): ByteArray {
        val pixels = width * height
        val rgba = ByteArray(pixels * 4)
        when (format) {
            GhosttyImageFormat.RGBA, GhosttyImageFormat.PNG -> bytes.copyInto(rgba, endIndex = minOf(bytes.size, rgba.size))
            GhosttyImageFormat.RGB -> for (i in 0 until pixels) {
                rgba[i * 4] = bytes[i * 3]
                rgba[i * 4 + 1] = bytes[i * 3 + 1]
                rgba[i * 4 + 2] = bytes[i * 3 + 2]
                rgba[i * 4 + 3] = OPAQUE
            }
            GhosttyImageFormat.GRAY -> for (i in 0 until pixels) {
                rgba[i * 4] = bytes[i]
                rgba[i * 4 + 1] = bytes[i]
                rgba[i * 4 + 2] = bytes[i]
                rgba[i * 4 + 3] = OPAQUE
            }
            GhosttyImageFormat.GRAY_ALPHA -> for (i in 0 until pixels) {
                rgba[i * 4] = bytes[i * 2]
                rgba[i * 4 + 1] = bytes[i * 2]
                rgba[i * 4 + 2] = bytes[i * 2]
                rgba[i * 4 + 3] = bytes[i * 2 + 1]
            }
        }
        return rgba
    }

    private companion object {
        const val OPAQUE: Byte = -1
    }
}

/**
 * Where an image is shown. Positions are of the viewport and in the terminal's own pixels, which are the cells of the
 * size that the terminal was given by `resize`.
 *
 * @param z The layer: below the backgrounds of the cells under `Int.MIN_VALUE / 2`, below the text under 0, above it
 * from 0.
 * @param isVirtual Placed by the unicode placeholder characters in cells: not by a position.
 * @param viewportCol The column of the viewport where it starts: negative when it begins to the left, which is a
 * viewport scrolled over it.
 * @param viewportRow Likewise for the row, which is negative when it is partly scrolled out at the top.
 * @param xOffset The pixels from the left of the cell, and [yOffset] from its top, where the image starts.
 * @param pixelWidth The size that it is shown at: it is not the one of the image.
 * @param sourceX The part of the image that is shown, in the pixels of the image.
 */
public data class GhosttyImagePlacement(
    val imageId: Int,
    val placementId: Int,
    val z: Int,
    val isVirtual: Boolean,
    val imageGeneration: Long,
    val viewportCol: Int,
    val viewportRow: Int,
    val visible: Boolean,
    val xOffset: Int,
    val yOffset: Int,
    val pixelWidth: Int,
    val pixelHeight: Int,
    val gridCols: Int,
    val gridRows: Int,
    val sourceX: Int,
    val sourceY: Int,
    val sourceWidth: Int,
    val sourceHeight: Int,
    /** The columns that the program asked for (`c=`), `0` when it did not: for a virtual placement, the size of the grid that the image is fitted in. */
    val columns: Int = 0,
    /** The rows that it asked for (`r=`). */
    val rows: Int = 0,
)
