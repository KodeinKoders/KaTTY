package app.katty.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.katty.core.AgentState
import org.jetbrains.jewel.ui.component.Text

/** A section's label in capitals: 11 px, weight 600, letter spacing 0.06 em, muted. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, color = Katty.colors.muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.06.em)
}

/** A key of a shortcut, as a cap: `⌘`, `⇧`, `K`. */
@Composable
fun KeyCap(label: String, modifier: Modifier = Modifier, onPrimary: Boolean = false) {
    val colors = Katty.colors
    Box(
        modifier.height(20.dp).widthIn(min = 20.dp).clip(RoundedCornerShape(4.dp))
            .background(if (onPrimary) Color.Transparent else colors.side)
            .border(1.dp, if (onPrimary) Color.White.copy(alpha = .5f) else colors.line, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (onPrimary) Color.White else colors.muted, fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

/** The caps of a shortcut written as `⌘⇧J`: one cap for each key. */
@Composable
fun Shortcut(keys: String, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        keys.split(' ').filter { it.isNotEmpty() }.forEach { KeyCap(it) }
    }
}

/** How many terminals of a project are in a state: the small glyph and the number. */
@Composable
fun StateCount(state: AgentState, count: Int, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
        StateGlyph(state, size = GlyphSize.SMALL)
        Text(count.toString(), color = Katty.colors.muted, fontSize = 11.sp)
    }
}

/** A count in a pill, as in the header of a column of the board. */
@Composable
fun CountPill(count: Int, modifier: Modifier = Modifier) {
    val colors = Katty.colors
    Box(
        modifier.heightIn(min = 20.dp).widthIn(min = 22.dp).clip(RoundedCornerShape(10.dp)).background(colors.hover).padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(count.toString(), color = colors.muted, fontSize = 12.sp)
    }
}

/**
 * The bar of an agent's usage: [percent] of it filled, in the working color, and in the amber of "needs input" from 80 %.
 * It says its value to a screen reader, and the text next to it says it to the eyes.
 */
@Composable
fun UsageBar(percent: Int, modifier: Modifier = Modifier, height: Dp = 4.dp) {
    val colors = Katty.colors
    val shown = percent.coerceIn(0, 100)
    Box(
        modifier.height(height).clip(RoundedCornerShape(height / 2)).background(colors.hover)
            .semantics { progressBarRangeInfo = ProgressBarRangeInfo(shown.toFloat(), 0f..100f) },
    ) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(shown / 100f).background(if (shown >= USAGE_WARNING_PERCENT) colors.input else colors.work))
    }
}

/** From which percent a usage bar warns. */
const val USAGE_WARNING_PERCENT = 80

/** A card of the board, a menu or a dialog: the panel's color, a border and the radius of what it is. */
@Composable
fun Surface(modifier: Modifier = Modifier, radius: Dp = 8.dp, content: @Composable () -> Unit) {
    val colors = Katty.colors
    Box(modifier.clip(RoundedCornerShape(radius)).background(colors.panel).border(BorderStroke(1.dp, colors.line), RoundedCornerShape(radius))) { content() }
}

/**
 * A dialog over the scrim that covers [modifier]'s area with 30 % of dark: its content is in the frame of 12 px radius, with the soft
 * shadow of menus and dialogs, centered.
 */
@Composable
fun DialogScrim(modifier: Modifier = Modifier, width: Dp = 560.dp, content: @Composable () -> Unit) {
    val colors = Katty.colors
    Box(modifier.fillMaxSize().background(Color(0xFF0E0F12).copy(alpha = .3f)), contentAlignment = Alignment.Center) {
        Box(Modifier.widthIn(max = width).fillMaxWidth().shadow(24.dp, RoundedCornerShape(12.dp)).clip(RoundedCornerShape(12.dp)).background(colors.panel)
            .border(1.dp, colors.line, RoundedCornerShape(12.dp))) {
            content()
        }
    }
}

/** A line of text that is cut with an ellipsis rather than wrapped. */
@Composable
fun OneLine(text: String, modifier: Modifier = Modifier, color: Color = Color.Unspecified, fontSize: androidx.compose.ui.unit.TextUnit = androidx.compose.ui.unit.TextUnit.Unspecified) {
    Text(text, modifier, color = color, fontSize = fontSize, maxLines = 1, overflow = TextOverflow.Ellipsis)
}
