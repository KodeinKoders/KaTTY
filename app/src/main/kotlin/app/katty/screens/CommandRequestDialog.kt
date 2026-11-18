package app.katty.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.katty.core.AiAnswer
import app.katty.ui.DialogScrim
import app.katty.ui.Katty
import app.katty.ui.OneLine
import app.katty.ui.uiScaleFactor
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField

/**
 * Asks the AI for a command that does what the user describes, for [shell] in [directory]: the request is typed in a field and Enter asks
 * ([onAsk]); while [asking], it says so; the command that [answer] gives is shown in a field of its own, where it can be changed before [onType]
 * types it at the prompt (never running it) or [onCopy] copies it; an answer that failed says why. [note] says why the command could not be typed.
 * Escape, or a click outside the dialog, closes it ([onClose]). The request starts as [initialRequest].
 */
@Composable
internal fun CommandRequestDialog(
    directory: String,
    shell: String,
    answer: AiAnswer?,
    asking: Boolean,
    note: String?,
    onAsk: (String) -> Unit,
    onType: (String) -> Unit,
    onCopy: (String) -> Unit,
    onClose: () -> Unit,
    initialRequest: String = "",
) {
    val colors = Katty.colors
    val request = rememberTextFieldState(initialRequest)
    val requestFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { requestFocus.requestFocus() }
    val answered = answer as? AiAnswer.Command
    // The command of each answer, which the user may change before it is typed.
    val command = remember(answer) { TextFieldState(answered?.line.orEmpty()) }
    val commandFocus = remember { FocusRequester() }
    LaunchedEffect(answer) { if (answered != null) runCatching { commandFocus.requestFocus() } }
    val ask = { if (!asking && request.text.isNotBlank()) onAsk(request.text.toString()) }
    val type = { if (command.text.isNotBlank()) onType(command.text.toString()) }
    val title = "Ask AI for a command"
    DialogScrim(
        Modifier.onPreviewKeyEvent { event ->
            if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                onClose()
                true
            } else {
                false
            }
        },
        width = 560.dp * uiScaleFactor(),
        onDismiss = onClose,
    ) {
        Column(Modifier.semantics { contentDescription = title }) {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, fontSize = 16.sp, color = colors.text, fontWeight = FontWeight.SemiBold)
                Text("What do you want to do?", color = colors.text)
                TextField(
                    request,
                    Modifier.fillMaxWidth().focusRequester(requestFocus).semantics { contentDescription = "Request" }.onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && (event.key == Key.Enter || event.key == Key.NumPadEnter)) {
                            ask()
                            true
                        } else {
                            false
                        }
                    },
                    placeholder = { Text("Find the files over 100 MB changed this week") },
                )
                OneLine("$shell, in ${homeShortened(directory)}", color = colors.muted, fontSize = 12.sp)
                when {
                    asking -> Text("Asking the AI…", Modifier.semantics { contentDescription = "Asking the AI" }, color = colors.muted)
                    answer is AiAnswer.Failed -> Text("The AI command failed: ${answer.reason}.", color = colors.err)
                    answered != null -> {
                        Text("Command", color = colors.text)
                        TextField(
                            command,
                            Modifier.fillMaxWidth().focusRequester(commandFocus).semantics { contentDescription = "Command" }.onPreviewKeyEvent { event ->
                                if (event.type == KeyEventType.KeyDown && (event.key == Key.Enter || event.key == Key.NumPadEnter)) {
                                    type()
                                    true
                                } else {
                                    false
                                }
                            },
                            textStyle = JewelTheme.defaultTextStyle.copy(fontFamily = FontFamily.Monospace),
                        )
                    }
                }
                note?.let { Text(it, color = colors.err) }
                Text("Sent to the AI command: your request, the shell and the directory.", color = colors.muted, fontSize = 11.sp)
            }
            Row(
                Modifier.fillMaxWidth().background(colors.side).padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(onClick = onClose) { Text("Cancel") }
                when {
                    answered != null && !asking -> {
                        OutlinedButton(onClick = ask, enabled = request.text.isNotBlank()) { Text("Ask again") }
                        OutlinedButton(onClick = { onCopy(command.text.toString()) }, enabled = command.text.isNotBlank()) { Text("Copy") }
                        DefaultButton(onClick = type, enabled = command.text.isNotBlank()) { Text("Type it") }
                    }
                    answer is AiAnswer.Failed && !asking -> DefaultButton(onClick = ask, enabled = request.text.isNotBlank()) { Text("Try again") }
                    else -> DefaultButton(onClick = ask, enabled = !asking && request.text.isNotBlank()) { Text(if (asking) "Asking…" else "Ask") }
                }
            }
        }
    }
}
