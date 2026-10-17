package app.katty

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
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
import org.jetbrains.jewel.window.DecoratedWindow
import org.jetbrains.jewel.window.TitleBar

/**
 * The agent board in a window of its own, to keep on another screen: only the board, with its own project filter. A card that is clicked
 * selects its terminal in the main window, through [bridge]. Closing it only closes it; it is not kept for the next launch.
 *
 * @param focusTick Changes when the button that opens it is pressed while it is already open: it comes to the front.
 */
@Composable
fun BoardWindow(store: app.katty.core.KattyStore, tracker: AgentTracker, registry: SessionRegistry, bridge: WindowBridge, focusTick: Int, onClose: () -> Unit) {
    val state by store.state.collectAsState()
    val infos by tracker.infos.collectAsState()
    val handles by registry.handles.collectAsState()
    val processNames by registry.processNames.collectAsState()
    val now = rememberNow()
    DecoratedWindow(onCloseRequest = onClose, title = "KaTTY — Agent board", state = rememberWindowState(size = DpSize(1100.dp, 720.dp))) {
        LaunchedEffect(focusTick) { if (focusTick > 0) window.toFront() }
        TitleBar { OneLine("Agent board", color = Katty.colors.text) }
        val size = LocalWindowInfo.current.containerSize
        val vertical = state.settings.layout.orientation(size.width.toFloat(), size.height.toFloat()) == Orientation.VERTICAL
        BoardWindowContent(state, infos, handles, now, vertical, onOpen = { bridge.openTerminal.tryEmit(it) }, processes = processNames)
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
