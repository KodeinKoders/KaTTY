package app.katty

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import app.katty.core.Direction
import app.katty.core.KattyStore
import app.katty.core.Project
import app.katty.core.ProjectId
import app.katty.core.SplitDirection
import app.katty.core.WorkspaceId
import app.katty.core.neighbour
import app.katty.core.terminals
import app.katty.screens.Sidebar
import app.katty.screens.TitleBarContent
import app.katty.screens.Welcome
import app.katty.screens.WorkspaceArea
import app.katty.terminals.SessionRegistry
import app.katty.ui.ConfirmDialog
import app.katty.ui.Katty
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.window.DecoratedWindowScope
import org.jetbrains.jewel.window.TitleBar
import java.awt.Desktop
import java.io.File

/**
 * What is in the window: the title bar, and under it the sidebar and the workspace, or the welcome of an application that has no project.
 */
@Composable
fun DecoratedWindowScope.KattyWindowContent(store: KattyStore, registry: SessionRegistry) {
    val state by store.state.collectAsState()
    val settings = state.settings
    var renamingWorkspace by remember { mutableStateOf<WorkspaceId?>(null) }
    var removing by remember { mutableStateOf<ProjectId?>(null) }
    val handles by registry.handles.collectAsState()

    // The sessions follow the terminals of the store, and the ones that are not on the screen are paused.
    LaunchedEffect(state.projects, state.settings.colorScheme) { registry.sync(state) }
    val shown = state.selectedWorkspace?.layout?.terminals()?.map { it.id }?.toSet().orEmpty()
    LaunchedEffect(shown, handles.keys) { registry.setVisible(shown) }

    val addProject = {
        val folder = chooseFolder(window, "Add project folder", state.selectedProject?.directory)
        if (folder != null) store.addProject(folder)
        Unit
    }
    val newWorkspace = {
        state.selection.project?.let { project ->
            renamingWorkspace = store.addWorkspace(project)
        }
        Unit
    }
    val closeSelectedWorkspace = { state.selection.workspace?.let(store::closeWorkspace); Unit }
    // Whether every split is drawn turned, which the vertical layout does: decided with the layout mode, later.
    val flipped = false
    val focusNeighbour = { direction: Direction ->
        val workspace = state.selectedWorkspace
        val from = state.selection.terminal
        if (workspace != null && from != null) workspace.layout.neighbour(from, direction, flipped)?.let(store::focus)
        Unit
    }

    TitleBar {
        TitleBarContent(state, onToggleSidebar = { store.updateSettings { it.copy(sidebarCollapsed = !it.sidebarCollapsed) } })
    }

    Box(
        Modifier.fillMaxSize().background(Katty.colors.bg).onPreviewKeyEvent { event ->
            val shortcut = event.windowShortcut() ?: return@onPreviewKeyEvent false
            val terminal = state.selection.terminal
            when (shortcut) {
                WindowShortcut.ADD_PROJECT -> addProject()
                WindowShortcut.NEW_WORKSPACE -> newWorkspace()
                WindowShortcut.CLOSE_WORKSPACE -> closeSelectedWorkspace()
                WindowShortcut.SPLIT_RIGHT -> terminal?.let { store.split(it, SplitDirection.ROW) }
                WindowShortcut.SPLIT_DOWN -> terminal?.let { store.split(it, SplitDirection.COLUMN) }
                WindowShortcut.CLOSE_TERMINAL -> terminal?.let { store.closeTerminal(it) }
                WindowShortcut.FOCUS_LEFT -> focusNeighbour(Direction.LEFT)
                WindowShortcut.FOCUS_RIGHT -> focusNeighbour(Direction.RIGHT)
                WindowShortcut.FOCUS_UP -> focusNeighbour(Direction.UP)
                WindowShortcut.FOCUS_DOWN -> focusNeighbour(Direction.DOWN)
            }
            true
        },
    ) {
        if (state.projects.isEmpty()) {
            Welcome(onAddProject = addProject)
        } else {
            Row(Modifier.fillMaxSize()) {
                if (!settings.sidebarCollapsed) {
                    Sidebar(
                        state = state,
                        store = store,
                        onAddProject = addProject,
                        onRemoveProject = { removing = it },
                        onCloseWorkspace = store::closeWorkspace,
                        onReveal = { id -> state.projects.firstOrNull { it.id == id }?.let { revealInFileManager(it.directory) } },
                        renamingWorkspace = renamingWorkspace,
                        onRenamingWorkspace = { renamingWorkspace = it },
                    )
                }
                val workspace = state.selectedWorkspace
                if (workspace == null) {
                    EmptyProject(Modifier.weight(1f), onNewWorkspace = newWorkspace)
                } else {
                    WorkspaceArea(
                        workspace = workspace,
                        selectedTerminal = state.selection.terminal,
                        handles = handles,
                        settings = settings,
                        store = store,
                        flipped = flipped,
                        focusTerminals = renamingWorkspace == null && removing == null,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
            }
        }

        removing?.let { id ->
            val project = state.projects.firstOrNull { it.id == id }
            if (project == null) removing = null else RemoveProjectDialog(project, onConfirm = { store.removeProject(id); removing = null }, onCancel = { removing = null })
        }
    }
}

/** The window's own shortcuts, which the terminal does not see. */
private enum class WindowShortcut {
    ADD_PROJECT, NEW_WORKSPACE, CLOSE_WORKSPACE, SPLIT_RIGHT, SPLIT_DOWN, CLOSE_TERMINAL, FOCUS_LEFT, FOCUS_RIGHT, FOCUS_UP, FOCUS_DOWN,
}

private fun KeyEvent.windowShortcut(): WindowShortcut? {
    if (type != KeyEventType.KeyDown || !isMetaPressed) return null
    if (isAltPressed) {
        // Command and Option and an arrow: the neighbouring pane.
        return when (key) {
            Key.DirectionLeft -> WindowShortcut.FOCUS_LEFT
            Key.DirectionRight -> WindowShortcut.FOCUS_RIGHT
            Key.DirectionUp -> WindowShortcut.FOCUS_UP
            Key.DirectionDown -> WindowShortcut.FOCUS_DOWN
            else -> null
        }
    }
    return when {
        key == Key.O && !isShiftPressed -> WindowShortcut.ADD_PROJECT
        key == Key.N && !isShiftPressed -> WindowShortcut.NEW_WORKSPACE
        key == Key.W && isShiftPressed -> WindowShortcut.CLOSE_WORKSPACE
        key == Key.W -> WindowShortcut.CLOSE_TERMINAL
        key == Key.D && isShiftPressed -> WindowShortcut.SPLIT_DOWN
        key == Key.D -> WindowShortcut.SPLIT_RIGHT
        else -> null
    }
}

/** What a project without workspaces shows. */
@Composable
private fun EmptyProject(modifier: Modifier, onNewWorkspace: () -> Unit) {
    Column(modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("This project has no workspace.", color = Katty.colors.muted)
        DefaultButton(onClick = onNewWorkspace) { Text("New workspace") }
    }
}

@Composable
private fun RemoveProjectDialog(project: Project, onConfirm: () -> Unit, onCancel: () -> Unit) {
    val terminals = project.workspaces.sumOf { it.layout.terminals().size }
    ConfirmDialog(
        title = "Remove “${project.name}”?",
        message = "Removing this project closes its workspaces and ends its terminals. Its folder stays on your disk.",
        confirmLabel = "Remove project",
        onConfirm = onConfirm,
        onCancel = onCancel,
        details = {
            if (project.workspaces.isEmpty()) {
                Text("No workspaces.", color = Katty.colors.muted)
            } else {
                for (workspace in project.workspaces) {
                    val count = workspace.layout.terminals().size
                    Text("${workspace.name}  ·  ${if (count == 1) "1 terminal" else "$count terminals"}", color = Katty.colors.text)
                }
                Text("$terminals terminals in all", color = Katty.colors.muted)
            }
        },
    )
}

private fun revealInFileManager(directory: String) {
    val file = File(directory)
    try {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE_FILE_DIR)) {
            Desktop.getDesktop().browseFileDirectory(file)
        } else {
            Desktop.getDesktop().open(file)
        }
    } catch (e: Exception) {
        org.slf4j.LoggerFactory.getLogger("app.katty.KattyWindow").warn("Could not show {} in the file manager", directory, e)
    }
}
