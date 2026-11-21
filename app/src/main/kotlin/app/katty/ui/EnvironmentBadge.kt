package app.katty.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.katty.core.EnvironmentBadge
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Tooltip

/** The color of the badge of the environment [name]: always the same for a name (`String.hashCode` is the same on every run), one of [KattyColors.badges]. */
fun environmentColor(name: String, colors: KattyColors): Color = colors.badges[Math.floorMod(name.hashCode(), colors.badges.size)]

/**
 * The badge of an environment: its name in a small pill of its color, muted for "standard" (no environment where its project has one), and red with
 * a tooltip that says why for one that has no file.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun EnvironmentPill(badge: EnvironmentBadge, modifier: Modifier = Modifier) {
    val colors = Katty.colors
    val color = when {
        badge.missing -> colors.err
        badge.label == EnvironmentBadge.STANDARD -> colors.muted
        else -> environmentColor(badge.label, colors)
    }
    val shape = RoundedCornerShape(4.dp)
    val description = "Environment ${badge.label}" + if (badge.missing) ", not found" else ""
    val pill: @Composable () -> Unit = {
        Box(
            modifier.widthIn(max = 96.dp).clip(shape).background(color.copy(alpha = 0.14f)).border(1.dp, color.copy(alpha = 0.45f), shape)
                .semantics { contentDescription = description }
                .padding(horizontal = 5.dp, vertical = 1.dp),
        ) {
            Text(badge.label, color = color, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    if (badge.missing) {
        Tooltip(tooltip = { Text("No file has the environment “${badge.label}”: its shells start without it.") }) { pill() }
    } else {
        pill()
    }
}
