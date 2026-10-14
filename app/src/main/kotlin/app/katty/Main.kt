package app.katty

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import app.katty.terminal.TerminalSession
import app.katty.terminal.TerminalTheme
import app.katty.terminal.compose.Terminal
import app.katty.terminal.themes.ITermColorSchemes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("app.katty.Main")

private const val DEFAULT_THEME = "Alabaster"

fun main() {
    // Before the first key is typed.
    disablePressAndHold()
    application { Katty() }
}

@Composable
private fun ApplicationScope.Katty() {
    Window(onCloseRequest = ::exitApplication, title = "KaTTY") {
        MaterialTheme {
            var themeName by remember { mutableStateOf(DEFAULT_THEME) }
            val terminalFocus = remember { FocusRequester() }

            Column {
                Row(Modifier.align(Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
                    Text("Hello, KaTTY!")
                    Spacer(Modifier.width(16.dp))
                    ThemeSelector(
                        names = remember { ITermColorSchemes.names },
                        selected = themeName,
                        onSelect = {
                            themeName = it
                            // What is typed next is for the terminal, not for the field that was used to choose.
                            // There is no terminal to give it to when it could not start.
                            runCatching { terminalFocus.requestFocus() }
                        },
                    )
                }
                Box(
                    Modifier
                        .padding(16.dp)
                        .fillMaxWidth()
                        .weight(1f)
                        .border(2.dp, MaterialTheme.colorScheme.outline)
                ) {
                    // Inside the border, which would hide the first cells.
                    Shell(themeName, terminalFocus, Modifier.fillMaxSize().padding(2.dp))
                }
            }
        }
    }
}

/**
 * The user's login shell, in a terminal, with the colors of the theme called [themeName].
 */
@Composable
private fun Shell(themeName: String, focusRequester: FocusRequester, modifier: Modifier) {
    // The terminal takes the size of the space it is given as soon as it is laid out: this is only where it starts.
    val started = remember { runCatching { TerminalSession.start(cols = 80, rows = 24, theme = loadTheme(themeName)) } }
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

    // Reading a theme is a file and some XML: not on the UI thread. The one the session started with is
    // applied again, which changes nothing.
    LaunchedEffect(session, themeName) {
        val theme = withContext(Dispatchers.IO) { loadTheme(themeName) } ?: return@LaunchedEffect
        session.setTheme(theme)
    }

    Terminal(session, modifier, focusRequester = focusRequester)
}

// A theme that cannot be read is not worth stopping for: the terminal keeps the colors it has.
private fun loadTheme(name: String): TerminalTheme? =
    try {
        ITermColorSchemes.load(name)
    } catch (e: Exception) {
        logger.error("Could not load the theme {}", name, e)
        null
    }
