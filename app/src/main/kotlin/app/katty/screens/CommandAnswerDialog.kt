package app.katty.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.katty.core.AiDiagnosis
import app.katty.core.CommandQuestion
import app.katty.core.CommandRun
import app.katty.core.DIAGNOSIS_MAX_LINES
import app.katty.ui.DialogScrim
import app.katty.ui.Katty
import app.katty.ui.uiScaleFactor
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text

/**
 * What the AI says of the command [run], as it was asked [question] (why it failed, or what it did): its line and exit code, then, while [answer] is
 * `null`, that it is being asked; then its explanation and, for a command that failed, the corrected command that it gives, which [onType] puts where
 * it can be run ([typeLabel]: typed at the prompt, or in the question of the run), never running it; or why the AI command gave no answer, which
 * [onRetry] asks again. [note] says why the command could not be typed. Escape, or a click outside the dialog, closes it; Enter does what its default
 * button does.
 */
@Composable
internal fun CommandAnswerDialog(
    question: CommandQuestion,
    run: CommandRun,
    answer: AiDiagnosis?,
    note: String?,
    typeLabel: String,
    onType: () -> Unit,
    onCopy: (String) -> Unit,
    onRetry: () -> Unit,
    onClose: () -> Unit,
) {
    val colors = Katty.colors
    val focus = remember { FocusRequester() }
    LaunchedEffect(answer) { focus.requestFocus() }
    val command = (answer as? AiDiagnosis.Explained)?.command
    val primary: (() -> Unit)? = when {
        answer is AiDiagnosis.Failed -> onRetry
        command != null -> onType
        answer != null -> onClose
        else -> null
    }
    val title = if (question == CommandQuestion.WHY_FAILED) "Why did it fail?" else "What did it do?"
    DialogScrim(
        Modifier.onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when (event.key) {
                Key.Escape -> { onClose(); true }
                Key.Enter, Key.NumPadEnter -> { primary?.invoke(); primary != null }
                else -> false
            }
        },
        width = 560.dp * uiScaleFactor(),
        onDismiss = onClose,
    ) {
        Column(Modifier.semantics { contentDescription = title }.focusRequester(focus).focusable()) {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(title, fontSize = 16.sp, color = colors.text, fontWeight = FontWeight.SemiBold)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        run.command, Modifier.weight(1f, fill = false), color = colors.text, fontFamily = FontFamily.Monospace, maxLines = 2,
                    )
                    val failed = question == CommandQuestion.WHY_FAILED
                    Text(
                        run.exitCode?.let { "Exit code $it" } ?: if (failed) "Failed" else "Ended",
                        color = if (failed) colors.err else colors.muted, fontSize = 12.sp, maxLines = 1,
                    )
                }
                when (answer) {
                    null -> Text("Asking the AI…", Modifier.semantics { contentDescription = "Asking the AI" }, color = colors.muted)
                    is AiDiagnosis.Failed -> Text("The AI command failed: ${answer.reason}.", color = colors.err)
                    is AiDiagnosis.Explained -> {
                        // An explanation may be long: it scrolls, the buttons stay.
                        SelectionContainer(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
                            Text(answer.explanation.ifEmpty { "The AI gave no explanation." }, color = colors.text)
                        }
                        if (command != null) {
                            SelectionContainer {
                                Text(
                                    command,
                                    Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(colors.side).padding(horizontal = 10.dp, vertical = 8.dp)
                                        .semantics { contentDescription = "Corrected command" },
                                    color = colors.text, fontFamily = FontFamily.Monospace,
                                )
                            }
                        }
                    }
                }
                note?.let { Text(it, color = colors.err) }
                Text(
                    "Sent to the AI command: the command, its exit code and the last $DIAGNOSIS_MAX_LINES lines of its output.",
                    color = colors.muted, fontSize = 11.sp,
                )
            }
            Row(
                Modifier.fillMaxWidth().background(colors.side).padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when {
                    answer == null -> OutlinedButton(onClick = onClose) { Text("Cancel") }
                    answer is AiDiagnosis.Failed -> {
                        OutlinedButton(onClick = onClose) { Text("Close") }
                        DefaultButton(onClick = onRetry) { Text("Try again") }
                    }
                    command != null -> {
                        OutlinedButton(onClick = onClose) { Text("Close") }
                        OutlinedButton(onClick = { onCopy(command) }) { Text("Copy") }
                        DefaultButton(onClick = onType) { Text(typeLabel) }
                    }
                    else -> DefaultButton(onClick = onClose) { Text("Close") }
                }
            }
        }
    }
}
