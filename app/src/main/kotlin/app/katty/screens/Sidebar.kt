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
import androidx.compose.foundation.layout.padding
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
import app.katty.core.KattyState
import app.katty.core.KattyStore
import app.katty.core.Project
import app.katty.core.ProjectId
import app.katty.core.Workspace
import app.katty.core.WorkspaceId
import app.katty.ui.ButtonSize
import app.katty.ui.Katty
import app.katty.ui.KattyIconButton
import app.katty.ui.KattyIcons
import app.katty.ui.KattyMenu
import app.katty.ui.Icon
import app.katty.ui.InlineRename
import app.katty.ui.OneLine
import app.katty.ui.SectionLabel
import app.katty.ui.menu
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
    renamingWorkspace: WorkspaceId?,
    onRenamingWorkspace: (WorkspaceId?) -> Unit,
    modifier: Modifier = Modifier,
    usage: @Composable () -> Unit = {},
) {
    val colors = Katty.colors
    // What is being renamed, and the projects that the user folded: the selected project is open unless it was folded.
    var renamingProject by remember { mutableStateOf<ProjectId?>(null) }
    var folded by remember { mutableStateOf(emptySet<ProjectId>()) }

    Column(modifier.width(SIDEBAR_WIDTH).fillMaxHeight().background(colors.side).border(1.dp, colors.line)) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 6.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("PROJECTS")
            Spacer(Modifier.weight(1f))
            KattyIconButton(KattyIcons.Plus, "Add project", onAddProject)
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            for (project in state.projects) {
                val selected = project.id == state.selection.project
                val open = selected && project.id !in folded
                ProjectRow(
                    project = project,
                    selected = selected,
                    open = open,
                    renaming = renamingProject == project.id,
                    onToggle = { folded = if (open) folded + project.id else folded - project.id },
                    onSelect = {
                        folded = folded - project.id
                        store.selectProject(project.id)
                    },
                    onRename = { renamingProject = project.id },
                    onRenamed = { name ->
                        renamingProject = null
                        if (!name.isNullOrBlank()) store.renameProject(project.id, name)
                    },
                    onNewWorkspace = {
                        folded = folded - project.id
                        onRenamingWorkspace(store.addWorkspace(project.id))
                    },
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
                    for (workspace in project.workspaces) {
                        WorkspaceRow(
                            workspace = workspace,
                            selected = workspace.id == state.selection.workspace,
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
                        Modifier.fillMaxWidth().height(30.dp).padding(start = 22.dp).clip(RoundedCornerShape(5.dp))
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
        usage()
    }
}

@Composable
private fun ProjectRow(
    project: Project,
    selected: Boolean,
    open: Boolean,
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
            Modifier.fillMaxWidth().height(30.dp).clip(RoundedCornerShape(5.dp))
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
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(Modifier.clickable(role = Role.Button, onClick = onToggle).padding(4.dp)) {
                Icon(if (open) KattyIcons.ChevronDown else KattyIcons.ChevronRight, colors.muted, size = 12.dp)
            }
            Icon(KattyIcons.Folder, colors.muted, size = 16.dp)
            if (renaming) {
                InlineRename(project.name, onRenamed, Modifier.weight(1f))
            } else {
                OneLine(project.name, Modifier.weight(1f), color = colors.text)
            }
        }
        menuAt?.let { at ->
            KattyMenu(
                menu {
                    action("New workspace", "⌘N", onClick = onNewWorkspace)
                    action("Rename project…", "F2", onClick = onRename)
                    action("Reveal in file manager", onClick = onReveal)
                    divider()
                    action("Remove project…", danger = true, onClick = onRemove)
                },
                onDismiss = { menuAt = null },
                offset = at,
            )
        }
    }
}

@Composable
private fun WorkspaceRow(
    workspace: Workspace,
    selected: Boolean,
    renaming: Boolean,
    onSelect: () -> Unit,
    onRename: () -> Unit,
    onRenamed: (String?) -> Unit,
    onClose: () -> Unit,
) {
    val colors = Katty.colors
    var menuAt by remember { mutableStateOf<IntOffset?>(null) }
    Box(Modifier.padding(start = 22.dp)) {
        Row(
            Modifier.fillMaxWidth().height(30.dp).clip(RoundedCornerShape(5.dp))
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
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (renaming) {
                InlineRename(workspace.name, onRenamed, Modifier.weight(1f))
            } else {
                OneLine(workspace.name, Modifier.weight(1f), color = colors.text)
            }
        }
        menuAt?.let { at ->
            KattyMenu(
                menu {
                    action("Rename workspace", "F2", onClick = onRename)
                    divider()
                    action("Close workspace", "⌘⇧W", danger = true, onClick = onClose)
                },
                onDismiss = { menuAt = null },
                offset = at,
            )
        }
    }
}

// The path with the user's home folder written as ~, as the design shows it.
internal fun String.abbreviatedHome(home: String = System.getProperty("user.home")): String =
    if (this == home) "~" else if (startsWith("$home/")) "~" + removePrefix(home) else this
