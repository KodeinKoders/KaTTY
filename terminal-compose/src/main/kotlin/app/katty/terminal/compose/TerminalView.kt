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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.drawText
import app.katty.terminal.TerminalCellWidth
import app.katty.terminal.TerminalCursor
import app.katty.terminal.TerminalCursorShape
import app.katty.terminal.TerminalFrame
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
) {
    // What is kept for the rows depends on how they are drawn.
    val layouts = remember(typography, frame.foreground, frame.background) { RowLayouts(typography) }
    val cursorShown = rememberCursorBlink(frame.cursor, focused)

    Canvas(modifier) {
        drawRect(frame.background.rgb.toColor())

        val rows = layouts.prepare(frame)
        rows.forEachIndexed { y, row ->
            val top = y * typography.cellHeight
            for (background in row.backgrounds) {
                drawCells(background.startCol, background.cols, top, typography, background.rgb.toColor())
            }
            for (text in row.texts) {
                drawPreparedText(text, top, typography)
            }
        }

        if (cursorShown.value) drawCursor(frame, typography, focused)
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

private fun DrawScope.drawPreparedText(text: PreparedText, top: Float, typography: TerminalTypography) {
    val run = text.run
    val left = run.startCol * typography.cellWidth
    val color = run.foreground.toColor()
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
            // The character under it, in the color of the background: the other way round it would not show.
            if (cell != null && cell.text.isNotEmpty()) {
                val layout = typography.layout(cell.text, cell.style.bold, cell.style.italic)
                drawText(layout, color = frame.background.rgb.toColor(), topLeft = Offset(col * typography.cellWidth, top))
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
