package app.katty.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.selectAll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.katty.ui.DialogScrim
import app.katty.ui.Katty
import app.katty.ui.uiScaleFactor
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField

/**
 * Edits the command that asks the AI something: the field starts with the one in use ([current]), and says what the default is ([default], the one of
 * the first installed agent, `null` when none is installed). Enter or Save keeps what is typed (a blank field is the default), "Use default" goes back
 * to following what is installed, Escape cancels.
 *
 * @param onSave The command to keep, `null` for the default.
 */
@Composable
internal fun AiCommandDialog(current: String?, default: String?, onSave: (String?) -> Unit, onCancel: () -> Unit) {
    val colors = Katty.colors
    val state = rememberTextFieldState(current.orEmpty())
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        focus.requestFocus()
        state.edit { selectAll() }
    }
    val save = { onSave(state.text.toString().trim().takeIf { it.isNotEmpty() && it != default }) }
    DialogScrim(
        Modifier.onPreviewKeyEvent { event ->
            if (event.type == KeyEventType.KeyDown && event.key == Key.Escape && !event.isMetaPressed) {
                onCancel()
                true
            } else {
                false
            }
        },
        width = 640.dp * uiScaleFactor(),
        onDismiss = onCancel,
    ) {
        Column(Modifier.semantics { contentDescription = "AI command" }) {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("AI command", fontSize = 16.sp, color = colors.text, fontWeight = FontWeight.SemiBold)
                Text(
                    "What KaTTY runs to ask the AI something, such as the command that runs a project. \$PROMPT is the question, and the first line " +
                        "that it prints is the answer. It runs with /bin/sh, in the directory that the question is about.",
                    color = colors.muted,
                )
                TextField(
                    state,
                    Modifier.fillMaxWidth().focusRequester(focus).semantics { contentDescription = "Command" }.onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && (event.key == Key.Enter || event.key == Key.NumPadEnter)) {
                            save()
                            true
                        } else {
                            false
                        }
                    },
                    placeholder = { Text(default ?: "claude -p \"\$PROMPT\"") },
                    textStyle = JewelTheme.defaultTextStyle.copy(fontFamily = FontFamily.Monospace),
                )
                Text(
                    default?.let { "Default, from the first installed agent: $it" } ?: "No installed agent has a default: type one.",
                    color = colors.muted, fontSize = 12.sp,
                )
            }
            Row(
                Modifier.fillMaxWidth().background(colors.side).padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(onClick = { onSave(null) }) { Text("Use default") }
                OutlinedButton(onClick = onCancel) { Text("Cancel") }
                DefaultButton(onClick = save) { Text("Save") }
            }
        }
    }
}
