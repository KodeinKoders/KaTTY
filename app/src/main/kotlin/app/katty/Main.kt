package app.katty

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import app.katty.ui.UiScale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.withContext
import org.jetbrains.jewel.window.DecoratedWindow

fun main() {
    // Before the first key is typed.
    disablePressAndHold()
    val directories = KattyDirectories.default()
    val file = StateFile(directories.stateFile)
    val store = KattyStore(file.load())
    // What the user bound the shortcuts to is known before the first label is drawn.
    Shortcuts.map = ShortcutMap.of(store.state.value.settings.shortcuts)
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
    // Where the window is, how big and in which mode is kept as it changes, and a last time when the application quits.
    val flushWindow = keepWindowGeometry(windowState, windowFile, geometry)
    val quit = {
        flushWindow()
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
    // The board in a window of its own, when it was asked for; the main window is told what is clicked there through the bridge.
    val bridge = remember { WindowBridge() }
    // The shortcuts of the window are handled by the window, which sees the keys before whatever has the focus.
    val windowKeys = remember { WindowKeys() }
    // The board window comes back where it was last closed, in a file of its own, and is open again if the application was closed with it open.
    val boardWindowFile = remember { WindowFile(directories.boardWindowFile, WindowGeometry(width = BOARD_WINDOW_WIDTH, height = BOARD_WINDOW_HEIGHT)) }
    val openedAtLaunch = remember { boardWindowFile.load().open }
    var boardWindow by remember { mutableStateOf(openedAtLaunch) }
    // Until the user opens it or closes it themselves, it is the window that opened by itself, which does not keep the focus.
    var restoredAtLaunch by remember { mutableStateOf(openedAtLaunch) }
    var boardFocusTick by remember { mutableIntStateOf(0) }
    KattyTheme(dark) {
        DecoratedWindow(
            onCloseRequest = quit,
            title = "KaTTY",
            state = windowState,
            onPreviewKeyEvent = windowKeys::handle,
        ) {
            UiScale(settings.effectiveUiScale) {
                KattyWindowContent(store, registry, tracker, directories, bridge, onOpenBoardWindow = { restoredAtLaunch = false; if (boardWindow) boardFocusTick++ else boardWindow = true }, keys = windowKeys)
            }
        }
        if (boardWindow) BoardWindow(store, tracker, registry, bridge, boardFocusTick, boardWindowFile, restoredAtLaunch, onClose = { boardWindow = false; restoredAtLaunch = false })
    }
}
