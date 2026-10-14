package app.katty.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.katty.core.TerminalSpec
import app.katty.terminal.compose.Terminal
import app.katty.ui.Katty
import app.katty.ui.OneLine
import androidx.compose.runtime.withFrameNanos
import org.jetbrains.jewel.ui.component.Text

/** The height of the header of a pane. */
val PANE_HEADER_HEIGHT = 30.dp

private val PANE_RADIUS = 6.dp

/**
 * A terminal in its pane: a header, and the terminal. The focused pane has the accent ring; a click in a terminal, or anything that gives it
 * the keyboard focus, makes it the focused one.
 */
@Composable
internal fun PaneView(spec: TerminalSpec, context: PaneContext) {
    val colors = Katty.colors
    val focused = context.selected == spec.id
    val handle = context.handles[spec.id]
    val shape = RoundedCornerShape(PANE_RADIUS)

    // The focused terminal has the keyboard, unless something else is being typed in. The terminal may not be composed yet.
    LaunchedEffect(handle, focused, context.focusTerminals) {
        if (focused && context.focusTerminals && handle != null) {
            withFrameNanos { }
            runCatching { handle.focus.requestFocus() }
        }
    }

    Column(
        Modifier.fillMaxSize()
            .clip(shape)
            .background(colors.panel)
            // The ring is the border of the design plus the shadow around it: 2 px of the accent, 1 px of the line otherwise.
            .border(if (focused) 2.dp else 1.dp, if (focused) colors.accent else colors.line, shape)
            .onFocusChanged { if (it.hasFocus) context.store.focus(spec.id) },
    ) {
        PaneHeader(spec)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val session = handle?.session
            if (session != null) {
                // A shell that was started again is another session: what shows it is made again.
                key(handle.generation) {
                    Terminal(
                        session,
                        Modifier.fillMaxSize(),
                        fontSize = context.settings.fontSize.sp,
                        ligatures = context.settings.ligatures,
                        focusRequester = handle.focus,
                        focusOnStart = false,
                    )
                }
            } else if (handle?.failure != null) {
                Text("Could not start the shell: ${handle.failure}", Modifier.padding(12.dp), color = colors.err)
            }
        }
    }
}

@Composable
private fun PaneHeader(spec: TerminalSpec) {
    val colors = Katty.colors
    Row(
        Modifier.fillMaxWidth().height(PANE_HEADER_HEIGHT).background(colors.side).padding(start = 10.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OneLine(spec.name ?: "zsh", color = colors.text)
    }
}

