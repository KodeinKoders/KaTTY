package app.katty.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import app.katty.core.AgentState
import app.katty.core.AgentStates
import app.katty.core.KattyState
import app.katty.core.KattyStore
import app.katty.core.NoAgentStates
import app.katty.core.Project
import app.katty.core.ProjectId
import app.katty.core.counts
import app.katty.core.initialsOf
import app.katty.core.strip
import app.katty.core.urgent
import app.katty.ui.GlyphSize
import app.katty.ui.Icon
import app.katty.ui.Katty
import app.katty.ui.KattyIcons
import app.katty.ui.OneLine
import app.katty.ui.StateCount
import app.katty.ui.StateGlyph
import org.jetbrains.jewel.ui.component.Text

/** The width of the sidebar when it is folded into a rail. */
val RAIL_WIDTH = 56.dp

private val TILE_SIZE = 36.dp

/**
 * The sidebar folded: a tile with two letters for each project, with the state of the terminals that matters most in a badge, a dashed
 * tile to add one, and, at the bottom, [usage]. A click on a tile selects the project and opens the list of its workspaces.
 */
@Composable
fun Rail(
    state: KattyState,
    store: KattyStore,
    onAddProject: () -> Unit,
    modifier: Modifier = Modifier,
    states: AgentStates = NoAgentStates,
    usage: @Composable () -> Unit = {},
) {
    val colors = Katty.colors
    var open by remember { mutableStateOf<ProjectId?>(null) }
    Column(
        modifier.width(RAIL_WIDTH).fillMaxHeight().background(colors.side).border(1.dp, colors.line).semantics { contentDescription = "Projects" },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (project in state.projects) {
                Box {
                    RailTile(project, project.id == state.selection.project, states.urgent(project)) {
                        store.selectProject(project.id)
                        open = if (open == project.id) null else project.id
                    }
                    if (open == project.id) {
                        Flyout(project, state, store, states, onDismiss = { open = null })
                    }
                }
            }
            AddTile(onAddProject)
        }
        usage()
    }
}

@Composable
private fun RailTile(project: Project, selected: Boolean, urgent: AgentState, onClick: () -> Unit) {
    val colors = Katty.colors
    Box(Modifier.size(TILE_SIZE)) {
        Box(
            Modifier.size(TILE_SIZE).clip(RoundedCornerShape(8.dp))
                .background(if (selected) colors.sel else colors.hover)
                .semantics { contentDescription = project.name; this.selected = selected }
                .clickable(role = Role.Button, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Text(initialsOf(project.name), color = colors.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
        // The state that matters most among its terminals, at the corner.
        Box(Modifier.align(Alignment.TopEnd).offset(x = 3.dp, y = (-3).dp).background(colors.side, RoundedCornerShape(50)).padding(2.dp)) {
            StateGlyph(urgent, size = GlyphSize.SMALL)
        }
    }
}

@Composable
private fun AddTile(onClick: () -> Unit) {
    val colors = Katty.colors
    Box(
        Modifier.size(TILE_SIZE).clip(RoundedCornerShape(8.dp))
            .semantics { contentDescription = "Add project" }
            .clickable(role = Role.Button, onClick = onClick)
            .drawBehind {
                // A dashed outline: the tile is where a project will be.
                drawRoundRect(
                    colors.line, size = Size(size.width, size.height), cornerRadius = CornerRadius(8.dp.toPx()),
                    style = Stroke(width = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))),
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(KattyIcons.Plus, colors.muted, size = 16.dp)
    }
}

// Beside the tile, with its top on the tile's.
private object BesideEnd : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset =
        IntOffset(anchorBounds.right + 12, anchorBounds.top.coerceAtMost((windowSize.height - popupContentSize.height).coerceAtLeast(0)))
}

/** The workspaces of a project, in a panel that opens beside its tile. */
@Composable
private fun Flyout(project: Project, state: KattyState, store: KattyStore, states: AgentStates, onDismiss: () -> Unit) {
    val colors = Katty.colors
    Popup(popupPositionProvider = BesideEnd, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        Column(
            Modifier.width(264.dp).shadow(16.dp, RoundedCornerShape(8.dp)).clip(RoundedCornerShape(8.dp)).background(colors.panel)
                .border(1.dp, colors.line, RoundedCornerShape(8.dp)).padding(6.dp).semantics { contentDescription = "Workspaces of ${project.name}" },
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OneLine(project.name, Modifier.weight(1f), color = colors.text)
                for ((s, n) in states.counts(project)) StateCount(s, n)
            }
            Text(
                project.directory.abbreviatedHome(), Modifier.padding(horizontal = 10.dp).padding(bottom = 4.dp),
                color = colors.muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace, maxLines = 1,
            )
            for (workspace in project.workspaces) {
                val selected = workspace.id == state.selection.workspace
                Row(
                    Modifier.fillMaxWidth().height(30.dp).clip(RoundedCornerShape(5.dp))
                        .background(if (selected) colors.sel else Color.Transparent)
                        .semantics { contentDescription = workspace.name; this.selected = selected }
                        .clickable(role = Role.Tab) {
                            store.selectWorkspace(workspace.id)
                            onDismiss()
                        }
                        .padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OneLine(workspace.name, Modifier.weight(1f), color = colors.text)
                    for (s in states.strip(workspace)) StateGlyph(s, size = GlyphSize.SMALL)
                }
            }
            Row(
                Modifier.fillMaxWidth().height(30.dp).clip(RoundedCornerShape(5.dp))
                    .clickable(role = Role.Button) {
                        store.addWorkspace(project.id)
                        onDismiss()
                    }
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(KattyIcons.Plus, colors.muted, size = 14.dp)
                Text("New workspace", color = colors.muted)
            }
            Spacer(Modifier.height(2.dp))
        }
    }
}
