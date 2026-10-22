package app.katty.screens

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.katty.ui.ConfirmDialog
import app.katty.ui.Katty
import org.jetbrains.jewel.ui.component.Text

/** How many lines of a paste the dialog shows. */
private const val SHOWN_LINES = 6

/**
 * Asked before a paste that could run commands: the program in the terminal would take its line breaks for Enter. The first lines are
 * shown as they are, as text (the clipboard may hold anything), with the control characters as their symbols.
 */
@Composable
fun PasteDialog(text: String, onConfirm: () -> Unit, onCancel: () -> Unit) {
    val colors = Katty.colors
    val lines = pasteLines(text)
    ConfirmDialog(
        title = "Paste and run?",
        message = if (lines.size == 1) {
            "Pasted here, this text could run as a command."
        } else {
            "This text has ${lines.size} lines. Pasted here, each of them could run as a command."
        },
        confirmLabel = "Paste",
        onConfirm = onConfirm,
        onCancel = onCancel,
        details = {
            lines.take(SHOWN_LINES).forEach { line ->
                Text(
                    line.ifEmpty { " " }, Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                    color = colors.text, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            val more = lines.size - SHOWN_LINES
            if (more > 0) {
                Text(if (more == 1) "…and 1 more line" else "…and $more more lines", Modifier.padding(horizontal = 4.dp, vertical = 2.dp), color = colors.muted)
            }
        },
    )
}

/**
 * The lines of [text] as the dialog shows them: split at any line break, without the empty one after a last break, and with the other
 * control characters as the symbols of the Control Pictures block (`␛` for an escape), so that what would be sent is seen.
 */
internal fun pasteLines(text: String): List<String> {
    val lines = text.split(LINE_BREAK).let { if (it.size > 1 && it.last().isEmpty()) it.dropLast(1) else it }
    return lines.map { line ->
        buildString(line.length) {
            for (c in line) {
                when {
                    c == '\t' -> append(' ')
                    c < ' ' -> append((0x2400 + c.code).toChar())
                    c == '\u007f' -> append('␡')
                    else -> append(c)
                }
            }
        }
    }
}

private val LINE_BREAK = Regex("\r\n|\r|\n")
