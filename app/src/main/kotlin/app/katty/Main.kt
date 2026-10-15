package app.katty

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import app.katty.core.KattyDirectories
import app.katty.core.KattyStore
import app.katty.core.StateFile
import app.katty.core.StatePersistence
import app.katty.core.ThemeMode
import app.katty.terminals.AgentTracker
import app.katty.terminals.SessionRegistry
import app.katty.ui.KattyTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.jetbrains.jewel.window.DecoratedWindow

fun main() {
    // Before the first key is typed.
    disablePressAndHold()
    val directories = KattyDirectories.default()
    val file = StateFile(directories.stateFile)
    val store = KattyStore(file.load())
    application { Katty(store, file, directories) }
}

@Composable
private fun ApplicationScope.Katty(store: KattyStore, file: StateFile, directories: KattyDirectories) {
    // What the store holds is written to the file as it changes, and a last time when the application quits.
    val writer = remember { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    val persistence = remember { StatePersistence(store, file, writer) }
    // One shell for each terminal of the store, which goes on running when its workspace is not the one that is shown.
    val registry = remember { SessionRegistry(store, writer) }
    // What each of them says it is doing.
    val tracker = remember { AgentTracker(writer) }
    DisposableEffect(Unit) { onDispose { writer.cancel() } }
    val quit = {
        persistence.flush()
        tracker.close()
        registry.close()
        exitApplication()
    }

    val settings by store.state.collectAsState().let { state -> derivedStateOf { state.value.settings } }
    val dark = when (settings.theme) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    KattyTheme(dark) {
        DecoratedWindow(
            onCloseRequest = quit,
            title = "KaTTY",
            state = rememberWindowState(size = DpSize(1440.dp, 900.dp)),
        ) {
            KattyWindowContent(store, registry, tracker, directories)
        }
    }
}
