package app.katty.terminal

/**
 * An image that a program sent with the Kitty graphics protocol, decoded.
 *
 * It is **the same object** in every frame while it does not change, and a new one when the program sends the image
 * again (with the same id, even if it is the same size): a renderer can keep what it made of the pixels, keyed by the
 * object. Equality is by identity, on purpose: comparing the pixels would defeat the point.
 *
 * @param id The id that the program gave it, or that the terminal did.
 * @param rgba The pixels, as straight (not premultiplied) 8-bit RGBA, row by row from the top left:
 * `width * height * 4` bytes. Not to be changed.
 */
public class TerminalImage(
    public val id: Int,
    public val generation: Long,
    public val width: Int,
    public val height: Int,
    public val rgba: ByteArray,
) {
    override fun toString(): String = "TerminalImage($id, ${width}x$height)"
}

/** Where an image is drawn in relation to the cells. */
public enum class TerminalImageLayer {
    /** Under the backgrounds of the cells. */
    BELOW_BACKGROUND,

    /** Over the backgrounds and under the text. */
    BELOW_TEXT,

    /** Over the text. */
    ABOVE_TEXT,
}

/**
 * An image where it is shown on the screen. Positions and sizes are in **cells**, from the top left of the screen, and
 * fractional: an image starts at a pixel of a cell, and ends where its size says, which is not on a boundary. A cell is
 * the size that the renderer measured, not the one that the terminal was given, so that the image stays on its cells.
 *
 * @param left The column where it starts: negative when the screen has scrolled over its left, and the same for [top].
 * @param width How wide it is shown, in cells: it is not the size of the image.
 * @param sourceX The part of the image that is shown, in the pixels of the image.
 */
public data class TerminalImagePlacement(
    public val image: TerminalImage,
    public val left: Float,
    public val top: Float,
    public val width: Float,
    public val height: Float,
    public val sourceX: Int,
    public val sourceY: Int,
    public val sourceWidth: Int,
    public val sourceHeight: Int,
    public val layer: TerminalImageLayer,
)
