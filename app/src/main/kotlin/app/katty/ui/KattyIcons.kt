package app.katty.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * An icon of the design: shapes on a 16 by 16 grid, drawn as outlines with round ends, or filled for the dots. They are the design's own,
 * so that they look as drawn there; Jewel's icons are the ones of IntelliJ.
 *
 * @param strokes The outlines, as SVG path data.
 * @param fills The shapes that are filled, as SVG path data.
 * @param grid The side of the grid the shapes are on.
 */
class KattyIcon(val strokes: List<String>, val fills: List<String> = emptyList(), val grid: Float = 16f, val strokeWidth: Float = 1.3f)

// The SVG of a rectangle and of a circle, as path data.
private fun rect(x: Float, y: Float, w: Float, h: Float, r: Float): String =
    "M${x + r} ${y}h${w - 2 * r}a$r $r 0 0 1 $r ${r}v${h - 2 * r}a$r $r 0 0 1 -$r ${r}h-${w - 2 * r}a$r $r 0 0 1 -$r -${r}v-${h - 2 * r}a$r $r 0 0 1 $r -${r}z"

private fun circle(cx: Float, cy: Float, r: Float): String = "M${cx - r} ${cy}a$r $r 0 1 0 ${2 * r} 0a$r $r 0 1 0 -${2 * r} 0z"

object KattyIcons {
    val SidebarToggle = KattyIcon(listOf(rect(2f, 3f, 12f, 10f, 2f), "M6 3v10"))
    val Search = KattyIcon(listOf("M7 12.5A5.5 5.5 0 1 0 7 1.5a5.5 5.5 0 0 0 0 11zM11 11l3.5 3.5"))
    val Bell = KattyIcon(listOf("M4 11V7a4 4 0 0 1 8 0v4l1 1.5H3zM6.5 14h3"))
    val Menu = KattyIcon(listOf("M2.5 4.5h11M2.5 8h11M2.5 11.5h11"))
    val ChevronDown = KattyIcon(listOf("M4 6l4 4 4-4"))
    val ChevronRight = KattyIcon(listOf("M6 4l4 4-4 4"))
    val ChevronUp = KattyIcon(listOf("M4 10l4-4 4 4"))
    val Plus = KattyIcon(listOf("M8 3v10M3 8h10"))
    val Folder = KattyIcon(listOf("M2 4.5A1.5 1.5 0 0 1 3.5 3H6l1.5 1.5h5A1.5 1.5 0 0 1 14 6v5.5a1.5 1.5 0 0 1-1.5 1.5h-9A1.5 1.5 0 0 1 2 11.5z"))
    val FolderPlus = KattyIcon(listOf("M2 4.5A1.5 1.5 0 0 1 3.5 3H6l1.5 1.5h5A1.5 1.5 0 0 1 14 6v5.5a1.5 1.5 0 0 1-1.5 1.5h-9A1.5 1.5 0 0 1 2 11.5zM8 7v4M6 9h4"))
    val Kebab = KattyIcon(emptyList(), listOf(circle(3.5f, 8f, 1.2f), circle(8f, 8f, 1.2f), circle(12.5f, 8f, 1.2f)))
    val SplitRight = KattyIcon(listOf(rect(2f, 3f, 12f, 10f, 2f), "M8 3v10"))
    val SplitDown = KattyIcon(listOf(rect(2f, 3f, 12f, 10f, 2f), "M2 8h12"))
    val Branch = KattyIcon(listOf(circle(4.5f, 3.5f, 1.5f), circle(4.5f, 12.5f, 1.5f), circle(11.5f, 6f, 1.5f), "M4.5 5v6M11.5 7.5c0 2.5-3 2.5-7 3.5"), strokeWidth = 1.4f)
    val Worktree = KattyIcon(listOf(rect(2f, 2.5f, 4f, 3f, .8f), rect(8.5f, 6.5f, 5f, 3f, .8f), rect(8.5f, 11f, 5f, 3f, .8f), "M4 5.5v6h4.5M4 8h4.5"), strokeWidth = 1.4f)
    val Maximize = KattyIcon(listOf("M3 6V3h3M13 6V3h-3M3 10v3h3M13 10v3h-3"))
    val Restore = KattyIcon(listOf("M6 3v3H3M10 3v3h3M6 13v-3H3M10 13v-3h3"))
    val Close = KattyIcon(listOf("M4 4l8 8M12 4l-8 8"), strokeWidth = 1.4f)
    val Board = KattyIcon(listOf(rect(2f, 3f, 5f, 10f, 1f), rect(9f, 3f, 5f, 6f, 1f)))
    val Check = KattyIcon(listOf("M3.5 8.5l3 3 6-7"), strokeWidth = 1.5f)
    val Pencil = KattyIcon(listOf("M10.5 3.5l2 2L6 12l-3 .8.8-3z"))
    val ArrowRight = KattyIcon(listOf("M3 8h9M9 5l3 3-3 3"), strokeWidth = 1.4f)
    val Palette = KattyIcon(listOf("M8 2.5a5.5 5.5 0 1 0 0 11c1 0 1.5-.6 1.5-1.3 0-.9-.7-1.2-.7-2 0-.7.6-1.2 1.4-1.2H12a2.5 2.5 0 0 0 2.5-2.5C14.5 4.2 11.7 2.5 8 2.5z"))
    val Copy = KattyIcon(listOf(rect(6f, 6f, 7f, 7f, 1.5f), "M10 6V4.5A1.5 1.5 0 0 0 8.5 3h-4A1.5 1.5 0 0 0 3 4.5v4A1.5 1.5 0 0 0 4.5 10H6"))
    val Restart = KattyIcon(listOf("M13 8a5 5 0 1 1-1.5-3.5M13 3v3h-3"))
    val Prompt = KattyIcon(listOf("M3 4.5l4 3.5-4 3.5M8.5 12h5"), strokeWidth = 1.5f)
    val WindowMinimize = KattyIcon(listOf("M1.5 6h9"), grid = 12f, strokeWidth = 1f)
    val WindowMaximize = KattyIcon(listOf("M2 2h8v8H2z"), grid = 12f, strokeWidth = 1f)
    val WindowClose = KattyIcon(listOf("M2 2l8 8M10 2l-8 8"), grid = 12f, strokeWidth = 1f)

    // What agents are drawn with.
    val Claude = KattyIcon(listOf("M8 2v12M2 8h12M3.8 3.8l8.4 8.4M12.2 3.8l-8.4 8.4"), strokeWidth = 1.5f)
    val Codex = KattyIcon(listOf("M8 2l5 3v6l-5 3-5-3V5z"), strokeWidth = 1.5f)
    val Antigravity = KattyIcon(listOf("M3 13L8 3l5 10M5.5 9.5h5"), strokeWidth = 1.5f)
    val Gemini = KattyIcon(listOf("M8 2c.5 3.5 2.5 5.5 6 6-3.5.5-5.5 2.5-6 6-.5-3.5-2.5-5.5-6-6 3.5-.5 5.5-2.5 6-6z"), strokeWidth = 1.5f)
    val Copilot = KattyIcon(listOf("M3 6a3 3 0 0 1 3-3h4a3 3 0 0 1 3 3v3a3 3 0 0 1-3 3H6a3 3 0 0 1-3-3zM6 7.5v1M10 7.5v1"), strokeWidth = 1.5f)
}

/** Draws [icon] in [size], in [tint]. It has no description of its own: a button that holds it says what it does. */
@Composable
fun Icon(icon: KattyIcon, tint: Color, modifier: Modifier = Modifier, size: Dp = 16.dp) {
    val strokes = remember(icon) { icon.strokes.map { PathParser().parsePathString(it).toPath() } }
    val fills = remember(icon) { icon.fills.map { PathParser().parsePathString(it).toPath() } }
    Canvas(modifier.size(size).clipToBounds()) {
        val factor = this.size.width / icon.grid
        scale(factor, factor, pivot = Offset.Zero) {
            val stroke = Stroke(width = icon.strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
            for (path in strokes) drawPath(path, tint, style = stroke)
            for (path in fills) drawPath(path, tint, style = Fill)
        }
    }
}

