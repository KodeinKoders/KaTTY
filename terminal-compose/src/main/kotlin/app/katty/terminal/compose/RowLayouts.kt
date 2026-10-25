package app.katty.terminal.compose

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.TextLayoutResult
import app.katty.terminal.TerminalFrame
import app.katty.terminal.TerminalRow

internal fun Int.toColor(): Color = Color(0xFF000000.toInt() or this)

internal class PreparedText(val run: TextRun, val layout: TextLayoutResult)

/**
 * A character drawn as a shape: [sprite] is in the pixels of its cell, which starts at [left] and is [width] wide. Its polygons and strokes are
 * paths, made once from the top of the row (they are drawn moved to it) and kept with the row: they were made again at each drawing, which a
 * blinking cursor asks for twice a second.
 */
internal class PreparedSprite(val left: Float, val width: Float, val sprite: Sprite, val foreground: Int, val blink: Boolean = false) {
    // Made and used by the thread that draws.
    val polygonPaths: List<Path> by lazy(LazyThreadSafetyMode.NONE) {
        sprite.polygons.map { polygon ->
            Path().apply {
                polygon.forEachIndexed { index, (x, y) -> if (index == 0) moveTo(left + x, y) else lineTo(left + x, y) }
                close()
            }
        }
    }

    /** The paths of the strokes, with their widths. */
    val strokePaths: List<Pair<Path, Float>> by lazy(LazyThreadSafetyMode.NONE) {
        sprite.strokes.map { stroke ->
            Path().apply {
                for (command in stroke.commands) {
                    when (command) {
                        is PathCommand.MoveTo -> moveTo(left + command.x, command.y)
                        is PathCommand.LineTo -> lineTo(left + command.x, command.y)
                        is PathCommand.QuadTo -> quadraticTo(left + command.controlX, command.controlY, left + command.x, command.y)
                    }
                }
            } to stroke.width
        }
    }
}

/** Everything a row is drawn with, which is what is worth keeping from one frame to the next. */
internal class PreparedRow(
    val backgrounds: List<BackgroundRun>,
    val texts: List<PreparedText>,
    val sprites: List<PreparedSprite> = emptyList(),
)

/**
 * Prepares the rows of a frame, and remembers what it prepared for the rows that did not change.
 *
 * Laying text out is what costs, and a row that did not change is the same object in the next frame. The
 * entries of the rows that are no longer there are dropped.
 */
internal class RowLayouts(
    private val typography: TerminalTypography,
    private val minimumContrast: Float = 1f,
    private val builtinGlyphs: Boolean = true,
) {
    private var prepared: Map<TerminalRow, PreparedRow> = emptyMap()

    fun prepare(frame: TerminalFrame): List<PreparedRow> {
        val previous = prepared
        val rows = frame.rows.map { row -> previous[row] ?: prepareRow(row, frame) }
        prepared = frame.rows.indices.associate { frame.rows[it] to rows[it] }
        return rows
    }

    private fun prepareRow(row: TerminalRow, frame: TerminalFrame) = PreparedRow(
        backgrounds = backgroundRuns(row, frame.foreground, frame.background, frame.boldColor),
        texts = textRuns(row, frame.foreground, frame.background, frame.boldColor, minimumContrast, builtinGlyphs).map {
            PreparedText(it, typography.layout(it.text, it.bold, it.italic))
        },
        sprites = if (builtinGlyphs) prepareSprites(row, frame) else emptyList(),
    )

    // The edges of a cell are on whole pixels, as the backgrounds' are, so that the shapes of two cells meet.
    private fun prepareSprites(row: TerminalRow, frame: TerminalFrame): List<PreparedSprite> =
        spriteCells(row, frame.foreground, frame.background, frame.boldColor, minimumContrast).mapNotNull { cell ->
            val left = kotlin.math.round(cell.col * typography.cellWidth)
            val right = kotlin.math.round((cell.col + 1) * typography.cellWidth)
            val width = (right - left).toInt()
            sprite(cell.codepoint, width, typography.cellHeight.toInt())?.let { PreparedSprite(left, right - left, it, cell.foreground, cell.blink) }
        }
}
