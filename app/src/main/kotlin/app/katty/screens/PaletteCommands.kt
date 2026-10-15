package app.katty.screens

import app.katty.core.AgentInfo
import app.katty.core.AgentState
import app.katty.core.KattyState
import app.katty.core.LayoutMode
import app.katty.core.MainView
import app.katty.core.PaletteEntry
import app.katty.core.PaletteGroup
import app.katty.core.TerminalId
import app.katty.core.TerminalSpec
import app.katty.core.ThemeMode
import app.katty.core.WorkspaceId

/** What the commands of the palette do: the window gives them, since it is what holds the dialogs and the selection. */
class PaletteHandlers(
    val addProject: () -> Unit,
    val newWorkspace: () -> Unit,
    val renameWorkspace: () -> Unit,
    val closeWorkspace: () -> Unit,
    val splitRight: () -> Unit,
    val splitDown: () -> Unit,
    val renameTerminal: () -> Unit,
    val closeTerminal: () -> Unit,
    val restartTerminal: () -> Unit,
    val find: () -> Unit,
    val clearScrollback: () -> Unit,
    val maximize: () -> Unit,
    val colorScheme: () -> Unit,
    val toggleSidebar: () -> Unit,
    val showView: (MainView) -> Unit,
    val setLayout: (LayoutMode) -> Unit,
    val setTheme: (ThemeMode) -> Unit,
    val jumpToWaiting: () -> Unit,
    val markAllSeen: () -> Unit,
    val openTerminal: (TerminalId) -> Unit,
    val openWorkspace: (WorkspaceId) -> Unit,
)

/** The entries of the palette, and what each does. */
class PaletteCommands(val entries: List<PaletteEntry>, private val actions: Map<String, () -> Unit>) {
    /** Does what [entry] says; nothing for one that is not there any more. */
    fun run(entry: PaletteEntry) {
        actions[entry.id]?.invoke()
    }
}

private fun AgentState.word(): String = when (this) {
    AgentState.IDLE -> "idle"
    AgentState.WORKING -> "working"
    AgentState.NEEDS_INPUT, AgentState.ERROR -> "needs input"
    AgentState.DONE -> "done"
}

/**
 * What the palette lists: the workspaces of every project (with their project), the terminals (with their workspace and what they are
 * doing), and the commands (with their shortcuts). A command that acts on a terminal or a workspace is there only when there is one.
 *
 * @param titleOf What a terminal is called: its name, its agent's, or its shell's.
 */
fun paletteCommands(
    state: KattyState,
    infos: Map<TerminalId, AgentInfo>,
    titleOf: (TerminalSpec) -> String,
    handlers: PaletteHandlers,
): PaletteCommands {
    val entries = ArrayList<PaletteEntry>()
    val actions = HashMap<String, () -> Unit>()
    fun add(entry: PaletteEntry, action: () -> Unit) {
        entries += entry
        actions[entry.id] = action
    }

    for (project in state.projects) for (workspace in project.workspaces) {
        add(PaletteEntry("workspace:${workspace.id.value}", PaletteGroup.WORKSPACES, workspace.name, project.name)) { handlers.openWorkspace(workspace.id) }
    }
    for (location in state.allTerminals()) {
        val agent = infos[location.terminal.id]?.state ?: AgentState.IDLE
        add(PaletteEntry("terminal:${location.terminal.id.value}", PaletteGroup.TERMINALS, titleOf(location.terminal), "${location.workspace.name}  ·  ${agent.word()}")) {
            handlers.openTerminal(location.terminal.id)
        }
    }

    fun command(key: String, label: String, shortcut: String? = null, action: () -> Unit) =
        add(PaletteEntry("command:$key", PaletteGroup.COMMANDS, label, shortcut = shortcut), action)

    val hasWorkspace = state.selectedWorkspace != null
    val hasTerminal = state.selection.terminal != null
    command("add-project", "Add project folder…", "⌘O", handlers.addProject)
    if (state.selection.project != null) command("new-workspace", "New workspace", "⌘N", handlers.newWorkspace)
    if (hasWorkspace) {
        command("rename-workspace", "Rename workspace", "F2", handlers.renameWorkspace)
        command("close-workspace", "Close workspace", "⌘⇧W", handlers.closeWorkspace)
    }
    if (hasTerminal) {
        command("split-right", "Split right", "⌘D", handlers.splitRight)
        command("split-down", "Split down", "⌘⇧D", handlers.splitDown)
        command("rename-terminal", "Rename terminal", action = handlers.renameTerminal)
        command("restart-terminal", "Restart terminal", "⌘R", handlers.restartTerminal)
        command("find", "Find in terminal", "⌘F", handlers.find)
        command("clear-scrollback", "Clear scrollback", "⌘L", handlers.clearScrollback)
        command("maximize", if (state.selection.maximized == null) "Maximize terminal" else "Restore terminal", action = handlers.maximize)
        command("color-scheme", "Change the color scheme of the terminal", action = handlers.colorScheme)
        command("close-terminal", "Close terminal", "⌘W", handlers.closeTerminal)
    }
    command("jump", "Jump to next waiting agent", "⌘⇧J", handlers.jumpToWaiting)
    command("mark-seen", "Mark all agents seen", action = handlers.markAllSeen)
    command("view", if (state.settings.view == MainView.BOARD) "Show terminals" else "Show the agent board") {
        handlers.showView(if (state.settings.view == MainView.BOARD) MainView.TERMINALS else MainView.BOARD)
    }
    command("sidebar", "Toggle project sidebar", action = handlers.toggleSidebar)
    command("layout-auto", "Layout: auto") { handlers.setLayout(LayoutMode.AUTO) }
    command("layout-horizontal", "Layout: horizontal") { handlers.setLayout(LayoutMode.HORIZONTAL) }
    command("layout-vertical", "Layout: vertical") { handlers.setLayout(LayoutMode.VERTICAL) }
    command("theme-light", "Theme: light") { handlers.setTheme(ThemeMode.LIGHT) }
    command("theme-dark", "Theme: dark") { handlers.setTheme(ThemeMode.DARK) }
    command("theme-system", "Theme: system") { handlers.setTheme(ThemeMode.SYSTEM) }
    return PaletteCommands(entries, actions)
}
