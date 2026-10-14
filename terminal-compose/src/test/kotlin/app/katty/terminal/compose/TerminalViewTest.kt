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
private const val COLOR_THRESHOLD = 30

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

    /** Whether anything in the cell is not gray: the text and the background of these tests are. */
    fun hasColor(col: Int, row: Int): Boolean {
        val left = (col * typography.cellWidth).toInt()
        val top = (row * typography.cellHeight).toInt()
        return (left until (left + typography.cellWidth.toInt())).any { x ->
            (top until (top + typography.cellHeight.toInt())).any { y ->
                val color = pixel(x, y)
                val channels = listOf(color shr 16 and 0xFF, color shr 8 and 0xFF, color and 0xFF)
                channels.max() - channels.min() > COLOR_THRESHOLD
            }
        }
    }

    /** Whether a line of the cell, across it, is in the color: an underline. */
    fun hasLine(col: Int, row: Int, color: TerminalColor): Boolean {
        val left = (col * typography.cellWidth).toInt() + 1
        val right = ((col + 1) * typography.cellWidth).toInt() - 1
        val top = (row * typography.cellHeight).toInt()
        return (top until (top + typography.cellHeight.toInt())).any { y ->
            (left until right).all { x -> distance(pixel(x, y), color.rgb) < INK_THRESHOLD }
        }
    }

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
private fun render(name: String, frame: TerminalFrame, focused: Boolean = true, fontSize: Float = 14f, preedit: String = "", font: TerminalFont? = null, scrollbarAlpha: Float = 0f, scrollbarEmphasized: Boolean = false): Rendered {
    var typography: TerminalTypography? = null
    val scene = ImageComposeScene(width = 640, height = 260, density = Density(2f)) {
        typography = if (font != null) rememberTerminalTypography(fontSize.sp, font) else rememberTerminalTypography(fontSize.sp)
        TerminalView(frame, typography, focused, Modifier.fillMaxSize(), preedit, scrollbarAlpha, scrollbarEmphasized)
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

    // Renders until the condition holds, since the cursor is hidden and shown by a delay.
    private fun ImageComposeScene.renderUntil(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!condition()) {
            check(System.nanoTime() < deadline) { "Still waiting for $what" }
            Thread.sleep(50)
        }
    }

    private fun cursorIsShown(frame: TerminalFrame, block: ImageComposeScene.(shown: () -> Boolean) -> Unit) {
        var typography: TerminalTypography? = null
        val scene = ImageComposeScene(width = 640, height = 260, density = Density(2f)) {
            typography = rememberTerminalTypography(14.sp)
            TerminalView(frame, typography, focused = true, Modifier.fillMaxSize())
        }
        scene.useScene {
            it.block { Rendered(it.render(System.nanoTime()), typography!!).center(5, 0) == FOREGROUND.rgb }
        }
    }

    @Test
    fun aBlinkingCursorIsHiddenThenShownAgain() {
        val blinking = TerminalCursor(x = 5, y = 0, visible = true, blinking = true, shape = TerminalCursorShape.BLOCK)

        cursorIsShown(frame(30, listOf(row(30, *text("abc"))), blinking)) { shown ->
            assertTrue(shown(), "It starts shown")
            renderUntil("the cursor to be hidden") { !shown() }
            renderUntil("the cursor to be shown again") { shown() }
        }
    }

    @Test
    fun aSteadyCursorStaysShown() {
        val steady = TerminalCursor(x = 5, y = 0, visible = true, blinking = false, shape = TerminalCursorShape.BLOCK)

        cursorIsShown(frame(30, listOf(row(30, *text("abc"))), steady)) { shown ->
            assertTrue(shown())
            // Longer than it takes a blinking one to be hidden.
            Thread.sleep(1_000)
            assertTrue(shown())
        }
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
    fun theCharacterUnderABlockCursorHasTheColorOfTheCursorText() {
        val frame = frame(
            30,
            listOf(row(30, *text("█"))),
            TerminalCursor(x = 0, y = 0, visible = true, blinking = false, shape = TerminalCursorShape.BLOCK),
        ).copy(cursorColor = RED, cursorTextColor = GREEN)

        val rendered = render("cursor-text-color", frame)

        // The middle of a full block is entirely in the color of the character, which is not the cursor's.
        assertEquals(GREEN.rgb, rendered.center(0, 0))
    }

    @Test
    fun boldTextThatHasTheDefaultForegroundIsDrawnInTheBoldColor() {
        val bold = TerminalStyle(bold = true)
        val frame = frame(30, listOf(row(30, *text("████", style = bold), *text("████", foreground = RED, style = bold)))).copy(boldColor = GREEN)

        val rendered = render("bold-color", frame)

        assertEquals(GREEN.rgb, rendered.center(1, 0))
        assertEquals(RED.rgb, rendered.center(5, 0), "The color of the program wins")
    }

    private val steadyCursor = { col: Int -> TerminalCursor(x = col, y = 0, visible = true, blinking = false, shape = TerminalCursorShape.BLOCK) }

    @Test
    fun theTextThatIsBeingComposedIsUnderlinedAtTheCursorAndCoversWhatIsThere() {
        // Full blocks, so that what is covered is visible: a cell that shows the background is a cell that is covered.
        val frame = frame(30, listOf(row(30, *text("███"))), steadyCursor(1))

        val rendered = render("preedit", frame, preedit = "'")

        assertEquals(BACKGROUND.rgb, rendered.center(1, 0), "The block is covered")
        assertEquals(FOREGROUND.rgb, rendered.center(0, 0), "What is not under the preedit is not")
        assertEquals(FOREGROUND.rgb, rendered.center(2, 0))
        assertTrue(rendered.hasInk(1, 0), "The dead key is drawn")
        assertTrue(rendered.hasLine(1, 0, FOREGROUND), "It is underlined")
    }

    @Test
    fun eachCharacterOfTheTextThatIsBeingComposedIsUnderlinedInItsCell() {
        val frame = frame(30, listOf(row(30)), steadyCursor(1))

        val rendered = render("preedit-two", frame, preedit = "éé")

        assertEquals(false, rendered.hasLine(0, 0, FOREGROUND), "Before it")
        assertTrue(rendered.hasLine(1, 0, FOREGROUND))
        assertTrue(rendered.hasLine(2, 0, FOREGROUND))
        assertEquals(false, rendered.hasLine(3, 0, FOREGROUND), "After it")
    }

    @Test
    fun theTextThatIsBeingComposedIsShiftedLeftToFitInTheLine() {
        val frame = frame(30, listOf(row(30)), steadyCursor(29))

        val rendered = render("preedit-edge", frame, preedit = "ab")

        assertEquals(false, rendered.hasLine(27, 0, FOREGROUND))
        assertTrue(rendered.hasLine(28, 0, FOREGROUND), "It starts one cell before the cursor")
        assertTrue(rendered.hasLine(29, 0, FOREGROUND))
    }

    @Test
    fun theCursorIsNotDrawnWhileComposing() {
        val frame = frame(30, listOf(row(30)), steadyCursor(5))

        assertEquals(FOREGROUND.rgb, render("no-preedit", frame).center(5, 0), "The cursor is a block")
        assertEquals(BACKGROUND.rgb, render("preedit-cursor", frame, preedit = "'").center(5, 0), "The cursor does not hide the text")
    }

    @Test
    fun aSymbolThatCanBeAnEmojiIsDrawnAsTextUnlessItIsAskedToBeAnEmoji() {
        // The system picks the emoji font for these, which draws pictures in color: the blue pause button
        // that Claude Code's plan mode would show instead of two bars.
        val frame = frame(
            30,
            listOf(
                row(
                    30,
                    cell("\u23F8"), cell("\u2764"), cell("\u23FA"), cell("\u26A0"), cell("\u23F5"),
                    cell("\u23F8\uFE0F"), cell("\u2764\uFE0F"), cell("\uD83D\uDE00", width = TerminalCellWidth.WIDE),
                ),
            ),
        )

        val rendered = render("text-symbols", frame, fontSize = 24f)

        for (col in 0..4) assertTrue(rendered.hasInk(col, 0), "The symbol of the cell $col is drawn")
        for (col in 0..4) assertEquals(false, rendered.hasColor(col, 0), "The symbol of the cell $col is a picture")
        for (col in 5..7) assertTrue(rendered.hasColor(col, 0), "The emoji of the cell $col is not a picture")
    }

    @Test
    fun aSymbolIsDrawnAsTextWhicheverFontTheTerminalHas() {
        // The fix is not about JetBrains Mono: it is about any font that lacks the symbol. Menlo does, and a font that
        // has it (STIX Two Math) draws it itself.
        val frame = frame(30, listOf(row(30, cell("\u23F8"), cell("\u2764"), cell("\u23FA"), cell("\u23F8\uFE0F"))))
        for (name in listOf("Menlo", "STIX Two Math", "Apple Symbols", "Courier New")) {
            val font = TerminalFont.system(name) ?: continue

            val rendered = render("text-symbols-$name", frame, fontSize = 24f, font = font)

            for (col in 0..2) assertEquals(false, rendered.hasColor(col, 0), "$name: the symbol of the cell $col is a picture")
            // A font that has the symbol itself draws it, even when the emoji is asked for: that is its business.
            if (font.fallback.familyFor("\u23F8") != null) assertTrue(rendered.hasColor(3, 0), "$name: the emoji that was asked for is not a picture")
        }
    }

    @Test
    fun whatIsSelectedIsDrawnInTheColorsOfTheTerminalTheOtherWayRound() {
        val frame = frame(30, listOf(row(30, *text("abcdef")))).copy(selection = listOf(1..3))

        val rendered = render("selection", frame)

        assertEquals(BACKGROUND.rgb, rendered.pixel(2, 2), "Not selected")
        // The selected cells have the foreground as background, and their text is in the background.
        assertEquals(FOREGROUND.rgb, rendered.pixel((1 * rendered.typography.cellWidth).toInt() + 1, 2))
        assertEquals(FOREGROUND.rgb, rendered.pixel((4 * rendered.typography.cellWidth).toInt() - 2, 2))
        assertEquals(BACKGROUND.rgb, rendered.pixel((4 * rendered.typography.cellWidth).toInt() + 1, 2), "The cell after is not")
        assertTrue(rendered.hasInk(2, 0, background = FOREGROUND), "The text of a selected cell shows on the selection")
        assertTrue(rendered.hasInk(0, 0), "The cell before has its text")
    }

    @Test
    fun whatIsSelectedIsDrawnInTheColorsOfTheTheme() {
        val selectedBackground = TerminalColor(0x335577)
        val selectedText = TerminalColor(0xFFEE00)
        val frame = frame(30, listOf(row(30, *text("████ ", foreground = RED))))
            .copy(selection = listOf(1..2), selectionColor = selectedBackground, selectedTextColor = selectedText)

        val rendered = render("selection-theme", frame)

        // A full block is entirely in the color of its text: the selected text color on the selected cells.
        assertEquals(selectedText.rgb, rendered.center(1, 0))
        assertEquals(selectedText.rgb, rendered.center(2, 0))
        assertEquals(RED.rgb, rendered.center(0, 0), "Not selected")
        assertEquals(RED.rgb, rendered.center(3, 0))
    }

    @Test
    fun whatIsSelectedKeepsItsOwnColorWhenTheThemeOnlyHasASelectionColor() {
        val selectedBackground = TerminalColor(0x335577)
        val frame = frame(30, listOf(row(30, *text("████ ", foreground = GREEN)))).copy(selection = listOf(1..2), selectionColor = selectedBackground)

        val rendered = render("selection-theme-background", frame)

        assertEquals(GREEN.rgb, rendered.center(1, 0))
        // The cell after the text, which is empty, is the selection color when it is selected.
        val spaced = frame(30, listOf(row(30, *text("██ ", foreground = GREEN)))).copy(selection = listOf(2..2), selectionColor = selectedBackground)
        assertEquals(selectedBackground.rgb, render("selection-theme-space", spaced).center(2, 0))
    }

    @Test
    fun theScrollbarIsAThumbOverTheRightEdgeWhereTheHistoryIs() {
        // At the top of 100 rows, 10 of them in view: the thumb is at the top, as short as it can be.
        val frame = frame(30, listOf(row(30, *text("text")))).copy(scrollbar = app.katty.terminal.TerminalScrollbar(total = 100, offset = 0, len = 10))

        val rendered = render("scrollbar", frame, scrollbarAlpha = 1f)

        // The middle of the thumb, 6 dp from the right edge, at 2 pixels to the dp.
        val x = 640 - 12
        assertNotEquals(BACKGROUND.rgb, rendered.pixel(x, 10), "The thumb is drawn at the top")
        assertEquals(BACKGROUND.rgb, rendered.pixel(x, 200), "And not where the history is not")
        assertEquals(BACKGROUND.rgb, rendered.pixel(x - 40, 10), "Nor beside it")
    }

    @Test
    fun theScrollbarIsStrongerWhenItIsUsed() {
        val frame = frame(30, listOf(row(30))).copy(scrollbar = app.katty.terminal.TerminalScrollbar(total = 100, offset = 0, len = 10))
        val x = 640 - 12

        val faint = render("scrollbar-faint", frame, scrollbarAlpha = 1f).pixel(x, 10)
        val strong = render("scrollbar-strong", frame, scrollbarAlpha = 1f, scrollbarEmphasized = true).pixel(x, 10)

        // Both are the color of the text over the background, the strong one more.
        assertTrue(strong > faint, "${"%06x".format(strong)} is not stronger than ${"%06x".format(faint)}")
    }

    @Test
    fun theScrollbarIsGoneOnceItHasFaded() {
        val frame = frame(30, listOf(row(30))).copy(scrollbar = app.katty.terminal.TerminalScrollbar(total = 100, offset = 0, len = 10))

        val rendered = render("scrollbar-faded", frame, scrollbarAlpha = 0f)

        assertEquals(BACKGROUND.rgb, rendered.pixel(640 - 12, 10))
    }

    // An image of 2 by 1 pixels, red and green, which is what the images of these tests are, and a placement of it.
    private val redGreen = byteArrayOf(-1, 0, 0, -1, 0, -1, 0, -1)

    private fun image(rgba: ByteArray = redGreen, width: Int = 2, height: Int = 1) = app.katty.terminal.TerminalImage(1, 1, width, height, rgba)

    private fun placement(
        image: app.katty.terminal.TerminalImage = image(),
        left: Float = 2f, top: Float = 1f, width: Float = 4f, height: Float = 2f,
        layer: app.katty.terminal.TerminalImageLayer = app.katty.terminal.TerminalImageLayer.ABOVE_TEXT,
        sourceX: Int = 0, sourceWidth: Int = image.width,
    ) = app.katty.terminal.TerminalImagePlacement(image, left, top, width, height, sourceX, 0, sourceWidth, image.height, layer)

    private val red = TerminalColor(0xFF0000)
    private val green = TerminalColor(0x00FF00)

    @Test
    fun anImageIsDrawnOnTheCellsItCovers() {
        val frame = frame(30, listOf(row(30), row(30), row(30), row(30))).copy(images = listOf(placement()))

        for (fontSize in listOf(14f, 22f)) {
            val rendered = render("image-$fontSize", frame, fontSize = fontSize)

            // 4 columns and 2 rows from the third column and the second row: red on the left, green on the right, and the
            // two pixels are blended in between, since it is scaled up smoothly.
            assertEquals(red.rgb, rendered.center(2, 1), "Red at $fontSize")
            assertEquals(red.rgb, rendered.center(2, 2))
            assertEquals(green.rgb, rendered.center(5, 1))
            assertEquals(green.rgb, rendered.center(5, 2))
            assertNotEquals(BACKGROUND.rgb, rendered.center(3, 1))
            assertNotEquals(BACKGROUND.rgb, rendered.center(4, 2))
            assertEquals(BACKGROUND.rgb, rendered.center(1, 1), "Not before it")
            assertEquals(BACKGROUND.rgb, rendered.center(6, 1), "Not after it")
            assertEquals(BACKGROUND.rgb, rendered.center(3, 0), "Not above it")
            assertEquals(BACKGROUND.rgb, rendered.center(3, 3), "Not under it")
        }
    }

    @Test
    fun anImageIsDrawnAtAFractionOfACell() {
        // From the middle of the third column to the middle of the fifth.
        val frame = frame(30, listOf(row(30), row(30))).copy(images = listOf(placement(left = 2.5f, top = 0f, width = 2f, height = 1f)))

        val rendered = render("image-fraction", frame)

        assertEquals(BACKGROUND.rgb, rendered.pixel((2.2f * rendered.typography.cellWidth).toInt(), 5), "Before it starts")
        assertEquals(red.rgb, rendered.pixel((3.0f * rendered.typography.cellWidth).toInt(), 5))
        assertEquals(green.rgb, rendered.pixel((4.0f * rendered.typography.cellWidth).toInt(), 5))
        assertEquals(BACKGROUND.rgb, rendered.pixel((4.8f * rendered.typography.cellWidth).toInt(), 5), "After it ends")
    }

    @Test
    fun theLayersOfImagesAreAroundTheTextAndTheBackgrounds() {
        // Full blocks of text, with a green background on one cell, under the images.
        val cells = arrayOf(cell("\u2588", foreground = BLUE), cell("\u2588", foreground = BLUE), cell(" ", background = GREEN), cell(" ", background = GREEN))
        val onTop = placement(left = 0f, top = 0f, width = 2f, height = 1f, layer = app.katty.terminal.TerminalImageLayer.ABOVE_TEXT)
        val underText = placement(left = 0f, top = 1f, width = 2f, height = 1f, layer = app.katty.terminal.TerminalImageLayer.BELOW_TEXT)
        val underBackground = placement(left = 2f, top = 0f, width = 2f, height = 1f, layer = app.katty.terminal.TerminalImageLayer.BELOW_BACKGROUND)
        val frame = frame(30, listOf(row(30, *cells), row(30, *cells))).copy(images = listOf(onTop, underText, underBackground))

        val rendered = render("image-layers", frame)

        assertEquals(red.rgb, rendered.center(0, 0), "Above the text: the image covers it")
        assertEquals(BLUE.rgb, rendered.center(0, 1), "Below the text: the text is on the image")
        assertEquals(GREEN.rgb, rendered.center(2, 0), "Below the background: the cell's background covers it")
    }

    @Test
    fun onlyTheSourceRectangleOfAnImageIsShown() {
        // The right pixel of the image only.
        val frame = frame(30, listOf(row(30), row(30))).copy(images = listOf(placement(left = 0f, top = 0f, width = 2f, height = 1f, sourceX = 1, sourceWidth = 1)))

        val rendered = render("image-source", frame)

        assertEquals(green.rgb, rendered.center(0, 0))
        assertEquals(green.rgb, rendered.center(1, 0))
    }

    @Test
    fun anImageThatIsPartlyAboveTheTerminalIsClipped() {
        // Starts one row above the first, and is two rows tall: it covers the first row only.
        val frame = frame(30, listOf(row(30), row(30), row(30))).copy(images = listOf(placement(left = 0f, top = -1f, width = 2f, height = 2f)))

        val rendered = render("image-clipped", frame)

        assertEquals(red.rgb, rendered.center(0, 0))
        assertEquals(BACKGROUND.rgb, rendered.center(0, 1))
    }

    @Test
    fun theTransparencyOfAnImageLetsWhatIsUnderItShow() {
        val half = image(byteArrayOf(-1, 0, 0, 128.toByte()), width = 1)
        val frame = frame(30, listOf(row(30), row(30))).copy(images = listOf(placement(half, left = 0f, top = 0f, width = 2f, height = 1f)))

        val rendered = render("image-alpha", frame)

        val pixel = rendered.center(0, 0)
        val red = pixel shr 16 and 0xFF
        val blue = pixel and 0xFF
        // Half red over the dark background: more red than it, less than pure red.
        assertTrue(red in 120..160, "Half red: ${"%06x".format(pixel)}")
        assertTrue(blue in 0x0E..0x20, "A bit of the background: ${"%06x".format(pixel)}")
    }

    @Test
    fun theBitmapsOfImagesAreMadeOnceAndForgottenWithTheImage() {
        val cache = ImageBitmapCache()
        val first = image()
        val second = app.katty.terminal.TerminalImage(2, 1, 2, 1, redGreen)

        val bitmap = cache.bitmapFor(first)

        assertSame(bitmap, cache.bitmapFor(first))
        cache.bitmapFor(second)
        assertEquals(2, cache.size)
        cache.retain(listOf(second))
        assertEquals(1, cache.size)
        assertNotEquals(bitmap, cache.bitmapFor(first), "A new bitmap once it was forgotten")
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
