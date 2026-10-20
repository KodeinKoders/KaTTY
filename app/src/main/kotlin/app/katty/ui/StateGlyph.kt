package app.katty.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.katty.core.AgentState

/** The sizes of a state glyph: 12 px with its mark, and 9 px, in a row or a count, as a dot. */
enum class GlyphSize(val dp: Dp, val mark: Boolean) {
    NORMAL(12.dp, true),
    SMALL(9.dp, false),
}

/**
 * The glyph of a state: a ring that spins for Working (it does not spin when the system asks for less motion), a filled circle with an
 * exclamation mark for Needs input, with a check for Done, with a cross for a failure, and a hollow ring for Nothing.
 *
 * What it says is always told in words next to it or by the control that holds it: it has no description.
 *
 * @param spinning Whether Working is the ring that turns (the default), or false for a glyph that labels a group, such as a column of the board, which is
 * not itself working: it is then a filled circle with three dots, like the other states, and does not move.
 */
@Composable
fun StateGlyph(state: AgentState, modifier: Modifier = Modifier, size: GlyphSize = GlyphSize.NORMAL, spinning: Boolean = true) {
    val colors = Katty.colors
    val reduced = LocalReducedMotion.current
    val angle = if (state == AgentState.WORKING && spinning && !reduced) {
        val transition = rememberInfiniteTransition(label = "working")
        val turn by transition.animateFloat(0f, 360f, infiniteRepeatable(tween(1000, easing = LinearEasing), RepeatMode.Restart), label = "turn")
        turn
    } else {
        0f
    }
    val density = LocalDensity.current
    Canvas(modifier.size(size.dp).clearAndSetSemantics { }) {
        val side = this.size.minDimension
        when (state) {
            AgentState.IDLE -> ring(colors.idle, width = with(density) { 1.5.dp.toPx() }, side)
            AgentState.WORKING -> if (!spinning) {
                disc(colors.work, side)
                if (size.mark) ellipsis(colors.onStatus, side)
            } else {
                val width = with(density) { (if (size == GlyphSize.SMALL) 1.5.dp else 2.dp).toPx() }
                // A ring whose top is open, turned.
                rotate(angle) { arc(colors.work, width, side) }
            }
            AgentState.NEEDS_INPUT -> {
                disc(colors.input, side)
                if (size.mark) exclamation(colors.onStatus, side)
            }
            AgentState.DONE -> {
                disc(colors.done, side)
                if (size.mark) check(colors.onStatus, side)
            }
            AgentState.ERROR -> {
                disc(colors.err, side)
                if (size.mark) cross(colors.onStatus, side)
            }
        }
    }
}

private fun DrawScope.ring(color: Color, width: Float, side: Float) {
    drawCircle(color, radius = (side - width) / 2, style = Stroke(width))
}

// A ring that is open for a quarter at the top, as the design's border with a transparent top.
private fun DrawScope.arc(color: Color, width: Float, side: Float) {
    val inset = width / 2
    drawArc(color, startAngle = -45f, sweepAngle = 270f, useCenter = false, topLeft = Offset(inset, inset), size = Size(side - width, side - width), style = Stroke(width))
}

private fun DrawScope.disc(color: Color, side: Float) {
    drawCircle(color, radius = side / 2)
}

private fun DrawScope.exclamation(color: Color, side: Float) {
    val unit = side / 12f
    val width = 1.4f * unit
    drawLine(color, Offset(side / 2, 3f * unit), Offset(side / 2, 6.6f * unit), strokeWidth = width, cap = StrokeCap.Round)
    drawCircle(color, radius = 0.75f * unit, center = Offset(side / 2, 8.8f * unit))
}

private fun DrawScope.ellipsis(color: Color, side: Float) {
    val unit = side / 12f
    for (x in listOf(3.4f, 6f, 8.6f)) drawCircle(color, radius = 0.8f * unit, center = Offset(x * unit, 6f * unit))
}

private fun DrawScope.check(color: Color, side: Float) {
    val unit = side / 12f
    val path = Path().apply {
        moveTo(3.2f * unit, 6.2f * unit)
        lineTo(5.2f * unit, 8.2f * unit)
        lineTo(8.9f * unit, 3.9f * unit)
    }
    drawPath(path, color, style = Stroke(1.5f * unit, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

private fun DrawScope.cross(color: Color, side: Float) {
    val unit = side / 12f
    val stroke = 1.5f * unit
    drawLine(color, Offset(3.7f * unit, 3.7f * unit), Offset(8.3f * unit, 8.3f * unit), strokeWidth = stroke, cap = StrokeCap.Round)
    drawLine(color, Offset(8.3f * unit, 3.7f * unit), Offset(3.7f * unit, 8.3f * unit), strokeWidth = stroke, cap = StrokeCap.Round)
}
