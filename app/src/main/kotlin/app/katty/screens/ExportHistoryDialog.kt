package app.katty.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.katty.terminals.ExportFormat
import app.katty.ui.DialogScrim
import app.katty.ui.Katty
import app.katty.ui.ListKey
import app.katty.ui.OneLine
import app.katty.ui.hoverHighlight
import app.katty.ui.listKey
import app.katty.ui.stepped
import app.katty.ui.uiScaleFactor
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text

/**
 * Asked before the history of the terminal [terminal] is exported: in which format. Choosing one (a click, or the arrows and Enter) goes on to
 * where the file is saved; the dialog starts on [initial], the format of the last export. Escape, or a click outside the dialog, cancels.
 * While a program has the [alternateScreen], it says that only what the program shows is exported.
 */
@Composable
fun ExportHistoryDialog(terminal: String, alternateScreen: Boolean, initial: ExportFormat, onChoose: (ExportFormat) -> Unit, onCancel: () -> Unit) {
    val colors = Katty.colors
    val formats = ExportFormat.entries
    var highlighted by remember { mutableIntStateOf(formats.indexOf(initial)) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val title = "Export the history of “$terminal”"
    DialogScrim(
        Modifier.onPreviewKeyEvent { event ->
            when (event.listKey()) {
                ListKey.UP -> { highlighted = highlighted.stepped(-1, formats.size); true }
                ListKey.DOWN -> { highlighted = highlighted.stepped(1, formats.size); true }
                ListKey.ENTER -> { onChoose(formats[highlighted]); true }
                ListKey.ESCAPE -> { onCancel(); true }
                null -> false
            }
        },
        width = 460.dp * uiScaleFactor(),
        onDismiss = onCancel,
    ) {
        Column(Modifier.semantics { contentDescription = title }) {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title, fontSize = 16.sp, color = colors.text, fontWeight = FontWeight.SemiBold)
                // The alternate screen has no history: what the program showed before is in the program, never written to the terminal.
                val what = if (alternateScreen) {
                    "Only what the program shows now: it draws on a screen of its own, which keeps no history. Its own export, if it has one, has the rest."
                } else {
                    "Everything the terminal keeps: its history and its screen."
                }
                Text(what, color = colors.muted)
            }
            Column(Modifier.padding(horizontal = 12.dp).fillMaxWidth().focusRequester(focus).focusable()) {
                formats.forEachIndexed { index, format ->
                    FormatRow(format, highlighted = index == highlighted) { onChoose(format) }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 12.dp).background(colors.side).padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(onClick = onCancel) { Text("Cancel") }
            }
        }
    }
}

@Composable
private fun FormatRow(format: ExportFormat, highlighted: Boolean, onClick: () -> Unit) {
    val colors = Katty.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(6.dp))
            .hoverHighlight(enabled = !highlighted)
            .background(if (highlighted) colors.sel else Color.Transparent)
            .semantics { contentDescription = format.label; selected = highlighted }
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            OneLine(format.label, color = colors.text)
            OneLine(format.description, color = colors.muted, fontSize = 12.sp)
        }
        Text(".${format.extension}", color = colors.muted, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
    }
}

/** Says that something the user asked for could not be done, and why. Enter, Escape, OK or a click outside it closes it. */
@Composable
fun MessageDialog(title: String, message: String, onClose: () -> Unit) {
    val colors = Katty.colors
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    DialogScrim(
        Modifier.onPreviewKeyEvent { event ->
            val closes = event.type == KeyEventType.KeyDown && (event.key == Key.Escape || event.key == Key.Enter || event.key == Key.NumPadEnter)
            if (closes) onClose()
            closes
        },
        width = 460.dp * uiScaleFactor(),
        onDismiss = onClose,
    ) {
        Column(Modifier.semantics { contentDescription = title }) {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, fontSize = 16.sp, color = colors.text, fontWeight = FontWeight.SemiBold)
                Text(message, color = colors.muted)
            }
            Row(
                Modifier.fillMaxWidth().background(colors.side).padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DefaultButton(onClick = onClose, modifier = Modifier.focusRequester(focus)) { Text("OK") }
            }
        }
    }
}
