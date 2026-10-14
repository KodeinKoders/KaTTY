package app.katty.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.katty.core.KattyStore
import app.katty.core.PaneNode
import app.katty.core.Settings
import app.katty.core.SplitDirection
import app.katty.core.TerminalId
import app.katty.core.Workspace
import app.katty.core.terminals
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
    modifier: Modifier = Modifier,
    overlay: @Composable (TerminalId) -> Unit = {},
) {
    val colors = Katty.colors
    Column(modifier.background(colors.bg)) {
        WorkspaceHeader(workspace, selectedTerminal, store)
        Column(Modifier.weight(1f).fillMaxWidth().padding(PANE_GAP / 2)) {
            PaneTree(
                node = workspace.layout,
                context = PaneContext(workspace, selectedTerminal, handles, settings, store, flipped, focusTerminals),
            )
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
            PaneView(node.terminal, context)
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

