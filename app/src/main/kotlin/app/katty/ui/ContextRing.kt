package app.katty.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.katty.core.SessionUsage
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Tooltip
import java.util.Locale

/** From this much of its context on, a conversation is drawn as one that will soon be compacted or cut. */
const val CONTEXT_WARNING_PERCENT = 80

/** From this much on, it is drawn as one that is about to be. */
const val CONTEXT_DANGER_PERCENT = 95

/**
 * How full a conversation's context is, as a thin ring that fills clockwise from the top, as the Claude app draws it: the color of the text
 * around it, then the warning and the danger colors near the end. Its size is in `sp`, so that it grows with the text of the application
 * next to it. It says nothing to the accessibility tree by itself: [SessionUsageBadge] does.
 */
@Composable
fun ContextRing(percent: Int, modifier: Modifier = Modifier, size: TextUnit = 12.sp) {
    val colors = Katty.colors
    val shown = percent.coerceIn(0, 100)
    val arc = when {
        shown >= CONTEXT_DANGER_PERCENT -> colors.err
        shown >= CONTEXT_WARNING_PERCENT -> colors.input
        else -> colors.muted
    }
    val diameter = with(LocalDensity.current) { size.toDp() }
    Canvas(modifier.size(diameter)) {
        val stroke = (this.size.minDimension * 0.16f).coerceAtLeast(1.5f)
        val inset = stroke / 2
        val box = Size(this.size.width - stroke, this.size.height - stroke)
        // The track is the text's color, faint: the border color is lost on the panels of the dark theme.
        drawArc(colors.muted.copy(alpha = 0.3f), 0f, 360f, useCenter = false, topLeft = Offset(inset, inset), size = box, style = Stroke(stroke))
        // A context that has something in it shows at least a sliver.
        val sweep = if (shown == 0) 0f else (360f * shown / 100).coerceAtLeast(12f)
        if (sweep > 0) drawArc(arc, -90f, sweep, useCenter = false, topLeft = Offset(inset, inset), size = box, style = Stroke(stroke, cap = StrokeCap.Round))
    }
}

/** A cost as the user reads it: two decimals, and a sign that it is under a cent. */
fun formatUsd(usd: Double): String = if (usd > 0 && usd < 0.01) "<$0.01" else "$" + String.format(Locale.US, "%.2f", usd)

/**
 * What a conversation has used, next to the agent's name: the ring of its context, and its cost when the agent counts it. Nothing when it
 * knows neither.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun SessionUsageBadge(usage: SessionUsage?, modifier: Modifier = Modifier) {
    if (usage == null || (usage.contextPercent == null && usage.costUsd == null)) return
    val colors = Katty.colors
    val description = listOfNotNull(
        usage.contextPercent?.let { "$it% of context used" },
        usage.costUsd?.let { "${formatUsd(it)} spent in this session" },
    ).joinToString(", ")
    Tooltip(tooltip = { Text(description) }) {
        Row(modifier.semantics { contentDescription = description }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            usage.contextPercent?.let { ContextRing(it) }
            usage.costUsd?.let { Text(formatUsd(it), color = colors.muted, fontSize = 11.sp, maxLines = 1) }
        }
    }
}
