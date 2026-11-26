package app.katty.screens

import androidx.compose.foundation.layout.widthIn
import app.katty.core.Ide
import app.katty.core.sharedGit
import app.katty.core.TerminalId
import app.katty.core.GitInfo
import app.katty.ui.uiScaleFactor
import app.katty.SplitLabels
import app.katty.AppShortcut
import app.katty.ui.rememberReorderState
import app.katty.ui.reorderItem
import app.katty.ui.reorderHandle
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.unit.sp
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
import app.katty.core.EnvironmentBadge
import app.katty.core.EnvironmentFilter
import app.katty.core.environmentBadge
import app.katty.core.shownProjects
import app.katty.core.shownWorkspaces
import app.katty.ui.EnvironmentPill
import androidx.compose.runtime.LaunchedEffect
import app.katty.ui.GlyphSize
import app.katty.ui.InlineRename
import app.katty.ui.Katty
import app.katty.ui.KattyIconButton
import app.katty.ui.KattyIcons
import app.katty.ui.KattyMenu
import app.katty.ui.OneLine
import app.katty.ui.StateCount
import app.katty.ui.StateGlyph
import app.katty.ui.hoverHighlight
import app.katty.ui.onDoubleClick
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
    onOpenRecent: () -> Unit = {},
    onRemoveProject: (ProjectId) -> Unit,
    onReveal: (ProjectId) -> Unit,
    renamingProject: ProjectId?,
    onRenamingProject: (ProjectId?) -> Unit,
    onNewWorkspace: (ProjectId) -> Unit,
    modifier: Modifier = Modifier,
    ides: List<Ide> = emptyList(),
    onOpenIn: (ProjectId, Ide) -> Unit = { _, _ -> },
    states: AgentStates = NoAgentStates,
    environments: EnvironmentControls? = null,
) {
    val colors = Katty.colors
    Row(modifier.fillMaxWidth().heightIn(min = TAB_BAR_HEIGHT).background(colors.side).border(1.dp, colors.line), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
            val filter = state.settings.environmentFilter
            // A project is dropped where the one that is at that place of the list is, which is not the same place when others are hidden.
            val shown = state.shownProjects(filter)
            val projects = rememberReorderState(shown.map { it.id }, horizontal = true) { id, index ->
                store.moveProject(id, shown.getOrNull(index)?.let { target -> state.projects.indexOfFirst { it.id == target.id } } ?: index)
            }
            environments?.let { state.emptyListMessage(it.available) }?.let { message ->
                EnvironmentFilterEmpty(message, { store.updateSettings { it.copy(environmentFilter = EnvironmentFilter.NONE) } }, Modifier.padding(horizontal = 10.dp))
            }
            for (project in shown) {
                ProjectTab(
                    Modifier.reorderItem(projects, project.id), Modifier.reorderHandle(projects, project.id, enabled = renamingProject != project.id),
                    project, project.id == state.selection.project, states.counts(project, filter), renamingProject == project.id,
                    onSelect = { store.selectProject(project.id) },
                    onRename = { onRenamingProject(project.id) },
                    onRenamed = { name ->
                        onRenamingProject(null)
                        if (!name.isNullOrBlank()) store.renameProject(project.id, name)
                    },
                    onNewWorkspace = { onNewWorkspace(project.id) },
                    onRemove = { onRemoveProject(project.id) },
                    onReveal = { onReveal(project.id) },
                    ides = ides,
                    onOpenIn = { onOpenIn(project.id, it) },
                    environment = environments?.menuOf(project),
                    badge = environments?.let { project.environmentBadge(it.available) },
                    onMenu = { environments?.onMenu?.invoke() },
                )
            }
        }
        if (environments != null) {
            EnvironmentFilterButton(state, environments.available, { change -> store.updateSettings { it.copy(environmentFilter = change) } })
        }
        AddProjectMenuHost(onAddProject, onOpenRecent) {
            KattyIconButton(KattyIcons.Plus, "Add project", onAddProject, Modifier.padding(horizontal = 6.dp), shortcut = AppShortcut.ADD_PROJECT.label)
        }
    }
}

@Composable
private fun ProjectTab(
    modifier: Modifier,
    drag: Modifier,
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
    ides: List<Ide>,
    onOpenIn: (Ide) -> Unit,
    environment: EnvironmentMenu? = null,
    badge: EnvironmentBadge? = null,
    onMenu: () -> Unit = {},
) {
    val colors = Katty.colors
    var menuAt by remember { mutableStateOf<IntOffset?>(null) }
    LaunchedEffect(menuAt != null) { if (menuAt != null) onMenu() }
    Box(modifier) {
        Row(
            Modifier.heightIn(min = TAB_BAR_HEIGHT)
                .hoverHighlight(enabled = !selected)
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
                .onDoubleClick { if (!renaming) onRename() }
                .then(drag)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (renaming) {
                InlineRename(project.name, onRenamed, Modifier.width(140.dp * uiScaleFactor()))
            } else {
                OneLine(project.name, color = colors.text)
            }
            if (badge != null) EnvironmentPill(badge)
            for ((state, count) in counts) StateCount(state, count)
        }
        menuAt?.let { at -> KattyMenu(projectMenu(onNewWorkspace, onRename, onReveal, onRemove, ides, onOpenIn, environment), onDismiss = { menuAt = null }, offset = at) }
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
    gits: Map<TerminalId, GitInfo?> = emptyMap(),
    onWorkspaceIde: ((WorkspaceId) -> Unit)? = null,
    onRunWorkspace: ((WorkspaceId) -> Unit)? = null,
    run: app.katty.terminals.RunTerminal? = null,
    runActions: RunActions? = null,
    environments: EnvironmentControls? = null,
    environmentFilter: EnvironmentFilter = EnvironmentFilter.NONE,
) {
    val colors = Katty.colors
    Row(modifier.fillMaxWidth().heightIn(min = TAB_BAR_HEIGHT).background(colors.panel).border(1.dp, colors.line), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
            val visible = project?.shownWorkspaces(environmentFilter).orEmpty()
            val workspaces = rememberReorderState(visible.map { it.id }, horizontal = true) { id, index ->
                store.moveWorkspace(id, visible.getOrNull(index)?.let { target -> project?.workspaces?.indexOfFirst { it.id == target.id } } ?: index)
            }
            for (workspace in visible) {
                WorkspaceTab(
                    Modifier.reorderItem(workspaces, workspace.id), Modifier.reorderHandle(workspaces, workspace.id, enabled = renamingWorkspace != workspace.id),
                    workspace, workspace.id == selectedWorkspace, states.strip(workspace), workspace.sharedGit(gits), renamingWorkspace == workspace.id,
                    onSelect = { store.selectWorkspace(workspace.id) },
                    onRename = { onRenamingWorkspace(workspace.id) },
                    onRenamed = { name ->
                        onRenamingWorkspace(null)
                        if (!name.isNullOrBlank()) store.renameWorkspace(workspace.id, name)
                    },
                    onClose = { onCloseWorkspace(workspace.id) },
                    onDefaultIde = onWorkspaceIde?.let { choose -> { choose(workspace.id) } },
                    onRun = onRunWorkspace?.let { run -> { run(workspace.id) } },
                    environment = environments?.menuOf(workspace),
                    badge = if (environments != null && project != null) workspace.environmentBadge(project, environments.available) else null,
                    projectEnvironment = project?.environment,
                    onUseProjectEnvironment = environments?.let { controls -> { controls.useProjectEnvironment(workspace.id) } },
                    onMenu = { environments?.onMenu?.invoke() },
                )
            }
        }
        KattyIconButton(KattyIcons.Plus, "New workspace", onNewWorkspace, Modifier.padding(horizontal = 4.dp), enabled = project != null, shortcut = AppShortcut.NEW_WORKSPACE.label)
        Box(Modifier.padding(horizontal = 4.dp).width(1.dp).height(20.dp).background(colors.line))
        // The first is the vertical split, which is what the vertical layout is about; every split is drawn turned here.
        val split = SplitLabels.of(vertical = true)
        KattyIconButton(KattyIcons.SplitDown, "Split down", { selectedTerminal?.let { store.splitVisually(it, right = false, flipped = true) } }, enabled = selectedTerminal != null, shortcut = split.downShortcut)
        KattyIconButton(KattyIcons.SplitRight, "Split right", { selectedTerminal?.let { store.splitVisually(it, right = true, flipped = true) } }, Modifier.padding(end = if (runActions == null) 6.dp else 0.dp), enabled = selectedTerminal != null, shortcut = split.rightShortcut)
        if (runActions != null) {
            if (run != null) RunIndicator(run, runActions.onToggle)
            KattyIconButton(KattyIcons.Play, "Run a command", runActions.onRun, Modifier.padding(end = 6.dp), shortcut = AppShortcut.RUN.label)
        }
    }
}

@Composable
private fun WorkspaceTab(
    modifier: Modifier,
    drag: Modifier,
    workspace: Workspace,
    selected: Boolean,
    strip: List<AgentState>,
    git: GitInfo?,
    renaming: Boolean,
    onSelect: () -> Unit,
    onRename: () -> Unit,
    onRenamed: (String?) -> Unit,
    onClose: () -> Unit,
    onDefaultIde: (() -> Unit)?,
    onRun: (() -> Unit)? = null,
    environment: EnvironmentMenu? = null,
    badge: EnvironmentBadge? = null,
    projectEnvironment: String? = null,
    onUseProjectEnvironment: (() -> Unit)? = null,
    onMenu: () -> Unit = {},
) {
    val colors = Katty.colors
    var menuAt by remember { mutableStateOf<IntOffset?>(null) }
    LaunchedEffect(menuAt != null) { if (menuAt != null) onMenu() }
    Box(modifier) {
        Row(
            Modifier.heightIn(min = TAB_BAR_HEIGHT)
                .hoverHighlight(enabled = !selected)
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
                .onDoubleClick { if (!renaming) onRename() }
                .then(drag)
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
                InlineRename(workspace.name, onRenamed, Modifier.width(140.dp * uiScaleFactor()))
            } else {
                Text(workspace.name, color = colors.text, fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal, maxLines = 1)
                // The branch that all of its terminals are in.
                if (git != null) GitLabel(git, Modifier.widthIn(max = 160.dp * uiScaleFactor()), fontSize = 11.sp)
            }
            if (badge != null) EnvironmentPill(badge)
        }
        menuAt?.let { at ->
            KattyMenu(
                workspaceMenu(onRename, onClose, onDefaultIde, onRun, environment, projectEnvironment, onUseProjectEnvironment),
                onDismiss = { menuAt = null }, offset = at,
            )
        }
    }
}
