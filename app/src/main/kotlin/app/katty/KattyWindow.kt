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
import androidx.compose.runtime.produceState
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
import app.katty.core.AgentState
import app.katty.core.CloseDecision
import app.katty.core.Direction
import app.katty.core.KattyStore
import app.katty.core.Project
import app.katty.core.ProjectId
import app.katty.core.SplitDirection
import app.katty.core.TerminalId
import app.katty.core.TerminalSpec
import app.katty.core.closeTerminalDecision
import app.katty.core.closeWorkspaceDecision
import app.katty.core.WorkspaceId
import app.katty.core.neighbour
import app.katty.core.terminals
import app.katty.screens.CloseLastTerminalDialog
import app.katty.screens.ColorSchemePicker
import app.katty.screens.MainMenu
import app.katty.screens.CloseWorkspaceDialog
import app.katty.screens.PaneActions
import app.katty.screens.Sidebar
import app.katty.screens.TerminalSummary
import app.katty.screens.WorkspaceChoice
import app.katty.screens.TitleBarContent
import app.katty.screens.Welcome
import app.katty.screens.WorkspaceArea
import app.katty.terminals.SessionRegistry
import app.katty.ui.AgentGlyph
import app.katty.terminal.compose.TerminalFont
import app.katty.terminal.themes.ITermColorSchemes
import app.katty.ui.ConfirmDialog
import app.katty.ui.KattyIconButton
import app.katty.ui.KattyIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    var renamingTerminal by remember { mutableStateOf<TerminalId?>(null) }
    var findingTerminal by remember { mutableStateOf<TerminalId?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    // Whose color scheme is being chosen: the default of the settings (`null` target with the flag), or a terminal's.
    var picking by remember { mutableStateOf<SchemeTarget?>(null) }
    val families by produceState(emptyList<String>()) { value = withContext(Dispatchers.IO) { SystemFonts.monospacedFamilies() } }
    // The font of the terminals: the family that the user chose, or the bundled one when there is none or it cannot be used.
    val font by produceState(TerminalFont.bundled(), settings.fontFamily) {
        value = withContext(Dispatchers.Default) {
            settings.fontFamily?.let { family -> runCatching { TerminalFont.system(family) }.getOrNull() } ?: TerminalFont.bundled()
        }
    }
    // What the user has to confirm before it is closed.
    var closingWorkspace by remember { mutableStateOf<WorkspaceId?>(null) }
    var closingTerminal by remember { mutableStateOf<TerminalId?>(null) }
    val handles by registry.handles.collectAsState()
    // What each terminal is doing comes from what its program reports: until the agents are told apart, every terminal is a shell at rest.
    val stateOf: (TerminalId) -> AgentState = { AgentState.IDLE }
    val describe: (TerminalSpec) -> TerminalSummary = { spec ->
        TerminalSummary(spec.id, spec.name ?: handles[spec.id]?.shellName ?: "shell", AgentGlyph.SHELL, stateOf(spec.id))
    }

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
    // Closing asks first when an agent that is active would be stopped, and does not otherwise.
    val closeWorkspace: (WorkspaceId) -> Unit = { id ->
        if (state.closeWorkspaceDecision(id, stateOf) == CloseDecision.NOW) store.closeWorkspace(id) else closingWorkspace = id
    }
    val closeTerminal: (TerminalId) -> Unit = { id ->
        if (state.closeTerminalDecision(id, stateOf) == CloseDecision.NOW) store.closeTerminal(id) else closingTerminal = id
    }
    val closeSelectedWorkspace = { state.selection.workspace?.let(closeWorkspace); Unit }
    // The workspaces that a terminal can go to: the ones of every project, named with their project when it is another one.
    val destinationsOf = { terminal: TerminalId ->
        val here = state.locate(terminal)
        state.projects.flatMap { project ->
            project.workspaces.filter { it.id != here?.workspace?.id }
                .map { WorkspaceChoice(it.id, if (project.id == here?.project?.id) it.name else "${project.name} / ${it.name}") }
        }
    }
    // Whether every split is drawn turned, which the vertical layout does: decided with the layout mode, later.
    val flipped = false
    val focusNeighbour = { direction: Direction ->
        val workspace = state.selectedWorkspace
        val from = state.selection.terminal
        if (workspace != null && from != null) workspace.layout.neighbour(from, direction, flipped)?.let(store::focus)
        Unit
    }

    TitleBar {
        TitleBarContent(
            state,
            onToggleSidebar = { store.updateSettings { it.copy(sidebarCollapsed = !it.sidebarCollapsed) } },
            trailing = {
                Box {
                    KattyIconButton(KattyIcons.Menu, "Main menu", { menuOpen = !menuOpen })
                    if (menuOpen) {
                        MainMenu(
                            settings = settings,
                            onChange = store::updateSettings,
                            colorScheme = ITermColorSchemes.entries.firstOrNull { it.name == settings.colorScheme },
                            fontFamilies = families,
                            onPickColorScheme = { menuOpen = false; picking = SchemeTarget.Default },
                            onDismiss = { menuOpen = false },
                        )
                    }
                }
            },
        )
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
                WindowShortcut.CLOSE_TERMINAL -> terminal?.let(closeTerminal)
                WindowShortcut.RESTART_TERMINAL -> terminal?.let(registry::restart)
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
                        onCloseWorkspace = closeWorkspace,
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
                        focusTerminals = renamingWorkspace == null && removing == null && closingWorkspace == null && closingTerminal == null && renamingTerminal == null && picking == null,
                        actions = PaneActions(
                            renaming = renamingTerminal,
                            onRename = { renamingTerminal = it },
                            onRenamed = { id, name ->
                                renamingTerminal = null
                                // A name that is blank is no name: the terminal goes back to its default one only when the user clears it on purpose.
                                if (name != null) store.renameTerminal(id, name)
                            },
                            onClose = closeTerminal,
                            destinations = state.selection.terminal?.let(destinationsOf).orEmpty(),
                            onMove = { id, to -> store.moveTerminal(id, to) },
                            finding = findingTerminal,
                            onFind = { findingTerminal = it },
                            onFindClosed = { findingTerminal = null },
                            onReopen = registry::restart,
                            onColorScheme = { picking = SchemeTarget.Terminal(it) },
                        ),
                        maximized = state.selection.maximized,
                        font = font,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
            }
        }

        picking?.let { target ->
            val chosen = when (target) {
                SchemeTarget.Default -> settings.colorScheme
                is SchemeTarget.Terminal -> state.locate(target.id)?.terminal?.colorScheme
            }
            ColorSchemePicker(
                entries = ITermColorSchemes.entries,
                selected = chosen,
                defaultName = if (target is SchemeTarget.Terminal) settings.colorScheme else null,
                onSelect = { name ->
                    when (target) {
                        SchemeTarget.Default -> if (name != null) store.updateSettings { it.copy(colorScheme = name) }
                        is SchemeTarget.Terminal -> store.setColorScheme(target.id, name)
                    }
                },
                onClose = { picking = null },
            )
        }
        closingWorkspace?.let { id ->
            val workspace = state.projects.flatMap { it.workspaces }.firstOrNull { it.id == id }
            if (workspace == null) {
                closingWorkspace = null
            } else {
                CloseWorkspaceDialog(
                    workspaceName = workspace.name,
                    terminals = workspace.layout.terminals().map(describe),
                    onConfirm = { store.closeWorkspace(id); closingWorkspace = null },
                    onCancel = { closingWorkspace = null },
                )
            }
        }
        closingTerminal?.let { id ->
            val location = state.locate(id)
            if (location == null) {
                closingTerminal = null
            } else {
                CloseLastTerminalDialog(
                    workspaceName = location.workspace.name,
                    terminal = describe(location.terminal),
                    onConfirm = { store.closeTerminal(id); closingTerminal = null },
                    onCancel = { closingTerminal = null },
                )
            }
        }
        removing?.let { id ->
            val project = state.projects.firstOrNull { it.id == id }
            if (project == null) removing = null else RemoveProjectDialog(project, onConfirm = { store.removeProject(id); removing = null }, onCancel = { removing = null })
        }
    }
}

/** Whose color scheme the picker chooses. */
private sealed interface SchemeTarget {
    /** The default of the settings, which the terminals that have no scheme of their own follow. */
    data object Default : SchemeTarget

    data class Terminal(val id: TerminalId) : SchemeTarget
}

/** The window's own shortcuts, which the terminal does not see. */
private enum class WindowShortcut {
    ADD_PROJECT, NEW_WORKSPACE, CLOSE_WORKSPACE, SPLIT_RIGHT, SPLIT_DOWN, CLOSE_TERMINAL, RESTART_TERMINAL, FOCUS_LEFT, FOCUS_RIGHT, FOCUS_UP, FOCUS_DOWN,
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
        key == Key.R && !isShiftPressed -> WindowShortcut.RESTART_TERMINAL
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
