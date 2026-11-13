package app.katty

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import app.katty.core.KattyDirectories
import app.katty.core.KattyStore
import app.katty.core.RecentProjects
import app.katty.core.RecentProjectsFile
import app.katty.core.TerminalSnapshotStore
import app.katty.core.StateFile
import app.katty.core.StatePersistence
import app.katty.core.WindowFile
import app.katty.core.WindowGeometry
import app.katty.core.ThemeMode
import app.katty.core.sessionUsageReaders
import app.katty.terminals.AgentTracker
import app.katty.terminals.SessionUsageTracker
import app.katty.terminals.SessionRegistry
import app.katty.ui.KattyTheme
import app.katty.ui.UiScale
import app.katty.ui.rememberSystemDark
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.jewel.window.DecoratedWindow
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("app.katty.Main")

fun main() {
    // Before anything uses AWT: the menu bar of macOS is named by what this says when it starts.
    nameApplication()
    // What runs it is not always the JetBrains Runtime that the build asks for (an IDE's run configuration, a `java -jar`), and some of what KaTTY
    // does depends on it: watching the repositories is the system's on the JetBrains Runtime, and polls elsewhere.
    logger.info("KaTTY {} ({}) runs on {}", BuildConfig.VERSION, BuildConfig.GIT_COMMIT, runtimeDescription())
    // Before the first key is typed.
    disablePressAndHold()
    // The logo is the icon of the Dock and of the application switcher, instead of the one of Java.
    AppIcon.installInDock()
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

/**
 * The Java runtime that the application runs on, for the log: its vendor and the vendor's version when it has one (`JetBrains s.r.o.
 * JBR-25.0.3+9-329.124-jcef`), the runtime and its version, the virtual machine, and the folder it is in.
 */
internal fun runtimeDescription(property: (String) -> String? = System::getProperty): String {
    val vendorVersion = property("java.vendor.version")?.takeIf { it.isNotBlank() }?.let { " $it" } ?: ""
    return "${property("java.vendor")}$vendorVersion (${property("java.runtime.name")} ${property("java.runtime.version")}, ${property("java.vm.name")}) " +
        "in ${property("java.home")}"
}

@Composable
private fun ApplicationScope.Katty(store: KattyStore, file: StateFile, directories: KattyDirectories, windowFile: WindowFile, geometry: WindowGeometry) {
    // What the store holds is written to the file as it changes, and a last time when the application quits.
    val writer = remember { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    val persistence = remember { StatePersistence(store, file, writer) }
    // The projects that were opened before, and the state of the ones that were removed, whose terminals keep what they showed.
    val recent = remember { RecentProjects(store, RecentProjectsFile(directories.recentProjectsFile), writer) }
    // One shell for each terminal of the store, which goes on running when its workspace is not the one that is shown.
    val registry = remember {
        SessionRegistry(
            store, writer, shellIntegration = directories.shellIntegration, snapshots = TerminalSnapshotStore(directories.snapshots),
            savedTerminals = recent.savedTerminals,
        )
    }
    // What each of them says it is doing.
    val tracker = remember { AgentTracker(writer) }
    // The sound of one of them that starts needing the user.
    val attentionSound = remember { AttentionSound() }
    // What the session of the agent in each of them has used, read from the agent's own files.
    val sessionUsage = remember {
        SessionUsageTracker(
            writer, sessionUsageReaders(), { registry.processNames.value },
            { id -> registry.handles.value[id]?.session?.foregroundCommand() },
            { id -> store.state.value.locate(id)?.terminal?.running?.agentSession },
        ).also { it.start() }
    }
    DisposableEffect(Unit) { onDispose { recent.stop(); writer.cancel() } }
    val windowState = rememberWindowState(placement = geometry.mode.toPlacement(), position = geometry.toPosition(), size = geometry.toSize())
    // Where the window is, how big and in which mode is kept as it changes, and a last time when the application quits.
    val flushWindow = keepWindowGeometry(windowState, windowFile, geometry)
    // The board in a window of its own, when it was asked for; the main window is told what is clicked there through the bridge.
    val bridge = remember { WindowBridge() }
    // Once, whichever asks first: the window's close button, or the system's Quit (⌘Q, the Dock, logging out), which ends the process after it.
    val quitting = remember { booleanArrayOf(false) }
    val quit = {
        if (!quitting[0]) {
            quitting[0] = true
            flushWindow()
            bridge.flushBoardWindow?.invoke()
            persistence.flush()
            sessionUsage.stop()
            tracker.close()
            registry.close()
            exitApplication()
        }
    }
    val latestQuit = rememberUpdatedState(quit)
    // Quitting asks first, in the main window, when programs or agents would be stopped: from the close button and from the system's Quit alike.
    val quitScope = rememberCoroutineScope()
    val requestQuit: (answer: (Boolean) -> Unit) -> Unit = { answer ->
        quitScope.launch {
            val confirmed = try {
                bridge.confirmQuit?.invoke() != false
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                true
            }
            try {
                if (confirmed) latestQuit.value()
            } finally {
                answer(confirmed)
            }
        }
    }
    DisposableEffect(Unit) { takeQuitItem(requestQuit); onDispose { } }

    val settings by store.state.collectAsState().let { state -> derivedStateOf { state.value.settings } }
    // The system's appearance is read again while the theme follows it: the terminals that have no scheme of their own change with it.
    val systemDark by rememberSystemDark(follow = settings.theme == ThemeMode.SYSTEM)
    val dark = when (settings.theme) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> systemDark
    }
    // The shortcuts of the window are handled by the window, which sees the keys before whatever has the focus.
    val windowKeys = remember { WindowKeys() }
    // The board window comes back where it was last closed, in a file of its own, and is open again if the application was closed with it open.
    val boardWindowFile = remember { WindowFile(directories.boardWindowFile, WindowGeometry(width = BOARD_WINDOW_WIDTH, height = BOARD_WINDOW_HEIGHT)) }
    val openedAtLaunch = remember { boardWindowFile.load().open }
    var boardWindow by remember { mutableStateOf(openedAtLaunch) }
    // Until the user opens it or closes it themselves, it is the window that opened by itself, which does not keep the focus.
    var restoredAtLaunch by remember { mutableStateOf(openedAtLaunch) }
    var boardFocusTick by remember { mutableIntStateOf(0) }
    // The About window, from the settings menu, the palette and the About item of the application menu of macOS.
    var aboutOpen by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { takeAboutItem { aboutOpen = true }; onDispose { } }
    KattyTheme(dark) {
        DecoratedWindow(
            onCloseRequest = { requestQuit {} },
            title = "KaTTY",
            icon = remember { AppIcon.painter() },
            state = windowState,
            onPreviewKeyEvent = windowKeys::handle,
        ) {
            UiScale(settings.effectiveUiScale) {
                KattyWindowContent(store, registry, tracker, sessionUsage, directories, attentionSound, bridge, onOpenAbout = { aboutOpen = true }, onOpenBoardWindow = { restoredAtLaunch = false; if (boardWindow) boardFocusTick++ else boardWindow = true }, keys = windowKeys, recent = recent)
            }
        }
        if (aboutOpen) AboutWindow(settings.effectiveUiScale, onClose = { aboutOpen = false })
        if (boardWindow) BoardWindow(store, tracker, sessionUsage, registry, bridge, boardFocusTick, boardWindowFile, restoredAtLaunch, onClose = { boardWindow = false; restoredAtLaunch = false })
    }
}
