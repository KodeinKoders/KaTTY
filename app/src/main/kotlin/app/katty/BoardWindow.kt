package app.katty

import androidx.compose.runtime.DisposableEffect
import app.katty.core.WindowFile
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.window.rememberWindowState
import app.katty.core.AgentInfo
import app.katty.core.AgentStates
import app.katty.core.AgentState
import app.katty.core.KattyState
import app.katty.core.Orientation
import app.katty.core.ProjectId
import app.katty.core.TerminalId
import app.katty.core.board
import app.katty.core.orientation
import app.katty.screens.AgentBoard
import app.katty.screens.BoardTabs
import app.katty.terminals.AgentTracker
import app.katty.terminals.SessionRegistry
import app.katty.terminals.TerminalHandle
import app.katty.ui.Katty
import app.katty.ui.OneLine
import app.katty.ui.UiScale
import org.jetbrains.jewel.window.DecoratedWindow
import org.jetbrains.jewel.window.TitleBar

/** The size of the board window the first time it is opened. */
internal const val BOARD_WINDOW_WIDTH = 1100f
internal const val BOARD_WINDOW_HEIGHT = 720f

/**
 * The agent board in a window of its own, to keep on another screen: only the board, with its own project filter. A card that is clicked
 * selects its terminal in the main window, through [bridge]. Closing it only closes it. It is opened again at the next launch when it was open
 * when the application was closed (not when the user closed it), and when it is opened it is where it was last closed, from [windowFile].
 *
 * @param restoredAtLaunch Whether it is opened by itself, at the launch of the application: the user did not ask for it, so the focus is given
 * back to the main window when this one takes it (through [bridge]). One that is opened from the button or the palette keeps it.
 * @param focusTick Changes when the button that opens it is pressed while it is already open: it comes to the front.
 */
@Composable
fun BoardWindow(
    store: app.katty.core.KattyStore,
    tracker: AgentTracker,
    registry: SessionRegistry,
    bridge: WindowBridge,
    focusTick: Int,
    windowFile: WindowFile,
    restoredAtLaunch: Boolean,
    onClose: () -> Unit,
) {
    val state by store.state.collectAsState()
    val infos by tracker.infos.collectAsState()
    val handles by registry.handles.collectAsState()
    val processNames by registry.processNames.collectAsState()
    val now = rememberNow()
    // Where it was, unless that is on a screen that is gone.
    val geometry = remember { windowFile.load().sanitized(screens()) }
    val windowState = rememberWindowState(placement = geometry.mode.toPlacement(), position = geometry.toPosition(), size = geometry.toSize())
    // Closed by the user, as against the application quitting with it open: the next launch opens it again in the second case only.
    val closedByUser = remember { booleanArrayOf(false) }
    val flush = keepWindowGeometry(windowState, windowFile, geometry, open = { !closedByUser[0] })
    // It is open from now on, for a launch that comes before anything about it changes.
    LaunchedEffect(Unit) { flush() }
    val closeByUser = { closedByUser[0] = true; flush(); onClose() }
    DecoratedWindow(
        onCloseRequest = closeByUser,
        title = "KaTTY — Agent board",
        state = windowState,
        // ⌘W closes the window that has the focus, as it does in any window of the system.
        onPreviewKeyEvent = { event ->
            val close = event.type == KeyEventType.KeyDown && event.isMetaPressed && !event.isShiftPressed && !event.isAltPressed && event.key == Key.W
            if (close) closeByUser()
            close
        },
    ) {
        LaunchedEffect(focusTick) { if (focusTick > 0) window.toFront() }
        // A window that opens takes the focus. At launch the main window is the one that is meant to have it: when this one is given it, once,
        // it is handed back. If it is never given it (the system did not), nothing is done.
        if (restoredAtLaunch) {
            DisposableEffect(window) {
                val listener = object : java.awt.event.WindowAdapter() {
                    override fun windowGainedFocus(event: java.awt.event.WindowEvent) {
                        window.removeWindowFocusListener(this)
                        bridge.focusMain.tryEmit(Unit)
                    }
                }
                window.addWindowFocusListener(listener)
                onDispose { window.removeWindowFocusListener(listener) }
            }
        }
        UiScale(state.settings.effectiveUiScale) {
            TitleBar { OneLine("Agent board", color = Katty.colors.text) }
            val size = LocalWindowInfo.current.containerSize
            val vertical = state.settings.layout.orientation(size.width.toFloat(), size.height.toFloat()) == Orientation.VERTICAL
            BoardWindowContent(state, infos, handles, now, vertical, onOpen = { bridge.openTerminal.tryEmit(it) }, processes = processNames)
        }
    }
}

/**
 * What the board window shows, apart from the window: the project tabs, which filter only this board, and the board, with no button to open
 * it in a window since it is in one. No terminal is drawn, so [handles] is only what the cards say of their program.
 */
@Composable
fun BoardWindowContent(
    state: KattyState,
    infos: Map<TerminalId, AgentInfo>,
    handles: Map<TerminalId, TerminalHandle>,
    now: Long,
    vertical: Boolean,
    onOpen: (TerminalId) -> Unit,
    modifier: Modifier = Modifier,
    processes: Map<TerminalId, String?> = emptyMap(),
) {
    var chosen by remember { mutableStateOf<ProjectId?>(null) }
    // A project that was removed is not a filter any more.
    val filter = chosen?.takeIf { id -> state.projects.any { it.id == id } }
    val agentStates = remember(infos) { object : AgentStates { override fun stateOf(id: TerminalId) = infos[id]?.state ?: AgentState.IDLE } }
    val columns = remember(state.projects, infos, processes, filter) { state.board(filter, infos, processes) }
    Column(modifier.fillMaxSize()) {
        BoardTabs(state, filter, { chosen = it }, states = agentStates)
        AgentBoard(columns, handles, now, vertical, onOpen, Modifier.weight(1f))
    }
}
