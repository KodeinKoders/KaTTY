package app.katty.screens

import app.katty.AppShortcut
import app.katty.SplitLabels
import app.katty.TerminalKeys
import app.katty.core.Direction
import app.katty.core.AgentInfo
import app.katty.core.AgentState
import app.katty.core.KattyState
import app.katty.core.LayoutMode
import app.katty.core.MainView
import app.katty.core.PaletteEntry
import app.katty.core.PaletteGroup
import app.katty.core.ProjectId
import app.katty.core.TerminalId
import app.katty.core.TerminalSpec
import app.katty.core.ThemeMode
import app.katty.core.WorkspaceId
import app.katty.core.terminals

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
    /** Clears the screen and the history of the terminal. */
    val clearScreen: () -> Unit,
    val maximize: () -> Unit,
    val colorScheme: () -> Unit,
    val toggleSidebar: () -> Unit,
    val showView: (MainView) -> Unit,
    val setLayout: (LayoutMode) -> Unit,
    val setTheme: (ThemeMode) -> Unit,
    val jumpToWaiting: () -> Unit,
    val markAllSeen: () -> Unit,
    /** Opens the board in a window of its own, or brings that window to the front when it is open. */
    val openBoardWindow: () -> Unit,
    val openTerminal: (TerminalId) -> Unit,
    val openWorkspace: (WorkspaceId) -> Unit,
    /** Goes to a project: the one that is selected stays as it is, another one is shown at its first workspace. */
    val selectProject: (ProjectId) -> Unit = {},
    /** What the selected project can be given: a new name, a removal (which asks first), the file manager, a place earlier or later in the list. */
    val renameProject: () -> Unit = {},
    val removeProject: () -> Unit = {},
    val revealProject: () -> Unit = {},
    val moveProject: (Int) -> Unit = {},
    /** Moves the selected workspace one place up or down in its project: -1 or 1. */
    val moveWorkspace: (Int) -> Unit = {},
    /** Moves the selected terminal to another workspace of its project. */
    val moveTerminalTo: (WorkspaceId) -> Unit = {},
    /** Goes back to calling the selected terminal after what runs in it. */
    val useProcessName: () -> Unit = {},
    val copyDirectory: () -> Unit = {},
    /** Opens the choice of the color scheme that the terminals have unless they have one of their own. */
    val defaultColorScheme: () -> Unit = {},
    val toggleLigatures: () -> Unit = {},
    val refreshUsage: () -> Unit = {},
    val toggleCompactUsage: () -> Unit = {},
    /** Makes the font of the terminals bigger or smaller by a step, or what it is by default. */
    val fontBigger: () -> Unit = {},
    val fontSmaller: () -> Unit = {},
    val fontReset: () -> Unit = {},
    /** Moves the focus to the pane in that direction, or the nearest divider that way (the keyboard does the same). */
    val focusPane: (Direction) -> Unit = {},
    val resizeSplit: (Direction) -> Unit = {},
    /** Steps through the matches of the search of the terminal, which it opens when there is none. */
    val findNext: () -> Unit = {},
    val findPrevious: () -> Unit = {},
    /** What the terminal does with its own keys: copy what is selected, paste, select everything. */
    val copy: () -> Unit = {},
    val paste: () -> Unit = {},
    val selectAll: () -> Unit = {},
    /** Goes to the previous or the next prompt that the shell marked, scrolls the history by a page or to its ends. */
    val previousPrompt: () -> Unit = {},
    val nextPrompt: () -> Unit = {},
    val scrollPageUp: () -> Unit = {},
    val scrollPageDown: () -> Unit = {},
    val scrollToTop: () -> Unit = {},
    val scrollToBottom: () -> Unit = {},
    /** Makes the text of the application bigger or smaller by a step, or what it is by default. */
    val uiBigger: () -> Unit = {},
    val uiSmaller: () -> Unit = {},
    val uiReset: () -> Unit = {},
    /** Opens the dialog where the shortcuts are changed. */
    val editShortcuts: () -> Unit = {},
    /** Opens the main menu. */
    val openMenu: () -> Unit = {},
    /** Opens the list of the agents that need the user. */
    val openAttention: () -> Unit = {},
    /** Goes to the project before or after the selected one, wrapping around. */
    val previousProject: () -> Unit = {},
    val nextProject: () -> Unit = {},
    /** Goes to the workspace before the selected one, crossing projects. */
    val previousWorkspace: () -> Unit = {},
    val nextWorkspace: () -> Unit = {},
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
 * @param vertical Whether the layout is the vertical one, where the main direction of a split is down and not right: it decides which of the
 * two ways of splitting has the shortcut without Shift.
 * @param hasSelection Whether something is selected in the terminal: there is nothing to copy otherwise.
 */
fun paletteCommands(
    state: KattyState,
    infos: Map<TerminalId, AgentInfo>,
    titleOf: (TerminalSpec) -> String,
    handlers: PaletteHandlers,
    vertical: Boolean = false,
    hasSelection: Boolean = false,
): PaletteCommands {
    val entries = ArrayList<PaletteEntry>()
    val actions = HashMap<String, () -> Unit>()
    fun add(entry: PaletteEntry, action: () -> Unit) {
        entries += entry
        actions[entry.id] = action
    }

    for (project in state.projects) {
        add(PaletteEntry("project:${project.id.value}", PaletteGroup.PROJECTS, project.name, project.directory)) { handlers.selectProject(project.id) }
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
        add(PaletteEntry("command:$key", PaletteGroup.COMMANDS, label, shortcut = shortcut?.takeIf { it.isNotEmpty() }), action)

    val hasWorkspace = state.selectedWorkspace != null
    val hasTerminal = state.selection.terminal != null
    val selectedTerminal = state.selection.terminal?.let { state.locate(it) }
    val split = SplitLabels.of(vertical)
    command("add-project", "Add project folder…", AppShortcut.ADD_PROJECT.label, handlers.addProject)
    if (state.selection.project != null) command("new-workspace", "New workspace", AppShortcut.NEW_WORKSPACE.label, handlers.newWorkspace)
    state.selectedProject?.let { project ->
        val index = state.projects.indexOf(project)
        command("rename-project", "Rename project", action = handlers.renameProject)
        command("reveal-project", "Reveal project in the file manager", action = handlers.revealProject)
        if (index > 0) command("project-up", "Move project up") { handlers.moveProject(-1) }
        if (index < state.projects.lastIndex) command("project-down", "Move project down") { handlers.moveProject(1) }
        command("remove-project", "Remove project…", action = handlers.removeProject)
    }
    if (hasWorkspace) {
        command("rename-workspace", "Rename workspace", AppShortcut.RENAME_WORKSPACE.label, handlers.renameWorkspace)
        val workspaces = state.selectedProject?.workspaces.orEmpty()
        val index = workspaces.indexOfFirst { it.id == state.selection.workspace }
        if (index > 0) command("workspace-up", "Move workspace up") { handlers.moveWorkspace(-1) }
        if (index in 0 until workspaces.lastIndex) command("workspace-down", "Move workspace down") { handlers.moveWorkspace(1) }
        command("close-workspace", "Close workspace", AppShortcut.CLOSE_WORKSPACE.label, handlers.closeWorkspace)
    }
    if (hasTerminal) {
        command("split-right", "Split right", split.rightShortcut, handlers.splitRight)
        command("split-down", "Split down", split.downShortcut, handlers.splitDown)
        command("rename-terminal", "Rename terminal", AppShortcut.RENAME_TERMINAL.label, handlers.renameTerminal)
        command("restart-terminal", "Restart terminal", AppShortcut.RESTART_TERMINAL.label, handlers.restartTerminal)
        command("find", "Find in terminal", AppShortcut.FIND.label, handlers.find)
        command("find-next", "Find next match", AppShortcut.FIND_NEXT.label, handlers.findNext)
        command("find-previous", "Find previous match", AppShortcut.FIND_PREVIOUS.label, handlers.findPrevious)
        command("clear-screen", "Clear screen and scrollback", AppShortcut.CLEAR_SCREEN.label, handlers.clearScreen)
        command("maximize", if (state.selection.maximized == null) "Maximize terminal" else "Restore terminal", AppShortcut.MAXIMIZE.label, handlers.maximize)
        command("color-scheme", "Change the color scheme of the terminal", action = handlers.colorScheme)
        if (selectedTerminal?.terminal?.name != null) command("process-name", "Use process name for the terminal", action = handlers.useProcessName)
        command("copy-directory", "Copy working directory", action = handlers.copyDirectory)
        // The other workspaces of its project: what the menu of the pane offers in a submenu.
        for (destination in destinationsOf(state, state.selection.terminal!!)) {
            command("move-terminal:${destination.id.value}", "Move terminal to “${destination.label}”") { handlers.moveTerminalTo(destination.id) }
        }
        command("close-terminal", "Close terminal", AppShortcut.CLOSE_TERMINAL.label, handlers.closeTerminal)
        // What the terminal does with its own keys.
        if (hasSelection) command("copy", "Copy", TerminalKeys.COPY, handlers.copy)
        command("paste", "Paste", TerminalKeys.PASTE, handlers.paste)
        command("select-all", "Select all", TerminalKeys.SELECT_ALL, handlers.selectAll)
        command("previous-prompt", "Jump to the previous prompt", TerminalKeys.PREVIOUS_PROMPT, handlers.previousPrompt)
        command("next-prompt", "Jump to the next prompt", TerminalKeys.NEXT_PROMPT, handlers.nextPrompt)
        command("scroll-page-up", "Scroll up a page", TerminalKeys.SCROLL_PAGE_UP, handlers.scrollPageUp)
        command("scroll-page-down", "Scroll down a page", TerminalKeys.SCROLL_PAGE_DOWN, handlers.scrollPageDown)
        command("scroll-top", "Scroll to the top", TerminalKeys.SCROLL_TO_TOP, handlers.scrollToTop)
        command("scroll-bottom", "Scroll to the bottom", TerminalKeys.SCROLL_TO_BOTTOM, handlers.scrollToBottom)
        // Moving between the panes, and resizing them, is for a workspace that has more than one.
        if ((state.selectedWorkspace?.layout?.terminals()?.size ?: 0) > 1) {
            command("focus-left", "Focus the pane on the left", AppShortcut.FOCUS_LEFT.label) { handlers.focusPane(Direction.LEFT) }
            command("focus-right", "Focus the pane on the right", AppShortcut.FOCUS_RIGHT.label) { handlers.focusPane(Direction.RIGHT) }
            command("focus-up", "Focus the pane above", AppShortcut.FOCUS_UP.label) { handlers.focusPane(Direction.UP) }
            command("focus-down", "Focus the pane below", AppShortcut.FOCUS_DOWN.label) { handlers.focusPane(Direction.DOWN) }
            command("resize-left", "Move the divider to the left", AppShortcut.RESIZE_LEFT.label) { handlers.resizeSplit(Direction.LEFT) }
            command("resize-right", "Move the divider to the right", AppShortcut.RESIZE_RIGHT.label) { handlers.resizeSplit(Direction.RIGHT) }
            command("resize-up", "Move the divider up", AppShortcut.RESIZE_UP.label) { handlers.resizeSplit(Direction.UP) }
            command("resize-down", "Move the divider down", AppShortcut.RESIZE_DOWN.label) { handlers.resizeSplit(Direction.DOWN) }
        }
    }
    if (state.projects.size > 1) {
        command("previous-project", "Previous project", AppShortcut.PROJECT_PREVIOUS.label, handlers.previousProject)
        command("next-project", "Next project", AppShortcut.PROJECT_NEXT.label, handlers.nextProject)
    }
    if (state.projects.sumOf { it.workspaces.size } > 1) {
        command("previous-workspace", "Previous workspace", AppShortcut.WORKSPACE_PREVIOUS.label, handlers.previousWorkspace)
        command("next-workspace", "Next workspace", AppShortcut.WORKSPACE_NEXT.label, handlers.nextWorkspace)
    }
    command("jump", "Jump to next waiting agent", AppShortcut.JUMP_TO_WAITING.label, handlers.jumpToWaiting)
    command("attention", "Show the agents that need you", AppShortcut.ATTENTION.label, handlers.openAttention)
    command("mark-seen", "Mark all agents seen", action = handlers.markAllSeen)
    command("view", if (state.settings.view == MainView.BOARD) "Show terminals" else "Show the agent board", AppShortcut.BOARD.label) {
        handlers.showView(if (state.settings.view == MainView.BOARD) MainView.TERMINALS else MainView.BOARD)
    }
    command("board-window", "Open the board in a window", action = handlers.openBoardWindow)
    command("sidebar", "Toggle project sidebar", AppShortcut.SIDEBAR.label, handlers.toggleSidebar)
    command("menu", "Open the main menu", AppShortcut.MENU.label, handlers.openMenu)
    command("edit-shortcuts", "Edit keyboard shortcuts…", action = handlers.editShortcuts)
    command("default-color-scheme", "Choose the default color scheme…", action = handlers.defaultColorScheme)
    command("ligatures", if (state.settings.ligatures) "Turn font ligatures off" else "Turn font ligatures on", action = handlers.toggleLigatures)
    command("usage-refresh", "Refresh the usage of the agents", action = handlers.refreshUsage)
    command("usage-compact", if (state.settings.compactUsage) "Show the usage in full" else "Show the usage in a compact form", action = handlers.toggleCompactUsage)
    command("ui-bigger", "Bigger interface text", action = handlers.uiBigger)
    command("ui-smaller", "Smaller interface text", action = handlers.uiSmaller)
    command("ui-reset", "Default interface text size", action = handlers.uiReset)
    command("font-bigger", "Bigger terminal font", AppShortcut.FONT_BIGGER.label, handlers.fontBigger)
    command("font-smaller", "Smaller terminal font", AppShortcut.FONT_SMALLER.label, handlers.fontSmaller)
    command("font-reset", "Default terminal font size", AppShortcut.FONT_RESET.label, handlers.fontReset)
    command("layout-auto", "Layout: auto") { handlers.setLayout(LayoutMode.AUTO) }
    command("layout-horizontal", "Layout: horizontal") { handlers.setLayout(LayoutMode.HORIZONTAL) }
    command("layout-vertical", "Layout: vertical") { handlers.setLayout(LayoutMode.VERTICAL) }
    command("theme-light", "Theme: light") { handlers.setTheme(ThemeMode.LIGHT) }
    command("theme-dark", "Theme: dark") { handlers.setTheme(ThemeMode.DARK) }
    command("theme-system", "Theme: system") { handlers.setTheme(ThemeMode.SYSTEM) }
    return PaletteCommands(entries, actions)
}
