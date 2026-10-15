package app.katty.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.katty.core.AgentInfo
import app.katty.core.KattyStore
import app.katty.core.PaneNode
import app.katty.core.Settings
import app.katty.core.SplitDirection
import app.katty.core.TerminalId
import app.katty.core.Workspace
import app.katty.core.terminals
import app.katty.terminal.compose.TerminalFont
import app.katty.terminals.TerminalHandle
import app.katty.ui.Katty
import app.katty.ui.KattyIconButton
import app.katty.ui.KattyIcons
import app.katty.ui.OneLine
import kotlinx.coroutines.flow.drop
import org.jetbrains.jewel.ui.component.HorizontalSplitLayout
import org.jetbrains.jewel.ui.component.SplitLayoutState
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.VerticalSplitLayout
import org.jetbrains.jewel.ui.component.styling.DividerMetrics
import org.jetbrains.jewel.ui.component.styling.DividerStyle

/** The height of the header of a workspace, and of the tabs that stand for it in the vertical layout. */
val WORKSPACE_HEADER_HEIGHT = 40.dp

/** The gap between panes, and between them and the edge of the area. */
val PANE_GAP = 8.dp

/**
 * A workspace: its header, and the terminals in the tree of their splits.
 *
 * @param flipped Whether every split is turned, as the vertical layout draws the tree.
 * @param focusTerminals Whether the focused terminal may take the keyboard focus: not while something else is being typed in.
 * @param actions What the window does for the panes.
 * @param infos What each terminal says it is doing: the header of a pane says it.
 * @param showHeader Whether the workspace has a header of its own: the vertical layout has tabs instead.
 * @param maximized The terminal that floats over the area of the workspace, with the others under a scrim.
 */
@Composable
fun WorkspaceArea(
    workspace: Workspace,
    selectedTerminal: TerminalId?,
    handles: Map<TerminalId, TerminalHandle>,
    settings: Settings,
    store: KattyStore,
    flipped: Boolean,
    focusTerminals: Boolean,
    actions: PaneActions,
    modifier: Modifier = Modifier,
    maximized: TerminalId? = null,
    font: TerminalFont = TerminalFont.bundled(),
    showHeader: Boolean = true,
    infos: Map<TerminalId, AgentInfo> = emptyMap(),
) {
    val colors = Katty.colors
    val context = PaneContext(workspace, selectedTerminal, handles, settings, store, flipped, focusTerminals, actions, maximized, font, infos)
    Column(modifier.background(colors.bg)) {
        // The vertical layout has the workspace's name and its split buttons in its tabs.
        if (showHeader) WorkspaceHeader(workspace, selectedTerminal, store)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Column(Modifier.fillMaxSize().padding(PANE_GAP / 2)) {
                PaneTree(node = workspace.layout, context = context)
            }
            val floating = workspace.layout.terminals().firstOrNull { it.id == maximized }
            if (floating != null) {
                // Over the area of the workspace only: the title bar, the sidebar and the header of the workspace stay as they are. A click
                // outside the terminal puts it back; Escape does not, since the programs in a terminal use it.
                Box(
                    Modifier.fillMaxSize().background(Color(0xFF0E0F12).copy(alpha = .3f)).clickable(interactionSource = null, indication = null) { store.maximize(null) },
                )
                Box(Modifier.fillMaxSize().padding(24.dp).shadow(24.dp, RoundedCornerShape(8.dp))) {
                    PaneView(floating, context, floating = true)
                }
            }
        }
    }
}

/** What every pane of a tree needs to know. */
internal class PaneContext(
    val workspace: Workspace,
    val selected: TerminalId?,
    val handles: Map<TerminalId, TerminalHandle>,
    val settings: Settings,
    val store: KattyStore,
    val flipped: Boolean,
    val focusTerminals: Boolean,
    val actions: PaneActions,
    val maximized: TerminalId? = null,
    val font: TerminalFont = TerminalFont.bundled(),
    val infos: Map<TerminalId, AgentInfo> = emptyMap(),
)

/**
 * What the window does for the panes, since it is what asks questions and keeps the dialogs.
 *
 * @param renaming The terminal whose name is being edited in its header.
 * @param onClose Closes a terminal, after the confirmation that it needs.
 * @param destinations The other workspaces that a terminal can be moved to.
 */
class PaneActions(
    val renaming: TerminalId?,
    val onRename: (TerminalId) -> Unit,
    val onRenamed: (TerminalId, String?) -> Unit,
    val onClose: (TerminalId) -> Unit,
    val destinations: List<WorkspaceChoice>,
    val onMove: (TerminalId, app.katty.core.WorkspaceId) -> Unit,
    /** The terminal whose find bar is open. */
    val finding: TerminalId? = null,
    val onFind: (TerminalId) -> Unit = {},
    val onFindClosed: (TerminalId) -> Unit = {},
    /** Starts the shell of a terminal again, in the place of the one that ended. */
    val onReopen: (TerminalId) -> Unit = {},
    /** Opens the picker of the color scheme of a terminal. */
    val onColorScheme: (TerminalId) -> Unit = {},
)

@Composable
private fun WorkspaceHeader(workspace: Workspace, selected: TerminalId?, store: KattyStore) {
    val colors = Katty.colors
    val count = workspace.layout.terminals().size
    Row(
        Modifier.fillMaxWidth().height(WORKSPACE_HEADER_HEIGHT).background(colors.panel).padding(start = 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        OneLine(workspace.name, color = colors.text)
        Text(if (count == 1) "1 terminal" else "$count terminals", color = colors.muted)
        Spacer(Modifier.weight(1f))
        KattyIconButton(KattyIcons.SplitRight, "Split right", { selected?.let { store.split(it, SplitDirection.ROW) } }, enabled = selected != null)
        KattyIconButton(KattyIcons.SplitDown, "Split down", { selected?.let { store.split(it, SplitDirection.COLUMN) } }, enabled = selected != null)
    }
}

// The dividers are not drawn: the 8 px between the panes are what they are dragged by.
private val INVISIBLE_DIVIDER = DividerStyle(Color.Transparent, DividerMetrics(thickness = 1.dp, startIndent = 0.dp))

/** The smallest a side of a split may be, so that a terminal always has room for a few lines. */
private val MIN_PANE_SIZE = 160.dp

@Composable
private fun PaneTree(node: PaneNode, context: PaneContext) {
    when (node) {
        is PaneNode.Leaf -> Column(Modifier.padding(PANE_GAP / 2).fillMaxSize()) {
            // The terminal that is maximized is shown in its floating pane: a second view of its session would fight that one over its size.
            PaneView(node.terminal, context, hollow = context.maximized == node.terminal.id)
        }
        is PaneNode.Split -> {
            val direction = if (context.flipped) node.direction.turned() else node.direction
            val state = remember(node.id) { SplitLayoutState(node.ratio) }
            // Dragging a divider is saved as it goes: the store keeps the ratio, and writes it when the dragging has stopped.
            LaunchedEffect(state, node.id) {
                snapshotFlow { state.dividerPosition }.drop(1).collect { context.store.setSplitRatio(context.workspace.id, node.id, it) }
            }
            if (direction == SplitDirection.ROW) {
                HorizontalSplitLayout(
                    first = { PaneTree(node.first, context) },
                    second = { PaneTree(node.second, context) },
                    state = state,
                    dividerStyle = INVISIBLE_DIVIDER,
                    draggableWidth = PANE_GAP,
                    firstPaneMinWidth = MIN_PANE_SIZE,
                    secondPaneMinWidth = MIN_PANE_SIZE,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                VerticalSplitLayout(
                    first = { PaneTree(node.first, context) },
                    second = { PaneTree(node.second, context) },
                    state = state,
                    dividerStyle = INVISIBLE_DIVIDER,
                    draggableWidth = PANE_GAP,
                    firstPaneMinWidth = MIN_PANE_SIZE,
                    secondPaneMinWidth = MIN_PANE_SIZE,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

