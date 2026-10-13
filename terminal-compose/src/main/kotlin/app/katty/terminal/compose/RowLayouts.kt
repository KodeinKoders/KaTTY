package app.katty.terminal.compose

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextLayoutResult
import app.katty.terminal.TerminalFrame
import app.katty.terminal.TerminalRow

internal fun Int.toColor(): Color = Color(0xFF000000.toInt() or this)

internal class PreparedText(val run: TextRun, val layout: TextLayoutResult)

/** Everything a row is drawn with, which is what is worth keeping from one frame to the next. */
internal class PreparedRow(
    val backgrounds: List<BackgroundRun>,
    val texts: List<PreparedText>,
)

/**
 * Prepares the rows of a frame, and remembers what it prepared for the rows that did not change.
 *
 * Laying text out is what costs, and a row that did not change is the same object in the next frame. The
 * entries of the rows that are no longer there are dropped.
 */
internal class RowLayouts(private val typography: TerminalTypography) {
    private var prepared: Map<TerminalRow, PreparedRow> = emptyMap()

    fun prepare(frame: TerminalFrame): List<PreparedRow> {
        val previous = prepared
        val rows = frame.rows.map { row -> previous[row] ?: prepareRow(row, frame) }
        prepared = frame.rows.indices.associate { frame.rows[it] to rows[it] }
        return rows
    }

    private fun prepareRow(row: TerminalRow, frame: TerminalFrame) = PreparedRow(
        backgrounds = backgroundRuns(row, frame.foreground, frame.background),
        texts = textRuns(row, frame.foreground, frame.background).map {
            PreparedText(it, typography.layout(it.text, it.bold, it.italic))
        },
    )
}
