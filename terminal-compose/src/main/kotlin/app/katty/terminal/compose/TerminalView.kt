package app.katty.terminal.compose

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.katty.terminal.TerminalCellWidth
import app.katty.terminal.TerminalCursor
import app.katty.terminal.TerminalCursorShape
import app.katty.terminal.TerminalFrame
import app.katty.terminal.TerminalImageLayer
import app.katty.terminal.TerminalScrollbar
import app.katty.terminal.TerminalUnderline
import kotlinx.coroutines.delay
import kotlin.math.max
import kotlin.math.round

private const val CURSOR_BLINK_MS = 530L

/**
 * Draws a frame: a pure function of what it is given, which is what makes it possible to draw one that
 * does not come from a session.
 */
@Composable
internal fun TerminalView(
    frame: TerminalFrame,
    typography: TerminalTypography,
    focused: Boolean,
    modifier: Modifier = Modifier,
    preedit: String = "",
    scrollbarAlpha: Float = 0f,
    scrollbarEmphasized: Boolean = false,
) {
    // What is kept for the rows depends on how they are drawn.
    val layouts = remember(typography, frame.foreground, frame.background, frame.boldColor) { RowLayouts(typography) }
    val cursorShown = rememberCursorBlink(frame.cursor, focused)

    val bitmaps = remember { ImageBitmapCache() }

    Canvas(modifier) {
        drawRect(frame.background.rgb.toColor())

        bitmaps.retain(frame.images.map { it.image })
        // The layers of the images are between what the cells are drawn with, as the protocol says.
        drawImages(frame, TerminalImageLayer.BELOW_BACKGROUND, bitmaps, typography)

        val rows = layouts.prepare(frame)
        rows.forEachIndexed { y, row ->
            val top = y * typography.cellHeight
            for (background in row.backgrounds) {
                drawCells(background.startCol, background.cols, top, typography, background.rgb.toColor())
            }
        }
        drawImages(frame, TerminalImageLayer.BELOW_TEXT, bitmaps, typography)
        rows.forEachIndexed { y, row ->
            val top = y * typography.cellHeight
            for (text in row.texts) {
                drawPreparedText(text, top, typography)
            }
        }
        drawImages(frame, TerminalImageLayer.ABOVE_TEXT, bitmaps, typography)

        if (frame.selection.isNotEmpty()) drawSelection(frame, rows, typography)

        frame.scrollbar?.takeIf { scrollbarAlpha > 0f }?.let { drawScrollbar(it, frame, scrollbarAlpha, scrollbarEmphasized) }

        if (preedit.isNotEmpty()) {
            // The cursor would be in the way of what is being composed.
            drawPreedit(frame, typography, preedit)
        } else if (cursorShown.value) {
            drawCursor(frame, typography, focused)
        }
    }
}

/**
 * Whether the cursor is shown: always, unless it blinks. It blinks only in a focused terminal, and starts
 * over, with the cursor shown, whenever it moves.
 */
@Composable
private fun rememberCursorBlink(cursor: TerminalCursor, focused: Boolean): State<Boolean> {
    val shown = remember { mutableStateOf(true) }
    LaunchedEffect(cursor, focused) {
        shown.value = true
        if (cursor.blinking && focused) {
            while (true) {
                delay(CURSOR_BLINK_MS)
                shown.value = !shown.value
            }
        }
    }
    return shown
}

// The edges of what is filled are on whole pixels, or the cells would be separated by thin lines of
// the background that show through where two neighbors are blended.
private fun DrawScope.drawCells(startCol: Int, cols: Int, top: Float, typography: TerminalTypography, color: Color) {
    val left = round(startCol * typography.cellWidth)
    val right = round((startCol + cols) * typography.cellWidth)
    drawRect(color, topLeft = Offset(left, top), size = Size(right - left, typography.cellHeight))
}

/**
 * The images of a layer, on the cells that they cover. They are **clipped to the terminal**: one that is partly scrolled out
 * at the top must not be drawn over what is above it.
 *
 * The edges are on whole pixels, as the cells' are, or the image would shimmer as the window is resized.
 */
private fun DrawScope.drawImages(frame: TerminalFrame, layer: TerminalImageLayer, bitmaps: ImageBitmapCache, typography: TerminalTypography) {
    if (frame.images.none { it.layer == layer }) return
    clipRect(0f, 0f, size.width, size.height) {
        for (placement in frame.images) {
            if (placement.layer != layer) continue
            val image = placement.image
            val left = round(placement.left * typography.cellWidth)
            val top = round(placement.top * typography.cellHeight)
            val right = round((placement.left + placement.width) * typography.cellWidth)
            val bottom = round((placement.top + placement.height) * typography.cellHeight)
            if (right <= left || bottom <= top) continue
            val sourceWidth = if (placement.sourceWidth > 0) placement.sourceWidth else image.width
            val sourceHeight = if (placement.sourceHeight > 0) placement.sourceHeight else image.height
            drawImage(
                image = bitmaps.bitmapFor(image),
                srcOffset = IntOffset(placement.sourceX, placement.sourceY),
                srcSize = IntSize(sourceWidth, sourceHeight),
                dstOffset = IntOffset(left.toInt(), top.toInt()),
                dstSize = IntSize((right - left).toInt(), (bottom - top).toInt()),
                filterQuality = FilterQuality.Medium,
            )
        }
    }
}

/** The width of the strip along the right edge where the scrollbar is, and what the pointer in it is for. */
internal val SCROLLBAR_STRIP_WIDTH = 12.dp
private val SCROLLBAR_THUMB_WIDTH = 6.dp
private val SCROLLBAR_THUMB_MARGIN = 3.dp
internal val SCROLLBAR_MIN_THUMB_HEIGHT = 24.dp

/**
 * The scrollbar, over the text: a thumb in the color of the text, faint, and stronger when it is used.
 */
private fun DrawScope.drawScrollbar(scrollbar: TerminalScrollbar, frame: TerminalFrame, alpha: Float, emphasized: Boolean) {
    val geometry = ScrollbarGeometry(size.height, scrollbar, SCROLLBAR_MIN_THUMB_HEIGHT.toPx())
    val thumbWidth = SCROLLBAR_THUMB_WIDTH.toPx()
    drawRoundRect(
        color = frame.foreground.rgb.toColor().copy(alpha = alpha * (if (emphasized) 0.6f else 0.35f)),
        topLeft = Offset(size.width - thumbWidth - SCROLLBAR_THUMB_MARGIN.toPx(), geometry.thumbTop),
        size = Size(thumbWidth, geometry.thumbHeight),
        cornerRadius = CornerRadius(thumbWidth / 2),
    )
}

/**
 * What is selected: its cells are filled with the selection color, and the text that is in them is drawn again, in
 * the color of selected text. Without a theme that has them, it is the colors of the terminal the other way round.
 */
private fun DrawScope.drawSelection(frame: TerminalFrame, rows: List<PreparedRow>, typography: TerminalTypography) {
    val background = (frame.selectionColor ?: frame.foreground).rgb.toColor()
    // The text keeps its own color on a selection color of the theme, unless the theme says how selected text is drawn.
    val foreground = (frame.selectedTextColor ?: if (frame.selectionColor == null) frame.background else null)?.rgb?.toColor()
    for ((y, range) in frame.selection.withIndex()) {
        if (range == null || y !in rows.indices) continue
        val top = y * typography.cellHeight
        val left = round(range.first * typography.cellWidth)
        val right = round((range.last + 1) * typography.cellWidth)
        clipRect(left, top, right, top + typography.cellHeight) {
            drawRect(background, Offset(left, top), Size(right - left, typography.cellHeight))
            for (text in rows[y].texts) drawPreparedText(text, top, typography, foreground)
        }
    }
}

private fun DrawScope.drawPreparedText(text: PreparedText, top: Float, typography: TerminalTypography, colorOverride: Color? = null) {
    val run = text.run
    val left = run.startCol * typography.cellWidth
    val color = colorOverride ?: run.foreground.toColor()
    drawText(text.layout, color = color, topLeft = Offset(left, top))

    val right = (run.startCol + run.cols) * typography.cellWidth
    val thickness = max(1f, round(typography.cellHeight / 16f))
    val baseline = top + text.layout.firstBaseline
    if (run.underline != TerminalUnderline.NONE) {
        drawUnderline(run.underline, left, right, baseline + thickness, thickness, color)
    }
    if (run.strikethrough) {
        val y = baseline - typography.cellHeight * 0.3f
        drawLine(color, Offset(left, y), Offset(right, y), thickness)
    }
    if (run.overline) {
        drawLine(color, Offset(left, top + thickness / 2), Offset(right, top + thickness / 2), thickness)
    }
}

private fun DrawScope.drawUnderline(kind: TerminalUnderline, left: Float, right: Float, y: Float, thickness: Float, color: Color) {
    when (kind) {
        TerminalUnderline.NONE -> Unit
        TerminalUnderline.SINGLE -> drawLine(color, Offset(left, y), Offset(right, y), thickness)
        TerminalUnderline.DOUBLE -> {
            drawLine(color, Offset(left, y), Offset(right, y), thickness)
            drawLine(color, Offset(left, y + thickness * 2), Offset(right, y + thickness * 2), thickness)
        }
        TerminalUnderline.DOTTED -> drawLine(
            color, Offset(left, y), Offset(right, y), thickness,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(thickness, thickness)),
        )
        TerminalUnderline.DASHED -> drawLine(
            color, Offset(left, y), Offset(right, y), thickness,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(thickness * 4, thickness * 2)),
        )
        TerminalUnderline.CURLY -> {
            // A wave, one period every two thicknesses of height.
            val amplitude = thickness
            val half = thickness * 2
            val path = Path().apply {
                moveTo(left, y)
                var x = left
                var up = true
                while (x < right) {
                    val next = minOf(x + half, right)
                    quadraticTo((x + next) / 2, y + if (up) amplitude else -amplitude, next, y)
                    x = next
                    up = !up
                }
            }
            drawPath(path, color, style = Stroke(width = thickness))
        }
    }
}

/**
 * The text that is being composed, in the cells from the cursor's, underlined: it is not in the terminal yet.
 * It covers what is under it.
 */
private fun DrawScope.drawPreedit(frame: TerminalFrame, typography: TerminalTypography, preedit: String) {
    val cursor = frame.cursor
    val cursorCol = cursor.x ?: return
    val y = cursor.y ?: return
    if (y !in frame.rows.indices) return

    val cells = preeditCells(preedit)
    var col = preeditStart(cursorCol, cells.sumOf { it.cols }, frame.cols)
    val top = y * typography.cellHeight
    val thickness = max(1f, round(typography.cellHeight / 16f))
    val color = frame.foreground.rgb.toColor()
    for (cell in cells) {
        drawCells(col, cell.cols, top, typography, frame.background.rgb.toColor())
        val layout = typography.layout(cell.text, bold = false, italic = false)
        val left = col * typography.cellWidth
        drawText(layout, color = color, topLeft = Offset(left, top))
        val right = (col + cell.cols) * typography.cellWidth
        drawUnderline(TerminalUnderline.SINGLE, left, right, top + layout.firstBaseline + thickness, thickness, color)
        col += cell.cols
    }
}

private fun DrawScope.drawCursor(frame: TerminalFrame, typography: TerminalTypography, focused: Boolean) {
    val cursor = frame.cursor
    val col = cursor.x ?: return
    val y = cursor.y ?: return
    if (!cursor.visible || y !in frame.rows.indices) return

    val cell = frame.rows[y].cells.getOrNull(col)
    val cols = if (cell?.width == TerminalCellWidth.WIDE) 2 else 1
    val color = (frame.cursorColor ?: frame.foreground).rgb.toColor()
    val top = y * typography.cellHeight
    val left = round(col * typography.cellWidth)
    val width = round((col + cols) * typography.cellWidth) - left
    val thickness = max(1f, round(typography.cellHeight / 8f))

    // A block that does not have the focus is an outline: it is not where the keys go.
    val shape = if (cursor.shape == TerminalCursorShape.BLOCK && !focused) TerminalCursorShape.BLOCK_HOLLOW else cursor.shape
    when (shape) {
        TerminalCursorShape.BLOCK -> {
            drawRect(color, Offset(left, top), Size(width, typography.cellHeight))
            // The character under it, in the color that the theme gives it, or else the one of the background:
            // the other way round it would not show.
            if (cell != null && cell.text.isNotEmpty()) {
                val layout = typography.layout(cell.text, cell.style.bold, cell.style.italic)
                val textColor = (frame.cursorTextColor ?: frame.background).rgb.toColor()
                drawText(layout, color = textColor, topLeft = Offset(col * typography.cellWidth, top))
            }
        }
        TerminalCursorShape.BLOCK_HOLLOW -> drawRoundRect(
            color,
            Offset(left + 0.5f, top + 0.5f),
            Size(width - 1f, typography.cellHeight - 1f),
            cornerRadius = CornerRadius.Zero,
            style = Stroke(width = max(1f, round(typography.cellHeight / 16f))),
        )
        TerminalCursorShape.BAR -> drawRect(color, Offset(left, top), Size(thickness, typography.cellHeight))
        TerminalCursorShape.UNDERLINE -> drawRect(
            color,
            Offset(left, top + typography.cellHeight - thickness),
            Size(width, thickness),
        )
    }
}
