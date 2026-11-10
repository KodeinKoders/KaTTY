package app.katty.terminal.compose

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import app.katty.terminal.TerminalCommandMark
import app.katty.terminal.TerminalCommandOutcome
import app.katty.terminal.TerminalFrame
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** The width of the gutter at the left of the terminal where the marks of the commands are: a bar of this width for each command. */
internal val COMMAND_MARK_WIDTH = 5.dp
private val COMMAND_MARK_RADIUS = 2.dp

// Two commands that follow each other are two bars: each stops this far from the end of its rows.
private val COMMAND_MARK_GAP = 1.dp

/**
 * The marks of the commands in the gutter, one bar for each from the first row of its prompt to the last of its output: the scheme's green when
 * the command succeeded, its red when it failed, and the color of the text, faint, while it runs or when the shell did not say how it went.
 * [hovered] is the one that the pointer is over, drawn stronger. A bar that goes on past the viewport is cut square there.
 *
 * @param left Where the gutter starts, in pixels from the left edge of the grid: negative, since it is in the padding.
 * @param width How wide it is.
 */
internal fun DrawScope.drawCommandMarks(frame: TerminalFrame, typography: TerminalTypography, left: Float, width: Float, hovered: Long?) {
    if (width <= 0f) return
    val radius = COMMAND_MARK_RADIUS.toPx()
    val gap = COMMAND_MARK_GAP.toPx()
    for (mark in frame.commandMarks) {
        val strong = mark.id == hovered
        val color = when (mark.outcome) {
            TerminalCommandOutcome.SUCCEEDED -> frame.successColor.rgb.toColor().copy(alpha = if (strong) 1f else 0.7f)
            TerminalCommandOutcome.FAILED -> frame.failureColor.rgb.toColor().copy(alpha = if (strong) 1f else 0.8f)
            TerminalCommandOutcome.STOPPED -> frame.stoppedColor.rgb.toColor().copy(alpha = if (strong) 1f else 0.8f)
            else -> frame.foreground.rgb.toColor().copy(alpha = if (strong) 0.7f else 0.3f)
        }
        val top = mark.top * typography.cellHeight + if (mark.startsAbove) 0f else gap
        val bottom = (mark.bottom + 1) * typography.cellHeight - if (mark.endsBelow) 0f else gap
        if (bottom <= top) continue
        drawRoundRect(color, Offset(left, top), Size(width, bottom - top), CornerRadius(radius))
        // Where it goes on past the viewport it is not rounded: it is not where the command starts or ends.
        if (mark.startsAbove) drawRect(color, Offset(left, top), Size(width, min(radius, bottom - top)))
        if (mark.endsBelow) drawRect(color, Offset(left, max(top, bottom - radius)), Size(width, min(radius, bottom - top)))
    }
}

/**
 * The command whose line is shown at the top of the terminal while its output is scrolled through: the command that the first row of the viewport
 * belongs to, when it started above the viewport and the viewport is in the history (not at the bottom, where what is shown is what the program writes
 * now). `null` when there is none, or when its line is not known.
 */
internal fun stickyCommand(frame: TerminalFrame): TerminalCommandMark? {
    // No scrollbar: nothing to scroll. At the bottom: the screen as it is, which the line would only cover.
    if (frame.scrollbar?.atBottom != false) return null
    return frame.commandMarks.firstOrNull { it.startsAbove && it.top == 0 && !it.command.isNullOrBlank() }
}

/** What is written of a command line at the top of the terminal: as many characters as the row has room for, the end cut with an ellipsis. */
internal fun stickyText(command: String, columns: Int): String {
    val room = (columns - 2).coerceAtLeast(1)
    return if (command.length <= room) command else command.take((room - 1).coerceAtLeast(0)) + "…"
}

/**
 * The line of a command over the first row of the viewport: the colors of the terminal, with a little of the text's over the background, and a line
 * under it. The bar of the command is still in the gutter, beside it.
 */
internal fun DrawScope.drawStickyCommand(frame: TerminalFrame, typography: TerminalTypography, layout: androidx.compose.ui.text.TextLayoutResult) {
    val foreground = frame.foreground.rgb.toColor()
    val background = frame.background.rgb.toColor()
    drawRect(androidx.compose.ui.graphics.lerp(background, foreground, 0.14f), Offset.Zero, Size(size.width, typography.cellHeight))
    drawRect(foreground.copy(alpha = 0.25f), Offset(0f, typography.cellHeight - 1f), Size(size.width, 1f))
    drawText(layout, color = foreground, topLeft = Offset(typography.cellWidth, 0f))
}

/**
 * Which command's bar the pointer is over, and what a click on it does. The bars are in the padding at the left of the grid, which is outside
 * what the terminal's own pointer handler sees: it is given the events of the whole terminal, padding included, by a handler of its own.
 *
 * Not thread-safe: it is used from the UI thread.
 */
internal class CommandMarkInteraction {
    /** The id of the mark that a click would select the output of. A Compose state: it is drawn. */
    var hovered by mutableStateOf<Long?>(null)
        private set

    /** The row of the grid that the pointer is on, which is where the tooltip goes. */
    var hoveredRow by mutableStateOf(0)
        private set

    // Where the pointer is, in pixels from the top left of the whole terminal (the padding included), and whether it is in it.
    private var x = 0f
    private var y = 0f
    private var inside = false

    /** The pointer left the terminal. */
    fun exit() {
        inside = false
        hovered = null
    }

    /**
     * Follows the pointer, and gives the mark that a **press of the left button** is on, or `null` for anything else.
     *
     * @param gutter The width of the gutter at the left, where the bars are.
     * @param top The height of the padding above the grid.
     */
    fun handle(
        x: Float,
        y: Float,
        press: Boolean,
        marks: List<TerminalCommandMark>,
        gutter: Float,
        top: Float,
        cellHeight: Float,
    ): TerminalCommandMark? {
        this.x = x
        this.y = y
        inside = true
        val mark = markAt(marks, gutter, top, cellHeight)
        if (hovered != mark?.id) hovered = mark?.id
        if (mark != null) hoveredRow = rowOf(top, cellHeight)
        return mark.takeIf { press }
    }

    /** The screen moved under a pointer that did not: what it is over may be another mark, or none. */
    fun refresh(marks: List<TerminalCommandMark>, gutter: Float, top: Float, cellHeight: Float) {
        if (!inside) return
        val id = markAt(marks, gutter, top, cellHeight)?.id
        if (hovered != id) hovered = id
    }

    private fun rowOf(top: Float, cellHeight: Float): Int = floor((y - top) / cellHeight).toInt()

    private fun markAt(marks: List<TerminalCommandMark>, gutter: Float, top: Float, cellHeight: Float): TerminalCommandMark? {
        if (x < 0f || x >= gutter || y < top || cellHeight <= 0f) return null
        val row = rowOf(top, cellHeight)
        return marks.firstOrNull { row in it.top..it.bottom }
    }
}

/** What the tooltip of a mark says: how the command went, when it started and how long it took (or has run). */
internal fun commandMarkLines(mark: TerminalCommandMark, now: Instant, zone: ZoneId = ZoneId.systemDefault()): List<String> {
    val how = when (mark.outcome) {
        TerminalCommandOutcome.RUNNING -> "Running"
        TerminalCommandOutcome.SUCCEEDED -> "Succeeded"
        TerminalCommandOutcome.FAILED -> "Failed: exit code ${mark.exitCode}"
        TerminalCommandOutcome.STOPPED -> "Stopped, in the background"
        TerminalCommandOutcome.ENDED -> "Ended"
    }
    val end = mark.finishedAt
    val took = when {
        end == null -> "Running for " + formatCommandDuration(Duration.between(mark.startedAt, now))
        mark.outcome == TerminalCommandOutcome.STOPPED -> "Ran for " + formatCommandDuration(Duration.between(mark.startedAt, end))
        else -> "Took " + formatCommandDuration(Duration.between(mark.startedAt, end))
    }
    return listOf(how, "Started " + COMMAND_TIME_FORMAT.format(mark.startedAt.atZone(zone)), took)
}

// Dates are written in ISO order whatever the language. The seconds are there: a command's time is measured in them.
private val COMMAND_TIME_FORMAT = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss")

/** A duration as a person says it: `0.4 s`, `12 s`, `1 min 12 s`, `2 h 5 min`; the part that is zero is left out. */
public fun formatCommandDuration(duration: Duration): String {
    val millis = duration.toMillis().coerceAtLeast(0)
    if (millis < 1000) return "%.1f s".format(java.util.Locale.ROOT, millis / 1000.0)
    val seconds = millis / 1000
    val hours = seconds / 3600
    val minutes = seconds % 3600 / 60
    val rest = seconds % 60
    return when {
        hours > 0 -> if (minutes > 0) "$hours h $minutes min" else "$hours h"
        minutes > 0 -> if (rest > 0) "$minutes min $rest s" else "$minutes min"
        else -> "$rest s"
    }
}

/**
 * The tooltip of a mark: a small box in the colors of the terminal the other way round, under the row that the pointer is on.
 *
 * @param gridInWindow Where the grid's top left is in the window.
 */
@Composable
internal fun CommandMarkTooltip(
    lines: List<String>,
    frame: TerminalFrame,
    row: Int,
    cellHeight: Float,
    gridInWindow: Offset,
    fontSize: TextUnit,
    gap: Dp = 6.dp,
) {
    val provider = object : PopupPositionProvider {
        override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
            val x = gridInWindow.x.toInt() + 4
            val y = (gridInWindow.y + (row + 1) * cellHeight).toInt()
            // Under the row, and not off the window.
            return IntOffset(x.coerceAtMost((windowSize.width - popupContentSize.width).coerceAtLeast(0)), y.coerceAtMost((windowSize.height - popupContentSize.height).coerceAtLeast(0)))
        }
    }
    Popup(popupPositionProvider = provider) {
        Column(
            Modifier
                .background(frame.foreground.rgb.toColor(), RoundedCornerShape(4.dp))
                .padding(horizontal = gap, vertical = 4.dp),
        ) {
            for (line in lines) BasicText(line, style = TextStyle(color = frame.background.rgb.toColor(), fontSize = fontSize))
        }
    }
}
