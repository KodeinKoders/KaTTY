package app.katty.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.katty.core.AgentState
import app.katty.core.AgentStates
import app.katty.core.KattyState
import app.katty.core.KattyStore
import app.katty.core.NoAgentStates
import app.katty.core.Project
import app.katty.core.ProjectId
import app.katty.core.SplitDirection
import app.katty.core.Workspace
import app.katty.core.WorkspaceId
import app.katty.core.counts
import app.katty.core.strip
import app.katty.ui.GlyphSize
import app.katty.ui.InlineRename
import app.katty.ui.Katty
import app.katty.ui.KattyIconButton
import app.katty.ui.KattyIcons
import app.katty.ui.KattyMenu
import app.katty.ui.OneLine
import app.katty.ui.StateCount
import app.katty.ui.StateGlyph
import app.katty.ui.onSecondaryClick
import org.jetbrains.jewel.ui.component.Text

/** The height of a bar of tabs. */
val TAB_BAR_HEIGHT = 40.dp

/**
 * The projects as tabs along the top, with how many terminals of each are in each state, and a button to add one. Tabs that do not fit
 * scroll.
 */
@Composable
fun ProjectTabs(
    state: KattyState,
    store: KattyStore,
    onAddProject: () -> Unit,
    onRemoveProject: (ProjectId) -> Unit,
    onReveal: (ProjectId) -> Unit,
    renamingProject: ProjectId?,
    onRenamingProject: (ProjectId?) -> Unit,
    onNewWorkspace: (ProjectId) -> Unit,
    modifier: Modifier = Modifier,
    states: AgentStates = NoAgentStates,
) {
    val colors = Katty.colors
    Row(modifier.fillMaxWidth().height(TAB_BAR_HEIGHT).background(colors.side).border(1.dp, colors.line), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
            for (project in state.projects) {
                ProjectTab(
                    project, project.id == state.selection.project, states.counts(project), renamingProject == project.id,
                    onSelect = { store.selectProject(project.id) },
                    onRename = { onRenamingProject(project.id) },
                    onRenamed = { name ->
                        onRenamingProject(null)
                        if (!name.isNullOrBlank()) store.renameProject(project.id, name)
                    },
                    onNewWorkspace = { onNewWorkspace(project.id) },
                    onRemove = { onRemoveProject(project.id) },
                    onReveal = { onReveal(project.id) },
                )
            }
        }
        KattyIconButton(KattyIcons.Plus, "Add project", onAddProject, Modifier.padding(horizontal = 6.dp))
    }
}

@Composable
private fun ProjectTab(
    project: Project,
    selected: Boolean,
    counts: List<Pair<AgentState, Int>>,
    renaming: Boolean,
    onSelect: () -> Unit,
    onRename: () -> Unit,
    onRenamed: (String?) -> Unit,
    onNewWorkspace: () -> Unit,
    onRemove: () -> Unit,
    onReveal: () -> Unit,
) {
    val colors = Katty.colors
    var menuAt by remember { mutableStateOf<IntOffset?>(null) }
    Box {
        Row(
            Modifier.height(TAB_BAR_HEIGHT)
                .background(if (selected) colors.sel else Color.Transparent)
                .semantics { contentDescription = project.name; this.selected = selected }
                .clickable(role = Role.Tab, onClick = onSelect)
                .onKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.F2) {
                        onRename()
                        true
                    } else {
                        false
                    }
                }
                .onSecondaryClick { menuAt = IntOffset(it.x.toInt(), it.y.toInt()) }
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (renaming) {
                InlineRename(project.name, onRenamed, Modifier.width(140.dp))
            } else {
                OneLine(project.name, color = colors.text)
            }
            for ((state, count) in counts) StateCount(state, count)
        }
        menuAt?.let { at -> KattyMenu(projectMenu(onNewWorkspace, onRename, onReveal, onRemove), onDismiss = { menuAt = null }, offset = at) }
    }
}

/**
 * The workspaces of the selected project as tabs, each with the state of its terminals, the selected one under a line of the accent color;
 * a button for a new workspace, and, behind a divider at the far right, the buttons that split the focused terminal.
 */
@Composable
fun WorkspaceTabs(
    project: Project?,
    selectedWorkspace: WorkspaceId?,
    selectedTerminal: app.katty.core.TerminalId?,
    store: KattyStore,
    renamingWorkspace: WorkspaceId?,
    onRenamingWorkspace: (WorkspaceId?) -> Unit,
    onCloseWorkspace: (WorkspaceId) -> Unit,
    onNewWorkspace: () -> Unit,
    modifier: Modifier = Modifier,
    states: AgentStates = NoAgentStates,
) {
    val colors = Katty.colors
    Row(modifier.fillMaxWidth().height(TAB_BAR_HEIGHT).background(colors.panel).border(1.dp, colors.line), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
            for (workspace in project?.workspaces.orEmpty()) {
                WorkspaceTab(
                    workspace, workspace.id == selectedWorkspace, states.strip(workspace), renamingWorkspace == workspace.id,
                    onSelect = { store.selectWorkspace(workspace.id) },
                    onRename = { onRenamingWorkspace(workspace.id) },
                    onRenamed = { name ->
                        onRenamingWorkspace(null)
                        if (!name.isNullOrBlank()) store.renameWorkspace(workspace.id, name)
                    },
                    onClose = { onCloseWorkspace(workspace.id) },
                )
            }
        }
        KattyIconButton(KattyIcons.Plus, "New workspace", onNewWorkspace, Modifier.padding(horizontal = 4.dp), enabled = project != null)
        Box(Modifier.padding(horizontal = 4.dp).width(1.dp).height(20.dp).background(colors.line))
        KattyIconButton(KattyIcons.SplitRight, "Split right", { selectedTerminal?.let { store.split(it, SplitDirection.ROW) } }, enabled = selectedTerminal != null)
        KattyIconButton(KattyIcons.SplitDown, "Split down", { selectedTerminal?.let { store.split(it, SplitDirection.COLUMN) } }, Modifier.padding(end = 6.dp), enabled = selectedTerminal != null)
    }
}

@Composable
private fun WorkspaceTab(
    workspace: Workspace,
    selected: Boolean,
    strip: List<AgentState>,
    renaming: Boolean,
    onSelect: () -> Unit,
    onRename: () -> Unit,
    onRenamed: (String?) -> Unit,
    onClose: () -> Unit,
) {
    val colors = Katty.colors
    var menuAt by remember { mutableStateOf<IntOffset?>(null) }
    Box {
        Row(
            Modifier.height(TAB_BAR_HEIGHT)
                .semantics { contentDescription = workspace.name; this.selected = selected }
                .clickable(role = Role.Tab, onClick = onSelect)
                .onKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.F2) {
                        onRename()
                        true
                    } else {
                        false
                    }
                }
                .onSecondaryClick { menuAt = IntOffset(it.x.toInt(), it.y.toInt()) }
                // The selected tab has a line of the accent color under it, 2 px thick.
                .drawBehind {
                    if (selected) {
                        val thickness = 2.dp.toPx()
                        drawLine(colors.accent, Offset(0f, size.height - thickness / 2), Offset(size.width, size.height - thickness / 2), strokeWidth = thickness)
                    }
                }
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                for (state in strip) StateGlyph(state, size = GlyphSize.SMALL)
            }
            if (renaming) {
                InlineRename(workspace.name, onRenamed, Modifier.width(140.dp))
            } else {
                Text(workspace.name, color = colors.text, fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal, maxLines = 1)
            }
        }
        menuAt?.let { at -> KattyMenu(workspaceMenu(onRename, onClose), onDismiss = { menuAt = null }, offset = at) }
    }
}
