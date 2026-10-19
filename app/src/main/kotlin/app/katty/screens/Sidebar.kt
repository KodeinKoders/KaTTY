package app.katty.screens

import app.katty.ui.uiScaleFactor
import app.katty.AppShortcut
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontFamily
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
import app.katty.core.Workspace
import app.katty.core.WorkspaceId
import app.katty.core.counts
import app.katty.core.strip
import app.katty.ui.ButtonSize
import app.katty.ui.GlyphSize
import app.katty.ui.Katty
import app.katty.ui.StateCount
import app.katty.ui.StateGlyph
import app.katty.ui.KattyIconButton
import app.katty.ui.KattyIcons
import app.katty.ui.KattyMenu
import app.katty.ui.Icon
import app.katty.ui.InlineRename
import app.katty.ui.OneLine
import app.katty.ui.SectionLabel
import app.katty.ui.hoverHighlight
import app.katty.ui.reorderHandle
import app.katty.ui.reorderItem
import app.katty.ui.rememberReorderState
import app.katty.ui.onDoubleClick
import app.katty.ui.onSecondaryClick
import org.jetbrains.jewel.ui.component.Text

/** The width of the project sidebar. */
val SIDEBAR_WIDTH = 272.dp

/**
 * The project sidebar of the horizontal layout: the projects, and under the selected one its folder and its workspaces.
 *
 * @param onAddProject Asks the user for a folder.
 * @param onRemoveProject Asks the user to confirm before the project is removed.
 * @param onCloseWorkspace Closes a workspace, after the confirmation that it needs.
 * @param onReveal Shows a project's folder in the file manager.
 * @param renamingWorkspace The workspace whose name is being edited, which a new one is as soon as it exists; the window keeps it so
 * that the shortcut for a new workspace can start it too.
 * @param states What each terminal is doing: a project is shown with how many are in each state, and a workspace with the state of each.
 * @param usage What is at the bottom of the sidebar.
 */
@Composable
fun Sidebar(
    state: KattyState,
    store: KattyStore,
    onAddProject: () -> Unit,
    onRemoveProject: (ProjectId) -> Unit,
    onCloseWorkspace: (WorkspaceId) -> Unit,
    onReveal: (ProjectId) -> Unit,
    renamingProject: ProjectId?,
    onRenamingProject: (ProjectId?) -> Unit,
    renamingWorkspace: WorkspaceId?,
    onRenamingWorkspace: (WorkspaceId?) -> Unit,
    modifier: Modifier = Modifier,
    states: AgentStates = NoAgentStates,
    usage: @Composable () -> Unit = {},
) {
    val colors = Katty.colors

    Column(modifier.width(SIDEBAR_WIDTH * uiScaleFactor()).fillMaxHeight().background(colors.side).border(1.dp, colors.line)) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 6.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("PROJECTS")
            Spacer(Modifier.weight(1f))
            KattyIconButton(KattyIcons.Plus, "Add project", onAddProject, shortcut = AppShortcut.ADD_PROJECT.label)
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            // The projects are dragged by their row, and the workspaces of the open one by theirs, each among its own.
            val projects = rememberReorderState(state.projects.map { it.id }) { id, index -> store.moveProject(id, index) }
            for (project in state.projects) key(project.id) {
                Column(Modifier.reorderItem(projects, project.id), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    val selected = project.id == state.selection.project
                    val open = project.open
                    ProjectRow(
                        drag = Modifier.reorderHandle(projects, project.id, enabled = renamingProject != project.id),
                        project = project,
                        selected = selected,
                        open = open,
                        counts = states.counts(project),
                        renaming = renamingProject == project.id,
                        onToggle = { store.setProjectOpen(project.id, !open) },
                        // A click opens a closed project, and selects it; on one that is open it closes it. The arrow does the same without selecting.
                        onSelect = {
                            if (open) {
                                store.setProjectOpen(project.id, false)
                            } else {
                                store.setProjectOpen(project.id, true)
                                store.selectProject(project.id)
                            }
                        },
                        onRename = { onRenamingProject(project.id) },
                        onRenamed = { name ->
                            onRenamingProject(null)
                            if (!name.isNullOrBlank()) store.renameProject(project.id, name)
                        },
                        onNewWorkspace = { onRenamingWorkspace(store.addWorkspace(project.id)) },
                        onRemove = { onRemoveProject(project.id) },
                        onReveal = { onReveal(project.id) },
                    )
                    if (open) {
                        Text(
                            project.directory.abbreviatedHome(),
                            Modifier.padding(start = 36.dp, end = 10.dp, bottom = 2.dp),
                            color = colors.muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace, maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                        val workspaces = rememberReorderState(project.workspaces.map { it.id }) { id, index -> store.moveWorkspace(id, index) }
                        for (workspace in project.workspaces) {
                            WorkspaceRow(
                                modifier = Modifier.reorderItem(workspaces, workspace.id),
                                drag = Modifier.reorderHandle(workspaces, workspace.id, enabled = renamingWorkspace != workspace.id),
                                workspace = workspace,
                                selected = workspace.id == state.selection.workspace,
                                strip = states.strip(workspace),
                                renaming = renamingWorkspace == workspace.id,
                                onSelect = { store.selectWorkspace(workspace.id) },
                                onRename = { onRenamingWorkspace(workspace.id) },
                                onRenamed = { name ->
                                    onRenamingWorkspace(null)
                                    if (!name.isNullOrBlank()) store.renameWorkspace(workspace.id, name)
                                },
                                onClose = { onCloseWorkspace(workspace.id) },
                            )
                        }
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 30.dp).padding(start = 22.dp).clip(RoundedCornerShape(5.dp)).hoverHighlight()
                                .clickable(role = Role.Button) {
                                    onRenamingWorkspace(store.addWorkspace(project.id))
                                }
                                .padding(horizontal = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(KattyIcons.Plus, colors.muted, size = 14.dp)
                            Text("New workspace", color = colors.muted)
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                }
            }
        }
        usage()
    }
}

@Composable
private fun ProjectRow(
    drag: Modifier,
    project: Project,
    selected: Boolean,
    open: Boolean,
    counts: List<Pair<AgentState, Int>>,
    renaming: Boolean,
    onToggle: () -> Unit,
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
            Modifier.fillMaxWidth().heightIn(min = 30.dp).clip(RoundedCornerShape(5.dp)).hoverHighlight()
                .semantics { contentDescription = project.name; this.selected = selected }
                .clickable(role = Role.Button, onClick = onSelect)
                // F2 renames what has the focus, when it is in the sidebar: in a terminal it is the program's key.
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
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(Modifier.clickable(role = Role.Button, onClick = onToggle).semantics { contentDescription = if (open) "Close ${project.name}" else "Open ${project.name}" }.padding(4.dp)) {
                Icon(if (open) KattyIcons.ChevronDown else KattyIcons.ChevronRight, colors.muted, size = 12.dp)
            }
            Icon(KattyIcons.Folder, colors.muted, size = 16.dp)
            if (renaming) {
                InlineRename(project.name, onRenamed, Modifier.weight(1f))
            } else {
                OneLine(project.name, Modifier.weight(1f), color = colors.text)
            }
            // How many of its terminals are in each state: the sign that tells the user where to look.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                for ((state, count) in counts) StateCount(state, count)
            }
        }
        menuAt?.let { at ->
            KattyMenu(projectMenu(onNewWorkspace, onRename, onReveal, onRemove), onDismiss = { menuAt = null }, offset = at)
        }
    }
}

@Composable
private fun WorkspaceRow(
    modifier: Modifier,
    drag: Modifier,
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
    Box(modifier.padding(start = 22.dp)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 30.dp).clip(RoundedCornerShape(5.dp)).hoverHighlight(enabled = !selected)
                .background(if (selected) colors.sel else androidx.compose.ui.graphics.Color.Transparent)
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
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (renaming) {
                InlineRename(workspace.name, onRenamed, Modifier.weight(1f))
            } else {
                OneLine(workspace.name, Modifier.weight(1f), color = colors.text)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                for (state in strip) StateGlyph(state, size = GlyphSize.SMALL)
            }
        }
        menuAt?.let { at ->
            KattyMenu(workspaceMenu(onRename, onClose), onDismiss = { menuAt = null }, offset = at)
        }
    }
}

// The path with the user's home folder written as ~, as the design shows it.
internal fun String.abbreviatedHome(home: String = System.getProperty("user.home")): String =
    if (this == home) "~" else if (startsWith("$home/")) "~" + removePrefix(home) else this
