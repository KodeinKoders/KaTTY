package app.katty.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text

/**
 * A question that the user has to answer before something that cannot be undone: it is over a scrim that covers the window, and
 * Escape cancels. The button that confirms is red, as what it does is destroy.
 *
 * The keyboard is on Cancel when it opens, so that a stray Enter destroys nothing: Tab goes to the red button and Enter presses the one that
 * has the focus. Command and Backspace confirm at once, as the shortcut of something that is deliberate.
 *
 * @param title What is asked, as "Close “refactor-auth”?".
 * @param message What will happen.
 * @param details What would be lost, listed under the message: the terminals with their states.
 */
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    details: (@Composable () -> Unit)? = null,
) {
    val colors = Katty.colors
    val cancelFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { cancelFocus.requestFocus() }
    DialogScrim(
        modifier.onPreviewKeyEvent { event ->
            when {
                event.type != KeyEventType.KeyDown -> false
                event.key == Key.Escape -> { onCancel(); true }
                event.key == Key.Backspace && event.isMetaPressed -> { onConfirm(); true }
                else -> false
            }
        },
    ) {
        Column(Modifier.semantics { contentDescription = title }, verticalArrangement = Arrangement.spacedBy(0.dp)) {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, fontSize = 16.sp, color = colors.text, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                Text(message, color = colors.muted)
            }
            if (details != null) {
                Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 16.dp).fillMaxWidth().background(colors.side, RoundedCornerShape(8.dp)).padding(8.dp)) {
                    details()
                }
            }
            Row(
                Modifier.fillMaxWidth().background(colors.side).padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(onClick = onCancel, modifier = Modifier.focusRequester(cancelFocus)) { Text("Cancel") }
                // A destructive action is red: Jewel's default button is the accent's, so the red one is drawn here.
                DestructiveButton(confirmLabel, onConfirm)
            }
        }
    }
}

/** A button in the color of what is destroyed. */
@Composable
fun DestructiveButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = Katty.colors
    var focused by remember { mutableStateOf(false) }
    androidx.compose.foundation.layout.Box(
        modifier.onFocusChanged { focused = it.isFocused }
            // Where the keyboard is, since the button is not Jewel's, which draws it itself.
            .border(2.dp, if (focused) colors.accent else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(6.dp))
            .background(colors.err, RoundedCornerShape(6.dp)).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 14.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = colors.onStatus, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
    }
}
