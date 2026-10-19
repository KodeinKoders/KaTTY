package app.katty.screens

import app.katty.SplitLabels
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.DpSize
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
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

/** The space around a maximized pane, in the area of its workspace. */
val MAXIMIZED_MARGIN = 24.dp

/** How long a pane takes to grow to its maximized place and to go back, in milliseconds. */
const val MAXIMIZE_MS = 200

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
    focusTick: Int = 0,
    actions: PaneActions,
    modifier: Modifier = Modifier,
    maximized: TerminalId? = null,
    font: TerminalFont = TerminalFont.bundled(),
    showHeader: Boolean = true,
    infos: Map<TerminalId, AgentInfo> = emptyMap(),
) {
    val colors = Katty.colors
    // What the floating pane is, while it grows from its place and while it goes back to it: it outlives `maximized` by the time of the way back.
    var shown by remember(workspace.id) { mutableStateOf<TerminalId?>(null) }
    val progress = remember(workspace.id) { Animatable(0f) }
    LaunchedEffect(workspace.id, maximized) {
        if (maximized != null) {
            shown = maximized
            progress.animateTo(1f, tween(MAXIMIZE_MS, easing = FastOutSlowInEasing))
        } else {
            progress.animateTo(0f, tween(MAXIMIZE_MS, easing = FastOutSlowInEasing))
            shown = null
        }
    }
    // Where the panes are, to grow the floating one from there.
    val paneBounds = remember(workspace.id) { mutableStateMapOf<TerminalId, Rect>() }
    var areaBounds by remember(workspace.id) { mutableStateOf(Rect.Zero) }
    val context = PaneContext(workspace, selectedTerminal, handles, settings, store, flipped, focusTerminals, actions, shown, font, infos, paneBounds, focusTick)
    Column(modifier.background(colors.bg)) {
        // The vertical layout has the workspace's name and its split buttons in its tabs.
        if (showHeader) WorkspaceHeader(workspace, selectedTerminal, store)
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().onGloballyPositioned { areaBounds = it.boundsInRoot() }) {
            Column(Modifier.fillMaxSize().padding(PANE_GAP / 2)) {
                PaneTree(node = workspace.layout, context = context)
            }
            val floating = workspace.layout.terminals().firstOrNull { it.id == shown }
            if (floating != null) {
                // Over the area of the workspace only: the title bar, the sidebar and the header of the workspace stay as they are. A click
                // outside the terminal puts it back; Escape does not, since the programs in a terminal use it.
                Box(
                    Modifier.fillMaxSize().background(Color(0xFF0E0F12).copy(alpha = .3f * progress.value))
                        .clickable(interactionSource = null, indication = null) { if (maximized != null) store.maximize(null) },
                )
                // The pane's frame is laid out at its real bounds on every frame, from its place in the tree to where it ends. Its terminal is laid
                // out at its final size from the start and clipped, so that the program is resized once and not on every frame of the way.
                val from = paneBounds[floating.id]?.translate(-areaBounds.left, -areaBounds.top)
                val terminalSize = DpSize(maxWidth - MAXIMIZED_MARGIN * 2, maxHeight - MAXIMIZED_MARGIN * 2 - PANE_HEADER_HEIGHT)
                Box(
                    Modifier
                        .layout { measurable, constraints ->
                            val margin = MAXIMIZED_MARGIN.roundToPx().toFloat()
                            val end = Rect(margin, margin, constraints.maxWidth - margin, constraints.maxHeight - margin)
                            val rect = if (from == null) end else lerp(from, end, progress.value)
                            val placeable = measurable.measure(Constraints.fixed(rect.width.roundToInt().coerceAtLeast(0), rect.height.roundToInt().coerceAtLeast(0)))
                            layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(rect.left.roundToInt(), rect.top.roundToInt()) }
                        }
                        .shadow(24.dp, RoundedCornerShape(8.dp)),
                ) {
                    PaneView(floating, context, floating = true, terminalSize = terminalSize)
                }
            }
        }
    }
}

/**
 * Splits [terminal] so that the new shell is to its right, or under it, **as it is drawn**: the vertical layout draws every split turned,
 * so what it shows on the right is a `COLUMN` of the model and what it shows under is a `ROW`.
 *
 * @param flipped Whether the splits are drawn turned, as the vertical layout does.
 */
fun KattyStore.splitVisually(terminal: TerminalId, right: Boolean, flipped: Boolean) {
    split(terminal, if (right != flipped) SplitDirection.ROW else SplitDirection.COLUMN)
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
    /** Where each pane of the tree is, in the window's coordinates, written by the panes. */
    val paneBounds: MutableMap<TerminalId, Rect> = mutableMapOf(),
    /** Changes when the focused terminal is to take the keyboard again, though nothing about it changed. */
    val focusTick: Int = 0,
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
        Modifier.fillMaxWidth().heightIn(min = WORKSPACE_HEADER_HEIGHT).background(colors.panel).padding(start = 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        OneLine(workspace.name, color = colors.text)
        Text(if (count == 1) "1 terminal" else "$count terminals", color = colors.muted)
        Spacer(Modifier.weight(1f))
        // This header is the horizontal layout's: its main direction is right.
        val split = SplitLabels.of(vertical = false)
        KattyIconButton(KattyIcons.SplitRight, "Split right", { selected?.let { store.split(it, SplitDirection.ROW) } }, enabled = selected != null, shortcut = split.rightShortcut)
        KattyIconButton(KattyIcons.SplitDown, "Split down", { selected?.let { store.split(it, SplitDirection.COLUMN) } }, enabled = selected != null, shortcut = split.downShortcut)
    }
}

// The dividers are not drawn: the 8 px between the panes are what they are dragged by.
private val INVISIBLE_DIVIDER = DividerStyle(Color.Transparent, DividerMetrics(thickness = 1.dp, startIndent = 0.dp))

/** The smallest a side of a split may be, so that a terminal always has room for a few lines. */
private val MIN_PANE_SIZE = 160.dp

@Composable
private fun PaneTree(node: PaneNode, context: PaneContext) {
    when (node) {
        is PaneNode.Leaf -> Column(
            Modifier.padding(PANE_GAP / 2).fillMaxSize().onGloballyPositioned { context.paneBounds[node.terminal.id] = it.boundsInRoot() },
        ) {
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
            // A ratio that the store changed by itself (the keyboard moves the divider) is shown: the state is what draws the divider.
            LaunchedEffect(node.ratio) {
                if (kotlin.math.abs(state.dividerPosition - node.ratio) > 0.001f) state.dividerPosition = node.ratio
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

