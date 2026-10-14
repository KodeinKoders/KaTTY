package app.katty

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import app.katty.terminal.TerminalSession
import app.katty.terminal.compose.Terminal
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first

fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "KaTTY") {
        MaterialTheme {
            Column {
                Text("Hello, KaTTY!", Modifier.align(Alignment.CenterHorizontally))
                Box(
                    Modifier
                        .padding(16.dp)
                        .fillMaxWidth()
                        .weight(1f)
                        .border(2.dp, MaterialTheme.colorScheme.outline)
                ) {
                    // Inside the border, which would hide the first cells.
                    Shell(Modifier.fillMaxSize().padding(2.dp))
                }
            }
        }
    }
}

/**
 * The user's login shell, in a terminal.
 */
@Composable
private fun Shell(modifier: Modifier) {
    // The terminal takes the size of the space it is given as soon as it is laid out: this is only where it starts.
    val started = remember { runCatching { TerminalSession.start(cols = 80, rows = 24) } }
    val session = started.getOrNull()
    if (session == null) {
        Text("Could not start the terminal: ${started.exceptionOrNull()}", modifier)
        return
    }

    DisposableEffect(session) {
        onDispose { session.close() }
    }

    // The window stays open, with what the shell wrote: it may be what explains why it exited.
    LaunchedEffect(session) {
        val code = session.exited.filterNotNull().first()
        session.writeToScreen("\r\n[process exited with code $code]\r\n")
    }

    Terminal(session, modifier)
}
