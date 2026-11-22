package app.katty.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.katty.core.BadgeTint
import app.katty.core.EnvironmentBadge
import app.katty.core.EnvironmentColor
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Tooltip

/** The colors that the user gave the badges of environments, as the settings keep them (`Settings.environmentColors`): every window has them. */
val LocalEnvironmentColors = staticCompositionLocalOf<Map<String, String>> { emptyMap() }

/** The tint that the environment [name] falls on when it was given no color: always the same for a name (`String.hashCode` is the same on every run). */
fun hashedTint(name: String): BadgeTint = BadgeTint.entries[Math.floorMod(name.hashCode(), BadgeTint.entries.size)]

/** What [color] is drawn in, in [colors]: a tint in its color of the theme, a hex as it is. */
fun EnvironmentColor.toColor(colors: KattyColors): Color = when (this) {
    is EnvironmentColor.Tint -> colors.badge(tint)
    is EnvironmentColor.Hex -> Color(0xFF000000 or rgb.toLong())
}

/** The color of the badge of the environment [name]: the one of [chosen] (the settings' colors) when it has a valid one, else [hashedTint]'s. */
fun environmentColor(name: String, colors: KattyColors, chosen: Map<String, String> = emptyMap()): Color =
    (chosen[name]?.let(EnvironmentColor::decode) ?: EnvironmentColor.Tint(hashedTint(name))).toColor(colors)

/**
 * The badge of an environment: its name in a small pill of its color, muted for "standard" (no environment where its project has one), and red with
 * a tooltip that says why for one that has no file, whatever color it was given.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun EnvironmentPill(badge: EnvironmentBadge, modifier: Modifier = Modifier) {
    val colors = Katty.colors
    val color = when {
        badge.missing -> colors.err
        badge.label == EnvironmentBadge.STANDARD -> colors.muted
        else -> environmentColor(badge.label, colors, LocalEnvironmentColors.current)
    }
    val description = "Environment ${badge.label}" + if (badge.missing) ", not found" else ""
    val pill: @Composable () -> Unit = { Pill(badge.label, color, modifier.semantics { contentDescription = description }) }
    if (badge.missing) {
        Tooltip(tooltip = { Text("No file has the environment “${badge.label}”: its shells start without it.") }) { pill() }
    } else {
        pill()
    }
}

/**
 * [label] in a small pill of [color], cut at [maxWidth]: the shape of the badges of the environments, which the settings also draw to choose their
 * colors, wider there since they are where the names are read in full.
 */
@Composable
fun Pill(label: String, color: Color, modifier: Modifier = Modifier, maxWidth: Dp = 96.dp) {
    val shape = RoundedCornerShape(4.dp)
    Box(
        modifier.widthIn(max = maxWidth).clip(shape).background(color.copy(alpha = 0.14f)).border(1.dp, color.copy(alpha = 0.45f), shape)
            .padding(horizontal = 5.dp, vertical = 1.dp),
    ) {
        Text(label, color = color, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
