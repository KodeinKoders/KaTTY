@file:OptIn(ExperimentalFoundationApi::class)

package app.katty.ui

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
) {
    Tooltip(tooltip = { Text(description) }) {
        IconButton(
            onClick = onClick,
            modifier = modifier.size(size.dp).semantics { contentDescription = description; role = Role.Button },
            enabled = enabled,
        ) {
            Icon(icon, tint)
        }
    }
}

/** One of the choices of a [Choice]. */
class ChoiceOption<T>(val value: T, val label: String)

/**
 * A choice among a few options drawn as a row of segments, as the layout and the theme are in the main menu. Jewel's segmented control is
 * the closest of its components.
 */
@Composable
fun <T> Choice(options: List<ChoiceOption<T>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    val buttons = options.map { option ->
        SegmentedControlButtonData(selected = option.value == selected, content = { Text(option.label) }, onSelect = { onSelect(option.value) })
    }
    SegmentedControl(buttons, modifier)
}

/** An on or off setting: Jewel has no toggle switch, so it is a checkbox, with its text beside it. */
@Composable
fun CheckSetting(text: String, checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    CheckboxRow(text, checked, onChange, modifier)
}
