package app.katty

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import app.katty.core.KattyDirectories
import app.katty.core.KattyStore
import app.katty.core.StateFile
import app.katty.core.StatePersistence
import app.katty.core.WindowFile
import app.katty.core.WindowGeometry
import app.katty.core.ThemeMode
import app.katty.terminals.AgentTracker
import app.katty.terminals.SessionRegistry
import app.katty.ui.KattyTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.withContext
import org.jetbrains.jewel.window.DecoratedWindow

/** How long the window has to stay as it is before it is written: moving or resizing it changes it many times a second. */
private const val WINDOW_WRITE_DELAY_MS = 500L

fun main() {
    // Before the first key is typed.
    disablePressAndHold()
    val directories = KattyDirectories.default()
    val file = StateFile(directories.stateFile)
    val store = KattyStore(file.load())
    // The window is where it was, unless that is on a screen that is gone.
    val windowFile = WindowFile(directories.windowFile)
    val geometry = windowFile.load().sanitized(screens())
    application { Katty(store, file, directories, windowFile, geometry) }
}

@Composable
private fun ApplicationScope.Katty(store: KattyStore, file: StateFile, directories: KattyDirectories, windowFile: WindowFile, geometry: WindowGeometry) {
    // What the store holds is written to the file as it changes, and a last time when the application quits.
    val writer = remember { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    val persistence = remember { StatePersistence(store, file, writer) }
    // One shell for each terminal of the store, which goes on running when its workspace is not the one that is shown.
    val registry = remember { SessionRegistry(store, writer) }
    // What each of them says it is doing.
    val tracker = remember { AgentTracker(writer) }
    DisposableEffect(Unit) { onDispose { writer.cancel() } }
    val windowState = rememberWindowState(placement = geometry.mode.toPlacement(), position = geometry.toPosition(), size = geometry.toSize())
    // What is kept of the window: the last geometry that was written, and the one that it is now, from the same function.
    val kept = remember { arrayOf(geometry) }
    val keep = { kept[0] = geometryOf(windowState.placement, windowState.isMinimized, windowState.position, windowState.size, kept[0]) }
    @OptIn(FlowPreview::class)
    LaunchedEffect(windowState) {
        snapshotFlow { listOf(windowState.placement, windowState.isMinimized, windowState.position, windowState.size) }
            .debounce(WINDOW_WRITE_DELAY_MS)
            .collect {
                val before = kept[0]
                keep()
                if (kept[0] != before) withContext(Dispatchers.IO) { runCatching { windowFile.save(kept[0]) } }
            }
    }
    val quit = {
        keep()
        runCatching { windowFile.save(kept[0]) }
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
            state = windowState,
        ) {
            KattyWindowContent(store, registry, tracker, directories)
        }
    }
}
