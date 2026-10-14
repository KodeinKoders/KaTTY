package app.katty

import androidx.compose.foundation.layout.width
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import app.katty.terminal.TerminalSearchState
import app.katty.terminal.TerminalSession

private val FIELD_WIDTH = 240.dp

/**
 * A field to try the search of [session] by hand: typing searches, Enter goes to the next match, Shift and Enter to the previous one, and
 * "3 of 12" says where it is. It is the demo's: the terminal has no find bar, the application that has it does it as it likes.
 */
@Composable
internal fun FindField(session: TerminalSession?, modifier: Modifier = Modifier) {
    var query by remember(session) { mutableStateOf("") }
    val state by (session?.search?.state ?: remember { kotlinx.coroutines.flow.MutableStateFlow(TerminalSearchState()) }).collectAsState()

    OutlinedTextField(
        value = query,
        onValueChange = {
            query = it
            session?.search?.setQuery(it)
        },
        modifier = modifier.width(FIELD_WIDTH).onPreviewKeyEvent { event ->
            if (event.type == KeyEventType.KeyDown && event.key == Key.Enter) {
                if (event.isShiftPressed) session?.search?.previous() else session?.search?.next()
                true
            } else {
                false
            }
        },
        label = { Text("Find") },
        singleLine = true,
        supportingText = {
            if (query.isNotEmpty()) {
                Text(
                    when {
                        state.total == 0 && state.searching -> "searching…"
                        state.total == 0 -> "no match"
                        else -> "${state.selected ?: "–"} of ${state.total}" + if (state.searching) "…" else ""
                    },
                )
            }
        },
    )
}
