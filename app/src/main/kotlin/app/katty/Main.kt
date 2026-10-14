package app.katty

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import app.katty.terminal.TerminalSession
import app.katty.terminal.compose.Terminal
import app.katty.terminal.themes.ITermColorSchemes
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.intui.standalone.theme.IntUiTheme
import org.jetbrains.jewel.intui.standalone.theme.default
import org.jetbrains.jewel.intui.standalone.theme.lightThemeDefinition
import org.jetbrains.jewel.intui.window.decoratedWindow
import org.jetbrains.jewel.intui.window.styling.lightWithLightHeader
import org.jetbrains.jewel.ui.ComponentStyling
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.window.DecoratedWindow
import org.jetbrains.jewel.window.TitleBar
import org.jetbrains.jewel.window.styling.TitleBarStyle

fun main() {
    // Before the first key is typed.
    disablePressAndHold()
    application { Katty() }
}

@Composable
private fun ApplicationScope.Katty() {
    IntUiTheme(
        theme = JewelTheme.lightThemeDefinition(),
        styling = ComponentStyling.default().decoratedWindow(titleBarStyle = TitleBarStyle.lightWithLightHeader()),
    ) {
        DecoratedWindow(
            onCloseRequest = ::exitApplication,
            title = "KaTTY",
            state = rememberWindowState(size = DpSize(1440.dp, 900.dp)),
        ) {
            TitleBar { Text("KaTTY") }
            // Until the workspaces are there, the window is one shell.
            Shell(Modifier.fillMaxSize().padding(8.dp))
        }
    }
}

/**
 * The user's login shell, in a terminal.
 */
@Composable
private fun Shell(modifier: Modifier) {
    val started = remember { runCatching { TerminalSession.start(cols = 80, rows = 24, theme = ITermColorSchemes.load("Alabaster")) } }
    val session = started.getOrNull()
    if (session == null) {
        Text("Could not start the terminal: ${started.exceptionOrNull()}", modifier)
        return
    }

    DisposableEffect(session) { onDispose { session.close() } }

    // The window stays open, with what the shell wrote: it may be what explains why it exited.
    LaunchedEffect(session) {
        val code = session.exited.filterNotNull().first()
        session.writeToScreen("\r\n[process exited with code $code]\r\n")
    }

    Terminal(session, modifier)
}
