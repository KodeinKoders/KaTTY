package app.katty.ui

import androidx.compose.foundation.text.input.KeyboardActionHandler
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.selectAll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import org.jetbrains.jewel.ui.component.TextField

/**
 * A name that is edited where it is shown: its text is selected, Enter keeps what was typed, Escape keeps the old name, and leaving
 * the field keeps what was typed too.
 *
 * @param onDone Called once, with the new name, or `null` when it was abandoned. A name that is blank is the caller's to refuse.
 */
@Composable
fun InlineRename(initial: String, onDone: (String?) -> Unit, modifier: Modifier = Modifier) {
    val state: TextFieldState = rememberTextFieldState(initial)
    val focus = remember { FocusRequester() }
    val finished = remember { booleanArrayOf(false) }
    // The field gives the focus up after it had it: that is what leaving it is. It has not had it before it asks for it.
    val hadFocus = remember { booleanArrayOf(false) }
    fun finish(result: String?) {
        if (finished[0]) return
        finished[0] = true
        onDone(result)
    }
    LaunchedEffect(Unit) {
        focus.requestFocus()
        state.edit { selectAll() }
    }
    TextField(
        state,
        modifier.focusRequester(focus)
            .onFocusChanged {
                if (it.isFocused) hadFocus[0] = true else if (hadFocus[0]) finish(state.text.toString())
            }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.Escape -> {
                        finish(null)
                        true
                    }
                    // The keyboard action is what the input method sends: the key itself is handled here, so that it keeps the name wherever it
                    // comes from.
                    Key.Enter, Key.NumPadEnter -> {
                        finish(state.text.toString())
                        true
                    }
                    else -> false
                }
            },
        onKeyboardAction = KeyboardActionHandler { finish(state.text.toString()) },
    )
}
