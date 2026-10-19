package app.katty.screens

import app.katty.ui.uiScaleFactor
import app.katty.AppShortcut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.katty.terminal.TerminalSearchState
import app.katty.terminal.TerminalSession
import app.katty.ui.ButtonSize
import app.katty.ui.Icon
import app.katty.ui.Katty
import app.katty.ui.KattyIconButton
import app.katty.ui.KattyIcons
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField

/** What the find bar says of the matches: "2 of 7", or that there is none, or nothing while there is nothing to look for. */
internal fun TerminalSearchState.summary(): String = when {
    query.isEmpty() -> ""
    total == 0 -> if (searching) "Searching…" else "No results"
    selected == null -> if (total == 1) "1 result" else "$total results"
    else -> "$selected of $total"
}

/**
 * The find bar of a terminal: a text field, how many matches there are, and the buttons to go to the previous and the next one. Enter
 * goes to the next, Shift and Enter to the previous, and Escape closes it. The matches are drawn by the terminal itself.
 *
 * It does not say whether the case matters: the search ignores the case of ASCII letters and has no option for it.
 */
@Composable
fun FindBar(session: TerminalSession, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val colors = Katty.colors
    val query = rememberTextFieldState()
    val focus = remember { FocusRequester() }
    val search = session.search
    val state by search.state.collectAsState()

    LaunchedEffect(Unit) { focus.requestFocus() }
    LaunchedEffect(search) { snapshotFlow { query.text.toString() }.collect { search.setQuery(it) } }
    // The matches are taken off the screen with the bar.
    DisposableEffect(search) { onDispose { search.setQuery("") } }

    Row(
        modifier.shadow(8.dp, RoundedCornerShape(8.dp)).background(colors.panel, RoundedCornerShape(8.dp)).border(1.dp, colors.line, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .semantics { contentDescription = "Find in terminal" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(KattyIcons.Search, colors.muted, size = 14.dp)
        TextField(
            query,
            Modifier.width(180.dp * uiScaleFactor()).focusRequester(focus).onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.Enter, Key.NumPadEnter -> {
                        if (event.isShiftPressed) search.previous() else search.next()
                        true
                    }
                    Key.Escape -> {
                        onClose()
                        true
                    }
                    else -> false
                }
            },
            placeholder = { Text("Find") },
        )
        Text(state.summary(), Modifier.width(72.dp * uiScaleFactor()), color = colors.muted, fontSize = 12.sp, maxLines = 1)
        KattyIconButton(KattyIcons.ChevronUp, "Previous match", { search.previous() }, size = ButtonSize.COMPACT, shortcut = AppShortcut.FIND_PREVIOUS.label)
        KattyIconButton(KattyIcons.ChevronDown, "Next match", { search.next() }, size = ButtonSize.COMPACT, shortcut = AppShortcut.FIND_NEXT.label)
        KattyIconButton(KattyIcons.Close, "Close find", onClose, size = ButtonSize.COMPACT, shortcut = "Esc")
    }
}
