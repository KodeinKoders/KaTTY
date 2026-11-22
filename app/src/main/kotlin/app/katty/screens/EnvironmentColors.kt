package app.katty.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.katty.core.BadgeTint
import app.katty.core.EnvironmentColor
import app.katty.ui.DialogScrim
import app.katty.ui.Katty
import app.katty.ui.KattyColors
import app.katty.ui.KattyMenu
import app.katty.ui.Pill
import app.katty.ui.hashedTint
import app.katty.ui.menu
import app.katty.ui.toColor
import app.katty.ui.uiScaleFactor
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField

/**
 * The badge of the environment [name] as the settings show it, in the color it was given ([chosen], `null` for the one its name falls on): a
 * click opens the choice of its color. Auto, a tint of the palette ([onChoose]), or a hex of the user's ([onCustom], which asks for it).
 */
@Composable
internal fun EnvironmentColorField(name: String, chosen: EnvironmentColor?, onChoose: (EnvironmentColor?) -> Unit, onCustom: () -> Unit) {
    val colors = Katty.colors
    var open by remember { mutableStateOf(false) }
    val auto = hashedTint(name)
    Box {
        Pill(
            name,
            (chosen ?: EnvironmentColor.Tint(auto)).toColor(colors),
            Modifier.clip(RoundedCornerShape(4.dp)).clickable(role = Role.Button) { open = true }
                .semantics { contentDescription = "Color of $name: ${chosen?.label ?: "Auto"}" },
            maxWidth = 360.dp,
        )
        if (open) {
            KattyMenu(
                menu {
                    // Auto says which tint the name falls on: the one it has without a choice.
                    action("Auto", hint = auto.label, checked = chosen == null, swatch = colors.badge(auto)) { onChoose(null) }
                    divider()
                    for (tint in BadgeTint.entries) {
                        val color = EnvironmentColor.Tint(tint)
                        action(tint.label, checked = chosen == color, swatch = colors.badge(tint)) { onChoose(color) }
                    }
                    divider()
                    val hex = chosen as? EnvironmentColor.Hex
                    action("Custom…", hint = hex?.label, checked = hex != null, swatch = hex?.toColor(colors)) { onCustom() }
                },
                onDismiss = { open = false },
            )
        }
    }
}

/**
 * Asks for the hex that the badges of the environment [name] are drawn in, from [current] when it already has one. The badge is shown with it on
 * the light and on the dark theme, since it is drawn as it is in both; Apply ([onChoose]) only takes a valid one.
 */
@Composable
internal fun EnvironmentColorDialog(name: String, current: EnvironmentColor?, onChoose: (EnvironmentColor.Hex) -> Unit, onClose: () -> Unit) {
    val colors = Katty.colors
    val field = rememberTextFieldState((current as? EnvironmentColor.Hex)?.encoded.orEmpty())
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val hex = EnvironmentColor.parseHex(field.text.toString())
    val apply = { if (hex != null) onChoose(hex) }
    val title = "Color of “$name”"
    DialogScrim(
        Modifier.onPreviewKeyEvent { event ->
            if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                onClose()
                true
            } else {
                false
            }
        },
        width = 420.dp * uiScaleFactor(),
        onDismiss = onClose,
    ) {
        Column(Modifier.semantics { contentDescription = title }) {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, fontSize = 16.sp, color = colors.text, fontWeight = FontWeight.SemiBold)
                Text("A hex color, such as #E08A00. It is drawn as it is in the light and the dark themes.", color = colors.muted)
                TextField(
                    field,
                    Modifier.fillMaxWidth().focusRequester(focus).semantics { contentDescription = "Hex color" }.onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && (event.key == Key.Enter || event.key == Key.NumPadEnter)) {
                            apply()
                            true
                        } else {
                            false
                        }
                    },
                    placeholder = { Text("#E08A00") },
                    textStyle = JewelTheme.defaultTextStyle.copy(fontFamily = FontFamily.Monospace),
                )
                if (field.text.isNotBlank() && hex == null) Text("Six hex digits, with or without #.", color = colors.err, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ColorPreview(name, hex, KattyColors.Light, "Light", Modifier.weight(1f))
                    ColorPreview(name, hex, KattyColors.Dark, "Dark", Modifier.weight(1f))
                }
            }
            Row(
                Modifier.fillMaxWidth().background(colors.side).padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(onClick = onClose) { Text("Cancel") }
                DefaultButton(onClick = apply, enabled = hex != null) { Text("Apply") }
            }
        }
    }
}

/** The badge of [name] in [hex] on the side of [theme], where most badges are: muted while there is no valid hex. */
@Composable
private fun ColorPreview(name: String, hex: EnvironmentColor.Hex?, theme: KattyColors, label: String, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(6.dp)
    Column(
        modifier.background(theme.side, shape).border(1.dp, theme.line, shape).padding(10.dp).semantics(mergeDescendants = true) { contentDescription = "$label preview" },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(label, color = theme.muted, fontSize = 11.sp)
        Pill(name, hex?.toColor(theme) ?: theme.muted, maxWidth = 360.dp)
    }
}
