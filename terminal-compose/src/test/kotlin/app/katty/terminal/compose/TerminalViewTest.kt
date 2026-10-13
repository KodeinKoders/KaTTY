@file:OptIn(ExperimentalComposeUiApi::class)

package app.katty.terminal.compose

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import app.katty.terminal.TerminalCell
import app.katty.terminal.TerminalCellWidth
import app.katty.terminal.TerminalColor
import app.katty.terminal.TerminalCursor
import app.katty.terminal.TerminalCursorShape
import app.katty.terminal.TerminalFrame
import app.katty.terminal.TerminalRow
import app.katty.terminal.TerminalRowId
import app.katty.terminal.TerminalStyle
import app.katty.terminal.TerminalUnderline
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

private const val INK_THRESHOLD = 40

private val FOREGROUND = TerminalColor(0xD8D8D8)
private val BACKGROUND = TerminalColor(0x1E1E1E)
private val RED = TerminalColor(0xCC0000)
private val GREEN = TerminalColor(0x00AA00)
private val BLUE = TerminalColor(0x3060FF)

private var nextRowId = 0L

private fun cell(
    text: String = "",
    foreground: TerminalColor? = null,
    background: TerminalColor? = null,
    style: TerminalStyle = TerminalStyle.NONE,
    width: TerminalCellWidth = TerminalCellWidth.NARROW,
) = TerminalCell(text, foreground, background, style, width)

private fun row(cols: Int, vararg cells: TerminalCell): TerminalRow {
    val padded = cells.toList() + List(cols - cells.size) { cell() }
    return TerminalRow(TerminalRowId(nextRowId++, 0), padded)
}

private fun text(text: String, foreground: TerminalColor? = null, background: TerminalColor? = null, style: TerminalStyle = TerminalStyle.NONE) =
    text.map { cell(it.toString(), foreground, background, style) }.toTypedArray()

private fun frame(cols: Int, rows: List<TerminalRow>, cursor: TerminalCursor? = null) = TerminalFrame(
    cols = cols,
    rows = rows,
    background = BACKGROUND,
    foreground = FOREGROUND,
    cursorColor = null,
    cursor = cursor ?: TerminalCursor(x = null, y = null, visible = false, blinking = false, shape = TerminalCursorShape.BLOCK),
)

// The scene is closed, but it is not Closeable.
private inline fun <T> ImageComposeScene.useScene(block: (ImageComposeScene) -> T): T =
    try {
        block(this)
    } finally {
        close()
    }

private class Rendered(val image: Image, val typography: TerminalTypography) {
    private val bitmap = Bitmap.makeFromImage(image)

    /** The color of the pixel in the middle of a cell, as `0xRRGGBB`. */
    fun center(col: Int, row: Int): Int = pixel(((col + 0.5f) * typography.cellWidth).toInt(), ((row + 0.5f) * typography.cellHeight).toInt())

    fun pixel(x: Int, y: Int): Int = bitmap.getColor(x, y) and 0xFFFFFF

    /**
     * Whether anything but the background is drawn in the cell. The edges of the text are blended, and
     * text that has the color of the background still leaves a pixel or two a few levels off: that is not ink.
     */
    fun hasInk(col: Int, row: Int, background: TerminalColor = BACKGROUND): Boolean {
        val left = (col * typography.cellWidth).toInt()
        val top = (row * typography.cellHeight).toInt()
        return (left until (left + typography.cellWidth.toInt())).any { x ->
            (top until (top + typography.cellHeight.toInt())).any { y -> distance(pixel(x, y), background.rgb) > INK_THRESHOLD }
        }
    }

    private fun distance(a: Int, b: Int): Int =
        (0..2).sumOf { shift -> kotlin.math.abs((a shr (shift * 8) and 0xFF) - (b shr (shift * 8) and 0xFF)) }
}

/** Renders a frame, and keeps the image in the build directory, to look at. */
private fun render(name: String, frame: TerminalFrame, focused: Boolean = true, fontSize: Float = 14f): Rendered {
    var typography: TerminalTypography? = null
    val scene = ImageComposeScene(width = 640, height = 260, density = Density(2f)) {
        typography = rememberTerminalTypography(fontSize.sp)
        TerminalView(frame, typography, focused, Modifier.fillMaxSize())
    }
    scene.useScene {
        val image = scene.render()
        File("build/renders").apply { mkdirs() }.resolve("$name.png")
            .writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        return Rendered(image, typography!!)
    }
}

class TerminalViewTest {
    @Test
    fun cellSizeIsTheFontAdvanceAndAWholeLineHeight() {
        var typography: TerminalTypography? = null
        ImageComposeScene(width = 100, height = 100, density = Density(2f)) {
            typography = rememberTerminalTypography(14.sp)
        }.useScene { it.render() }

        // JetBrains Mono is 600 units wide in an em of 1000: at 14sp, on a 2x display, 28px.
        assertEquals(0.6f * 28f, typography!!.cellWidth, 0.05f)
        assertEquals(typography.cellHeight, kotlin.math.floor(typography.cellHeight), "Rows are on whole pixels")
        assertTrue(typography.cellHeight > 28f)
    }

    @Test
    fun drawsTheBackgroundsAndTheText() {
        val frame = frame(
            cols = 30,
            rows = listOf(
                row(30, *text("hello "), *text("red", background = RED), *text(" "), *text("green", foreground = GREEN)),
                row(30, *text("bold", style = TerminalStyle(bold = true)), *text(" "), *text("italic", style = TerminalStyle(italic = true))),
            ),
        )

        val rendered = render("backgrounds-and-text", frame)

        assertEquals(BACKGROUND.rgb, rendered.center(25, 0), "Nothing is drawn where there is no cell")
        assertEquals(RED.rgb, rendered.pixel((6.5f * rendered.typography.cellWidth).toInt(), 2), "A cell with a background has it, up to its edges")
        assertEquals(RED.rgb, rendered.pixel((8.5f * rendered.typography.cellWidth).toInt(), (rendered.typography.cellHeight - 2).toInt()))
        assertTrue(rendered.hasInk(0, 0), "h is drawn")
        assertTrue(rendered.hasInk(10, 0), "g is drawn")
        assertTrue(rendered.hasInk(0, 1), "A bold b is drawn")
        assertEquals(false, rendered.hasInk(29, 0), "Nothing is drawn after the text")
    }

    @Test
    fun drawsTheTextInItsColor() {
        val frame = frame(30, listOf(row(30, *text("████", foreground = GREEN))))

        val rendered = render("text-color", frame)

        // The middle of a full block is entirely in the color of the text.
        assertEquals(GREEN.rgb, rendered.center(1, 0))
    }

    @Test
    fun wideCharactersTakeTwoCellsAndWhatFollowsStaysInPlace() {
        val frame = frame(
            cols = 30,
            rows = listOf(
                row(
                    30,
                    cell("a"), cell("界", width = TerminalCellWidth.WIDE), cell(width = TerminalCellWidth.SPACER_TAIL), cell("b", background = RED),
                ),
            ),
        )

        val rendered = render("wide", frame)

        assertTrue(rendered.hasInk(1, 0), "The wide character is drawn")
        assertEquals(RED.rgb, rendered.pixel((3.5f * rendered.typography.cellWidth).toInt(), 2), "b is in the fourth cell, after the two of the wide character")
    }

    @Test
    fun appliesTheAttributes() {
        val frame = frame(
            cols = 30,
            rows = listOf(
                row(30, *text("inverse", style = TerminalStyle(inverse = true))),
                row(30, *text("hidden", style = TerminalStyle(invisible = true))),
                row(30, *text("underlined", style = TerminalStyle(underline = TerminalUnderline.SINGLE))),
                row(30, *text("curly ", style = TerminalStyle(underline = TerminalUnderline.CURLY)), *text("double", style = TerminalStyle(underline = TerminalUnderline.DOUBLE))),
                row(30, *text("struck", style = TerminalStyle(strikethrough = true)), *text(" over", style = TerminalStyle(overline = true)), *text(" dim", style = TerminalStyle(faint = true))),
            ),
        )

        val rendered = render("attributes", frame)

        assertEquals(FOREGROUND.rgb, rendered.pixel(2, 2), "Inverse fills the cell with the foreground")
        assertEquals(false, rendered.hasInk(1, 1), "Invisible text is not drawn")
    }

    @Test
    fun drawsTheCursor() {
        val cells = row(30, *text("abc"))
        val shapes = listOf(TerminalCursorShape.BLOCK, TerminalCursorShape.BAR, TerminalCursorShape.UNDERLINE)
        val frame = frame(
            30,
            listOf(cells),
            TerminalCursor(x = 5, y = 0, visible = true, blinking = false, shape = TerminalCursorShape.BLOCK),
        )

        val block = render("cursor-block", frame)
        assertEquals(FOREGROUND.rgb, block.center(5, 0), "A block fills its cell with the cursor color")

        val hollow = render("cursor-hollow", frame, focused = false)
        assertEquals(BACKGROUND.rgb, hollow.center(5, 0), "Without the focus the block is an outline")
        assertEquals(FOREGROUND.rgb, hollow.pixel((5 * hollow.typography.cellWidth).toInt() + 1, 4), "Its edge is drawn")

        for (shape in shapes - TerminalCursorShape.BLOCK) {
            render("cursor-${shape.name.lowercase()}", frame.copy(cursor = frame.cursor.copy(shape = shape)))
        }
        val hidden = render("cursor-hidden", frame.copy(cursor = frame.cursor.copy(visible = false)))
        assertEquals(BACKGROUND.rgb, hidden.center(5, 0))
    }

    @Test
    fun theCharacterUnderABlockCursorIsStillVisible() {
        val frame = frame(
            30,
            listOf(row(30, *text("abc"))),
            TerminalCursor(x = 1, y = 0, visible = true, blinking = false, shape = TerminalCursorShape.BLOCK),
        )

        val rendered = render("cursor-over-text", frame)

        assertTrue(rendered.hasInk(1, 0, background = FOREGROUND), "The b shows in the color of the background")
    }

    @Test
    fun keepsWhatItPreparedForTheRowsThatDidNotChange() {
        var layouts: RowLayouts? = null
        ImageComposeScene(width = 100, height = 100) {
            layouts = RowLayouts(rememberTerminalTypography(14.sp))
        }.useScene { it.render() }

        val unchanged = row(30, *text("same"))
        val first = frame(30, listOf(unchanged, row(30, *text("one"))))
        val second = frame(30, listOf(unchanged, row(30, *text("two"))))

        val preparedFirst = layouts!!.prepare(first)
        val preparedSecond = layouts.prepare(second)

        assertSame(preparedFirst[0], preparedSecond[0], "The row that did not change is not laid out again")
        assertNotEquals(preparedFirst[1], preparedSecond[1])
    }
}
