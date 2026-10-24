package app.katty

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.katty.core.AgentState
import app.katty.core.AttentionItem
import app.katty.core.CloseDecision
import app.katty.core.Direction
import app.katty.core.Ide
import app.katty.core.KattyStore
import app.katty.core.MainView
import app.katty.core.ProjectId
import app.katty.core.Settings
import app.katty.core.SplitDirection
import app.katty.core.TerminalId
import app.katty.core.WorkspaceId
import app.katty.core.attention
import app.katty.core.closeTerminalDecision
import app.katty.core.closeWorkspaceDecision
import app.katty.core.neighbour
import app.katty.core.splitToNudge
import app.katty.core.terminals
import app.katty.screens.PaletteHandlers
import app.katty.screens.splitVisually
import app.katty.terminal.TerminalSession
import app.katty.terminals.TerminalHandle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** How much of a split a press of the resize shortcut moves, and how much the font grows or shrinks for one of the zoom ones. */
private const val SPLIT_STEP = 0.05f
private const val FONT_STEP = 1f

/** What the window asks of the system: a folder, the clipboard, the file manager, another application, its other windows. */
internal interface WindowPlatform {
    fun chooseFolder(title: String, initial: String?): String?

    fun copy(text: String)

    fun readClipboard(): String?

    fun reveal(directory: String)

    fun openIn(bundleId: String, directory: String)

    fun openBoardWindow()

    fun openAbout()
}

/** A paste that waits for the user: [answer] is completed with whether to paste [text]. */
internal class PasteRequest(val text: String) {
    val answer = CompletableDeferred<Boolean>()
}

/** Whose color scheme the picker chooses. */
internal sealed interface SchemeTarget {
    /** The default of the settings, which the terminals that have no scheme of their own follow. */
    data object Default : SchemeTarget

    data class Terminal(val id: TerminalId) : SchemeTarget
}

/**
 * What the main window holds and does, apart from drawing it: what is open over it (the dialogs, the palette, the menus, a rename, a find bar),
 * and the actions that its shortcuts and its palette run, which are the same functions, so that both follow the same rules: the modal rule (an
 * open dialog keeps the shortcuts from acting), and what acts on a terminal is for the terminals view, not the board, which shows none.
 *
 * Its state is snapshot state, which the window draws from; it has no Compose of its own and is tested without a window. The window gives it
 * what it collects ([handles], [infos], [vertical], the editors that are installed) before each event can use it.
 *
 * @param scope Where a paste waits for the user's answer.
 * @param restart Starts a terminal's shell again.
 * @param markAllSeen Takes the finished and failed agents off the list of what needs the user.
 */
internal class WindowController(
    private val store: KattyStore,
    private val scope: CoroutineScope,
    private val platform: WindowPlatform,
    private val restart: (TerminalId) -> Unit,
    private val markAllSeen: () -> Unit,
) {
    var renamingWorkspace by mutableStateOf<WorkspaceId?>(null)
    var renamingProject by mutableStateOf<ProjectId?>(null)
    var renamingTerminal by mutableStateOf<TerminalId?>(null)
    var removing by mutableStateOf<ProjectId?>(null)
    var findingTerminal by mutableStateOf<TerminalId?>(null)
    var menuOpen by mutableStateOf(false)
    var attentionOpen by mutableStateOf(false)
    var paletteOpen by mutableStateOf(false)
    var editingShortcuts by mutableStateOf(false)

    // The terminal that is selected takes the keyboard back whenever this changes: when what had it (a palette, a menu, a rename) is gone, and when a
    // terminal is opened, even if it is the one that was selected.
    var focusTick by mutableIntStateOf(0)

    // The project that the board shows, `null` for every project.
    var boardFilter by mutableStateOf<ProjectId?>(null)
    var toast by mutableStateOf<AttentionItem?>(null)

    // Whose color scheme is being chosen.
    var picking by mutableStateOf<SchemeTarget?>(null)

    // What the user has to confirm before it is closed.
    var closingWorkspace by mutableStateOf<WorkspaceId?>(null)
    var closingTerminal by mutableStateOf<TerminalId?>(null)
    var restartingTerminal by mutableStateOf<TerminalId?>(null)

    // A paste that could run commands, waiting for the user's answer.
    var pasting by mutableStateOf<PasteRequest?>(null)

    /** The sessions of the terminals, as the registry has them now. */
    var handles: Map<TerminalId, TerminalHandle> = emptyMap()

    /** What each terminal's program reports. */
    var infos: Map<TerminalId, app.katty.core.AgentInfo> = emptyMap()

    /** Whether the window shows the vertical layout, which draws every split turned. */
    var vertical: Boolean = false

    /** The editors that are installed, by their bundle identifier. */
    var installedIdes: Map<Ide, String> = emptyMap()

    /** Reads the usage of the agents again at once. */
    var refreshUsage: () -> Unit = {}

    private val state get() = store.state.value

    /** What is on top of the window has the keys, and ends in its own way: the palette with its shortcut, a dialog with its buttons. */
    val blockingDialog: Boolean by derivedStateOf {
        picking != null || closingWorkspace != null || closingTerminal != null || restartingTerminal != null || removing != null || pasting != null
    }

    /** Whether anything is over the terminals, which then do not take the keyboard. */
    val overlayOpen: Boolean by derivedStateOf {
        blockingDialog || editingShortcuts || paletteOpen || menuOpen || attentionOpen || renamingWorkspace != null || renamingProject != null ||
            renamingTerminal != null
    }

    private val onTerminals get() = state.settings.view == MainView.TERMINALS

    /** The terminals that are on the screen: the ones of the selected workspace, none while the board is shown. */
    fun shown(): Set<TerminalId> = if (onTerminals) state.selectedWorkspace?.layout?.terminals()?.map { it.id }?.toSet().orEmpty() else emptySet()

    /** The state of a terminal, as its program reports it. */
    fun stateOf(id: TerminalId): AgentState = infos[id]?.state ?: AgentState.IDLE

    // What shows a rename of a workspace is the sidebar (not folded into the rail) or the tabs, over the terminals and not the board: when it is
    // not there, nothing could end the rename, and the terminals would not get the keyboard back.
    fun showWorkspaceHost() {
        val current = state.settings
        if (current.view != MainView.TERMINALS || (!vertical && current.sidebarCollapsed)) {
            store.updateSettings { it.copy(view = MainView.TERMINALS, sidebarCollapsed = if (vertical) it.sidebarCollapsed else false) }
        }
    }

    fun showTerminals() {
        if (state.settings.view != MainView.TERMINALS) store.updateSettings { it.copy(view = MainView.TERMINALS) }
    }

    fun addProject() {
        val folder = platform.chooseFolder("Add project folder", state.selectedProject?.directory) ?: return
        val before = state.projects.size
        store.addProject(folder)
        // A project that is new has a workspace with its default name, which opens for editing as a new workspace does.
        if (state.projects.size > before) {
            showWorkspaceHost()
            renamingWorkspace = state.selection.workspace
        }
    }

    fun newWorkspace() {
        val project = state.selection.project ?: return
        showWorkspaceHost()
        renamingWorkspace = store.addWorkspace(project)
    }

    /** Whether a program other than the shell has the terminal: closing or restarting it would stop it. */
    fun running(id: TerminalId): Boolean {
        val handle = handles[id]
        return handle?.session?.exited?.value == null && handle?.processName?.value.let { it != null && it != handle?.shellName }
    }

    // Closing asks first when a program, or an agent that is active, would be stopped, and does not otherwise.
    fun closeWorkspace(id: WorkspaceId) {
        if (state.closeWorkspaceDecision(id, ::stateOf, ::running) == CloseDecision.NOW) store.closeWorkspace(id) else closingWorkspace = id
    }

    fun closeTerminal(id: TerminalId) {
        if (state.closeTerminalDecision(id, ::stateOf, ::running) == CloseDecision.NOW) store.closeTerminal(id) else closingTerminal = id
    }

    fun restartTerminal(id: TerminalId) {
        if (running(id)) restartingTerminal = id else restart(id)
    }

    /** Goes to a terminal, whatever the window is showing. */
    fun openTerminal(id: TerminalId) {
        store.updateSettings { it.copy(view = MainView.TERMINALS) }
        store.focus(id)
        // Where it is can be seen.
        state.locate(id)?.project?.id?.let { store.setProjectOpen(it, true) }
        attentionOpen = false
        toast = null
        focusTick++
    }

    /** Goes to a workspace, whatever the window is showing, and gives the keyboard to its terminal. */
    fun goToWorkspace(id: WorkspaceId) {
        store.selectWorkspace(id)
        showTerminals()
        focusTick++
    }

    /** Goes to a project, at the workspace that was selected in it last, whatever the window is showing. */
    fun goToProject(id: ProjectId) {
        store.selectProject(id)
        store.setProjectOpen(id, true)
        showTerminals()
        focusTick++
    }

    // The state of the store, not the one of the last drawing: keys that repeat come faster than that.
    fun stepProject(delta: Int) {
        state.projectStep(delta)?.let(::goToProject)
    }

    fun stepWorkspace(delta: Int) {
        state.workspaceStep(delta)?.let(::goToWorkspace)
    }

    fun focusNeighbour(direction: Direction) {
        val workspace = state.selectedWorkspace ?: return
        val from = state.selection.terminal ?: return
        workspace.layout.neighbour(from, direction, vertical)?.let(store::focus)
    }

    // The nearest divider of the focused pane that the arrow goes along moves one step: as the screen shows the splits, turned in the vertical layout.
    fun resizeSplit(direction: Direction) {
        val workspace = state.selectedWorkspace ?: return
        val from = state.selection.terminal ?: return
        workspace.layout.splitToNudge(from, direction, SPLIT_STEP, vertical)?.let { (split, ratio) -> store.setSplitRatio(workspace.id, split, ratio) }
    }

    /** The session of the selected terminal: what the commands on the terminal's own keys act on. */
    fun selectedSession(): TerminalSession? = state.selection.terminal?.let { handles[it]?.session }

    /**
     * Asks the user before a paste that could run commands, whatever pastes it (a terminal's keys and middle click, its menu, the palette): one
     * question at a time, and a new one answers no to the one that was still waiting.
     */
    suspend fun confirmPaste(text: String): Boolean {
        val request = PasteRequest(text)
        pasting?.answer?.complete(false)
        pasting = request
        return try {
            request.answer.await()
        } finally {
            if (pasting === request) pasting = null
        }
    }

    fun pasteInto(session: TerminalSession, text: String) {
        if (!session.isPasteUnsafe(text)) session.paste(text) else scope.launch { if (confirmPaste(text)) session.paste(text) }
    }

    // The next or the previous match of the search of the terminal that is searched: a terminal that is not opens its search, since there is no match to
    // go to (the shortcut does the same).
    fun stepFind(forward: Boolean) {
        val searched = findTarget(findingTerminal, shown(), handles.filterValues { it.session != null }.keys)
        if (searched == null) {
            findingTerminal = state.selection.terminal
        } else {
            handles[searched]?.session?.search?.let { if (forward) it.next() else it.previous() }
        }
    }

    fun jumpToWaiting() {
        state.attention(infos).nextWaiting(state, state.selection.terminal)?.let(::openTerminal)
    }

    fun maximize() {
        val terminal = state.selection.terminal ?: return
        store.maximize(if (state.selection.maximized == null) terminal else null)
    }

    fun openProjectIn(id: ProjectId, ide: Ide) {
        val directory = state.projects.firstOrNull { it.id == id }?.directory ?: return
        val bundleId = installedIdes[ide] ?: return
        platform.openIn(bundleId, directory)
    }

    fun revealProject(id: ProjectId) {
        state.projects.firstOrNull { it.id == id }?.let { platform.reveal(it.directory) }
    }

    /** A rename of what is gone, or that nothing draws (the board shows no names, and the rail no field), ends: it would keep the keyboard for good. */
    fun dropStaleRenames() {
        val current = state
        val hostShown = current.settings.view == MainView.TERMINALS && (vertical || !current.settings.sidebarCollapsed)
        if (renamingWorkspace != null && (!hostShown || current.projects.none { p -> p.workspaces.any { it.id == renamingWorkspace } })) renamingWorkspace = null
        if (renamingProject != null && (!hostShown || current.projects.none { it.id == renamingProject })) renamingProject = null
        if (renamingTerminal != null && (current.settings.view != MainView.TERMINALS || renamingTerminal?.let(current::locate) == null)) renamingTerminal = null
    }

    /**
     * A find bar that is not on the screen any more (another workspace, the board, a terminal closed or started again) is closed: its query was
     * cleared with it, and the find shortcuts would step a search that nobody sees.
     */
    fun dropStaleFind() {
        if (findingTerminal != null && findTarget(findingTerminal, shown(), handles.filterValues { it.session != null }.keys) == null) findingTerminal = null
    }

    /**
     * What a shortcut of the window does, `true` when it is consumed. While the dialog of the shortcuts is open it sees every key, the ones that
     * are shortcuts too: they are what it records. While a dialog, the colour picker or a menu is open the others are swallowed, except the one
     * that closes what is open.
     *
     * @param number The digit of a shortcut that takes one (a workspace or a pane by its number).
     */
    fun handle(shortcut: AppShortcut?, number: Int?): Boolean {
        if (editingShortcuts) return false
        shortcut ?: return false
        if (blockingDialog || (menuOpen && shortcut != AppShortcut.MENU) || (attentionOpen && shortcut != AppShortcut.ATTENTION) ||
            (paletteOpen && shortcut != AppShortcut.PALETTE)
        ) {
            return true
        }
        if (blockedWhileRenaming(shortcut, state.selection.terminal, state.selection.workspace, renamingTerminal, renamingWorkspace)) return true
        val terminal = state.selection.terminal
        when (shortcut) {
            AppShortcut.ADD_PROJECT -> addProject()
            AppShortcut.NEW_WORKSPACE -> newWorkspace()
            AppShortcut.CLOSE_WORKSPACE -> state.selection.workspace?.let(::closeWorkspace)
            AppShortcut.SPLIT_MAIN -> onTerminal { store.split(it, SplitDirection.ROW) }
            AppShortcut.SPLIT_OTHER -> onTerminal { store.split(it, SplitDirection.COLUMN) }
            AppShortcut.CLOSE_TERMINAL -> onTerminal(::closeTerminal)
            AppShortcut.RESTART_TERMINAL -> onTerminal(::restartTerminal)
            AppShortcut.JUMP_TO_WAITING -> jumpToWaiting()
            AppShortcut.ATTENTION -> attentionOpen = !attentionOpen
            AppShortcut.MAXIMIZE -> onTerminals { maximize() }
            AppShortcut.RESIZE_LEFT -> onTerminals { resizeSplit(Direction.LEFT) }
            AppShortcut.RESIZE_RIGHT -> onTerminals { resizeSplit(Direction.RIGHT) }
            AppShortcut.RESIZE_UP -> onTerminals { resizeSplit(Direction.UP) }
            AppShortcut.RESIZE_DOWN -> onTerminals { resizeSplit(Direction.DOWN) }
            AppShortcut.FIND_NEXT -> onTerminals { stepFind(true) }
            AppShortcut.FIND_PREVIOUS -> onTerminals { stepFind(false) }
            AppShortcut.FONT_BIGGER -> fontBy(FONT_STEP)
            AppShortcut.FONT_SMALLER -> fontBy(-FONT_STEP)
            AppShortcut.FONT_RESET -> store.updateSettings { it.copy(fontSize = Settings.DEFAULT_FONT_SIZE) }
            AppShortcut.MENU -> menuOpen = !menuOpen
            AppShortcut.BOARD -> store.updateSettings { it.copy(view = if (it.view == MainView.BOARD) MainView.TERMINALS else MainView.BOARD) }
            // The vertical layout has no sidebar, and the board has its own.
            AppShortcut.SIDEBAR -> if (!vertical && onTerminals) toggleSidebar()
            AppShortcut.PALETTE -> paletteOpen = !paletteOpen
            AppShortcut.FOCUS_LEFT -> onTerminals { focusNeighbour(Direction.LEFT) }
            AppShortcut.FOCUS_RIGHT -> onTerminals { focusNeighbour(Direction.RIGHT) }
            AppShortcut.FOCUS_UP -> onTerminals { focusNeighbour(Direction.UP) }
            AppShortcut.FOCUS_DOWN -> onTerminals { focusNeighbour(Direction.DOWN) }
            AppShortcut.CLEAR_SCREEN -> onTerminal { handles[it]?.session?.clearScreen() }
            AppShortcut.FIND -> onTerminals { findingTerminal = terminal }
            AppShortcut.RENAME_TERMINAL -> renameTerminal()
            AppShortcut.RENAME_WORKSPACE -> renameWorkspace()
            AppShortcut.WORKSPACE_PREVIOUS -> stepWorkspace(-1)
            AppShortcut.WORKSPACE_NEXT -> stepWorkspace(1)
            AppShortcut.PROJECT_PREVIOUS -> stepProject(-1)
            AppShortcut.PROJECT_NEXT -> stepProject(1)
            AppShortcut.WORKSPACE_NUMBER -> number?.let { state.workspaceByNumber(it) }?.let(::goToWorkspace)
            AppShortcut.PANE_NUMBER -> number?.let { state.terminalByNumber(it) }?.let(::openTerminal)
        }
        return true
    }

    // What acts on a terminal is for the terminals, not for the board, which shows none: it is as if there were none.
    private inline fun onTerminals(action: () -> Unit) {
        if (onTerminals) action()
    }

    private inline fun onTerminal(action: (TerminalId) -> Unit) {
        if (onTerminals) state.selection.terminal?.let(action)
    }

    private fun toggleSidebar() = store.updateSettings { it.copy(sidebarCollapsed = !it.sidebarCollapsed) }

    private fun fontBy(step: Float) = store.updateSettings { it.copy(fontSize = it.fontSize + step) }

    private fun renameTerminal() {
        val terminal = state.selection.terminal ?: return
        showTerminals()
        renamingTerminal = terminal
    }

    private fun renameWorkspace() {
        val workspace = state.selection.workspace ?: return
        showWorkspaceHost()
        renamingWorkspace = workspace
    }

    private fun moveSelected(delta: Int, ids: List<Any>, selected: Any?, move: (Int, Int) -> Unit) {
        val from = ids.indexOf(selected)
        if (from >= 0) move(from, (from + delta).coerceIn(ids.indices))
    }

    /** What the palette runs: the same actions as the shortcuts, under the same rules. */
    fun paletteHandlers(): PaletteHandlers = PaletteHandlers(
        addProject = ::addProject,
        newWorkspace = ::newWorkspace,
        renameWorkspace = ::renameWorkspace,
        closeWorkspace = { state.selection.workspace?.let(::closeWorkspace) },
        splitRight = { onTerminal { store.splitVisually(it, right = true, flipped = vertical) } },
        splitDown = { onTerminal { store.splitVisually(it, right = false, flipped = vertical) } },
        renameTerminal = ::renameTerminal,
        closeTerminal = { onTerminal(::closeTerminal) },
        restartTerminal = { onTerminal(::restartTerminal) },
        find = { onTerminals { findingTerminal = state.selection.terminal } },
        clearScreen = { onTerminal { handles[it]?.session?.clearScreen() } },
        maximize = { onTerminals { maximize() } },
        colorScheme = { state.selection.terminal?.let { picking = SchemeTarget.Terminal(it) } },
        toggleSidebar = ::toggleSidebar,
        showView = { view -> store.updateSettings { it.copy(view = view) } },
        setLayout = { mode -> store.updateSettings { it.copy(layout = mode) } },
        setTheme = { theme -> store.updateSettings { it.copy(theme = theme) } },
        setSecureInput = { secure -> store.updateSettings { it.copy(secureInput = secure) } },
        jumpToWaiting = ::jumpToWaiting,
        markAllSeen = markAllSeen,
        openBoardWindow = platform::openBoardWindow,
        openTerminal = ::openTerminal,
        openWorkspace = ::goToWorkspace,
        selectProject = ::goToProject,
        previousProject = { stepProject(-1) },
        nextProject = { stepProject(1) },
        renameProject = { showWorkspaceHost(); renamingProject = state.selection.project },
        removeProject = { removing = state.selection.project },
        revealProject = { state.selection.project?.let(::revealProject) },
        ides = installedIdes.keys.toList(),
        openProjectIn = { ide -> state.selection.project?.let { openProjectIn(it, ide) } },
        moveProject = { delta ->
            val projects = state.projects
            moveSelected(delta, projects.map { it.id }, state.selection.project) { from, to -> store.moveProject(projects[from].id, to) }
        },
        moveWorkspace = { delta ->
            val workspaces = state.selectedProject?.workspaces.orEmpty()
            moveSelected(delta, workspaces.map { it.id }, state.selection.workspace) { from, to -> store.moveWorkspace(workspaces[from].id, to) }
        },
        moveTerminalTo = { workspace -> state.selection.terminal?.let { store.moveTerminal(it, workspace) } },
        useProcessName = { state.selection.terminal?.let { store.renameTerminal(it, null) } },
        copyDirectory = { state.selection.terminal?.let { id -> state.locate(id)?.terminal?.directory?.let(platform::copy) } },
        defaultColorScheme = { picking = SchemeTarget.Default },
        toggleLigatures = { store.updateSettings { it.copy(ligatures = !it.ligatures) } },
        refreshUsage = { refreshUsage() },
        toggleCompactUsage = { store.updateSettings { it.copy(compactUsage = !it.compactUsage) } },
        focusPane = { direction -> onTerminals { focusNeighbour(direction) } },
        resizeSplit = { direction -> onTerminals { resizeSplit(direction) } },
        findNext = { onTerminals { stepFind(true) } },
        findPrevious = { onTerminals { stepFind(false) } },
        copy = { onTerminals { selectedSession()?.selectedText()?.let(platform::copy) } },
        paste = { onTerminals { selectedSession()?.let { session -> platform.readClipboard()?.let { pasteInto(session, it) } } } },
        selectAll = { onTerminals { selectedSession()?.selectAll() } },
        previousPrompt = { onTerminals { selectedSession()?.scrollToPrompt(-1) } },
        nextPrompt = { onTerminals { selectedSession()?.scrollToPrompt(1) } },
        scrollPageUp = { onTerminals { selectedSession()?.scrollPage(-1) } },
        scrollPageDown = { onTerminals { selectedSession()?.scrollPage(1) } },
        scrollToTop = { onTerminals { selectedSession()?.scrollToTop() } },
        scrollToBottom = { onTerminals { selectedSession()?.scrollToBottom() } },
        editShortcuts = { editingShortcuts = true },
        openAbout = platform::openAbout,
        openAttention = { attentionOpen = true },
        uiBigger = { store.updateSettings { it.copy(uiScale = it.steppedUiScale(1)) } },
        uiSmaller = { store.updateSettings { it.copy(uiScale = it.steppedUiScale(-1)) } },
        uiReset = { store.updateSettings { it.copy(uiScale = Settings.DEFAULT_UI_SCALE) } },
        fontBigger = { fontBy(FONT_STEP) },
        fontSmaller = { fontBy(-FONT_STEP) },
        fontReset = { store.updateSettings { it.copy(fontSize = Settings.DEFAULT_FONT_SIZE) } },
        openMenu = { menuOpen = true },
        previousWorkspace = { stepWorkspace(-1) },
        nextWorkspace = { stepWorkspace(1) },
    )
}

/**
 * The terminal whose search the find shortcuts step: the one whose find bar is open ([finding]), while it is on the screen ([shown]) and has a
 * session ([running]). `null` otherwise, and the shortcuts open a find bar on the selected terminal instead.
 */
internal fun findTarget(finding: TerminalId?, shown: Set<TerminalId>, running: Set<TerminalId>): TerminalId? =
    finding?.takeIf { it in shown && it in running }

/**
 * Whether [shortcut] is kept from acting while a name is being edited: closing or restarting the terminal that is being renamed, or closing the
 * workspace that is, would take the field away under the keys.
 */
internal fun blockedWhileRenaming(
    shortcut: AppShortcut,
    selectedTerminal: TerminalId?,
    selectedWorkspace: WorkspaceId?,
    renamingTerminal: TerminalId?,
    renamingWorkspace: WorkspaceId?,
): Boolean = when (shortcut) {
    AppShortcut.CLOSE_TERMINAL, AppShortcut.RESTART_TERMINAL -> renamingTerminal != null && renamingTerminal == selectedTerminal
    AppShortcut.CLOSE_WORKSPACE -> renamingWorkspace != null && renamingWorkspace == selectedWorkspace
    else -> false
}
