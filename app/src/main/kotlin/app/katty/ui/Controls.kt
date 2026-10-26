@file:OptIn(ExperimentalFoundationApi::class)

package app.katty.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.jetbrains.jewel.ui.component.CheckboxRow
import org.jetbrains.jewel.ui.component.IconButton
import org.jetbrains.jewel.ui.component.SegmentedControl
import org.jetbrains.jewel.ui.component.SegmentedControlButtonData
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Tooltip

/** The sizes of an icon button of the design: 28 px, and 24 px in the header of a pane. */
enum class ButtonSize(val dp: Dp) {
    NORMAL(28.dp),
    COMPACT(24.dp),
}

/**
 * An icon button: Jewel's, with an icon of the design. [description] is what a screen reader says and what the tooltip shows, since an
 * icon alone does not say what it does.
 *
 * @param shortcut The keys that do the same, as `⌘,`: the tooltip says them after the description (a screen reader has the description alone).
 */
@Composable
fun KattyIconButton(
    icon: KattyIcon,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: ButtonSize = ButtonSize.NORMAL,
    tint: Color = Katty.colors.muted,
    enabled: Boolean = true,
    shortcut: String? = null,
) {
    Tooltip(tooltip = { Text(tooltipText(description, shortcut)) }) {
        IconButton(
            onClick = onClick,
            modifier = modifier.size(size.dp).semantics { contentDescription = description; role = Role.Button },
            enabled = enabled,
        ) {
            // A button that is disabled is grayed out: the icon says it does too.
            Icon(icon, if (enabled) tint else tint.copy(alpha = DISABLED_ICON_ALPHA))
        }
    }
}

/** What a tooltip says of a button: what it does, and the keys that do the same in parentheses, as macOS writes them. */
internal fun tooltipText(description: String, shortcut: String?): String =
    if (shortcut.isNullOrEmpty()) description else "$description ($shortcut)"

private const val DISABLED_ICON_ALPHA = 0.4f

/**
 * A small button with a word on it, outlined: unlike Jewel's, whose height is fixed, it grows with its text, so that it is not cut when the text of
 * the application is bigger. [description] is what a screen reader says (the word alone may not say what it does).
 */
@Composable
fun KattyTextButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, description: String = label, enabled: Boolean = true) {
    val colors = Katty.colors
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(5.dp)
    androidx.compose.foundation.layout.Box(
        modifier.heightIn(min = 26.dp).clip(shape).border(1.dp, colors.line, shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description }
            .padding(horizontal = 10.dp, vertical = 3.dp),
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) {
        Text(label, color = if (enabled) colors.text else colors.muted, fontSize = 12.sp, maxLines = 1)
    }
}

/** One of the choices of a [Choice]. */
class ChoiceOption<T>(val value: T, val label: String)

/**
 * A choice among a few options drawn as a row of segments, as the layout and the theme are in the main menu. Jewel's segmented control is
 * the closest of its components.
 */
@Composable
fun <T> Choice(options: List<ChoiceOption<T>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val buttons = options.map { option ->
        SegmentedControlButtonData(selected = option.value == selected, content = { Text(option.label) }, onSelect = { onSelect(option.value) })
    }
    SegmentedControl(buttons, modifier, enabled = enabled)
}

/** An on or off setting: Jewel has no toggle switch, so it is a checkbox, with its text beside it. */
@Composable
fun CheckSetting(text: String, checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    CheckboxRow(text, checked, onChange, modifier, enabled = enabled)
}
