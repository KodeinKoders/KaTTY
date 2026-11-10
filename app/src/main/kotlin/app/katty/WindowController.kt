package app.katty

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.katty.core.AgentState
import app.katty.core.AttentionItem
import app.katty.core.CloseDecision
import app.katty.core.ClosedItem
import app.katty.core.Direction
import app.katty.core.GitReader
import app.katty.core.Ide
import app.katty.core.KattyState
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
import app.katty.core.quitDecision
import app.katty.core.stoppedByQuitting
import app.katty.core.splitToNudge
import app.katty.core.terminalIdentity
import app.katty.core.terminals
import app.katty.screens.PaletteHandlers
import app.katty.screens.splitVisually
import app.katty.terminal.TerminalFilePosition
import app.katty.terminal.TerminalSession
import app.katty.terminals.ExportFormat
import app.katty.terminals.TerminalHandle
import app.katty.terminals.exportContent
import app.katty.terminals.exportFailureReason
import app.katty.terminals.exportFileName
import app.katty.terminals.writeExport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Path
import java.time.LocalDateTime
import kotlin.math.roundToInt

/** How much of a split a press of the resize shortcut moves, and how much the font grows or shrinks for one of the zoom ones. */
private const val SPLIT_STEP = 0.05f
private const val FONT_STEP = 1f

// How much bigger the terminals' font is in presentation mode than the setting.
private const val PRESENTATION_SCALE = 1.5f

/** The size of the terminals' font in presentation mode, for the [fontSize] of the settings: half as big again, in whole points, within the limits. */
internal fun presentationFontSize(fontSize: Float): Float =
    (fontSize * PRESENTATION_SCALE).roundToInt().toFloat().coerceIn(Settings.MIN_FONT_SIZE, Settings.MAX_FONT_SIZE)

/** What the window asks of the system: a folder, the clipboard, the file manager, another application, its other windows. */
internal interface WindowPlatform {
    fun chooseFolder(title: String, initial: String?): String?

    /** Asks where a file is saved, proposing [name] in [directory]: the path chosen, `null` when the user cancelled. */
    fun chooseSaveFile(title: String, directory: String?, name: String): String?

    fun copy(text: String)

    fun readClipboard(): String?

    fun reveal(directory: String)

    fun openIn(bundleId: String, directory: String)

    /** Opens [file] at its line in [ide], installed as [bundleId]. */
    fun openFile(ide: Ide, bundleId: String, file: TerminalFilePosition)

    fun openBoardWindow()

    fun openAbout()
}

/** A paste that waits for the user: [answer] is completed with whether to paste [text]. */
internal class PasteRequest(val text: String) {
    val answer = CompletableDeferred<Boolean>()
}

/** The path of a [file] that was clicked in a terminal of [workspace], whose IDE the user chooses. */
internal data class IdeRequest(val workspace: WorkspaceId, val file: TerminalFilePosition)

/** A quit that waits for the user, whose [answer] is whether to quit. */
internal class QuitRequest {
    val answer = CompletableDeferred<Boolean>()
}

/** Whose color scheme the picker chooses. */
internal sealed interface SchemeTarget {
    /** The default of the settings for the [dark] or the light application, which the terminals that have no scheme of their own follow. */
    data class Default(val dark: Boolean) : SchemeTarget

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
 * @param markSeen Takes what a terminal finished or failed at off that list: the user has gone to it.
 */
internal class WindowController(
    private val store: KattyStore,
    private val scope: CoroutineScope,
    private val platform: WindowPlatform,
    private val restart: (TerminalId) -> Unit,
    private val markAllSeen: () -> Unit,
    private val markSeen: (TerminalId) -> Unit = {},
) {
    var renamingWorkspace by mutableStateOf<WorkspaceId?>(null)
    var renamingProject by mutableStateOf<ProjectId?>(null)
    var renamingTerminal by mutableStateOf<TerminalId?>(null)
    var removing by mutableStateOf<ProjectId?>(null)
    var findingTerminal by mutableStateOf<TerminalId?>(null)
    var menuOpen by mutableStateOf(false)
    var attentionOpen by mutableStateOf(false)
    var paletteOpen by mutableStateOf(false)

    /** The list of the projects that were opened before, which works like the palette. */
    var recentOpen by mutableStateOf(false)

    /** The search of every terminal's history, which works like the palette. */
    var searchingAll by mutableStateOf(false)

    // What the find bar that opens starts with, and a number that changes at each such opening, so that a bar that is open is made again with it:
    // the text that was searched in every terminal, when one of its rows is gone to.
    var findQuery by mutableStateOf("")
    var findRequest by mutableIntStateOf(0)
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

    // A quit that would stop what runs, waiting for the user's answer.
    var quitting by mutableStateOf<QuitRequest?>(null)

    // A file whose path was clicked, waiting for the IDE that opens it; and the workspace whose IDE is being chosen from its menu.
    var choosingIde by mutableStateOf<IdeRequest?>(null)
    var editingWorkspaceIde by mutableStateOf<WorkspaceId?>(null)

    // The terminal whose history is being exported, while its format is chosen, and why the last export failed, until the user has read it.
    var exportingHistory by mutableStateOf<TerminalId?>(null)
    var exportFailure by mutableStateOf<String?>(null)

    /**
     * Presentation mode: the window shows only the terminals, in a font of their own ([presentationFontSize], which the font shortcuts change
     * then, and not the setting), with the keys that are pressed. A mode of the window and not a setting: it is not saved.
     */
    var presenting by mutableStateOf(false)
        private set
    var presentationFontSize by mutableFloatStateOf(Settings.DEFAULT_FONT_SIZE)
        private set

    /** The keys that presentation mode shows. */
    val presentedKeys = PresentedKeys()

    /** The format of the last export, which the next one starts on: in memory, as the folder. */
    var lastExportFormat: ExportFormat = ExportFormat.TEXT
        private set
    private var lastExportFolder: String? = null

    /** The sessions of the terminals, as the registry has them now. */
    var handles: Map<TerminalId, TerminalHandle> = emptyMap()

    /** What each terminal's program reports. */
    var infos: Map<TerminalId, app.katty.core.AgentInfo> = emptyMap()

    /** The commands that ended where the user was not looking, until they have seen them. */
    var commands: Map<TerminalId, app.katty.core.FinishedCommand> = emptyMap()

    /** Whether the window shows the vertical layout, which draws every split turned. */
    var vertical: Boolean = false

    /** The editors that are installed, by their bundle identifier. */
    var installedIdes: Map<Ide, String> = emptyMap()

    /** Reads the usage of the agents again at once. */
    var refreshUsage: () -> Unit = {}

    private val state get() = store.state.value

    /** What is on top of the window has the keys, and ends in its own way: the palette with its shortcut, a dialog with its buttons. */
    val blockingDialog: Boolean by derivedStateOf {
        picking != null || closingWorkspace != null || closingTerminal != null || restartingTerminal != null || removing != null || pasting != null ||
            quitting != null || recentOpen || choosingIde != null || editingWorkspaceIde != null || exportingHistory != null || exportFailure != null
    }

    /** Whether anything is over the terminals, which then do not take the keyboard. */
    val overlayOpen: Boolean by derivedStateOf {
        blockingDialog || editingShortcuts || paletteOpen || searchingAll || menuOpen || attentionOpen || renamingWorkspace != null || renamingProject != null ||
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

    fun openRecent() {
        paletteOpen = false
        menuOpen = false
        recentOpen = true
    }

    /** Opens the search of every terminal's history, over whatever the window shows. */
    fun searchAll() {
        paletteOpen = false
        menuOpen = false
        searchingAll = true
    }

    /**
     * Goes to a row of a terminal where the search of every terminal found [query]: the terminal is shown with the keyboard, its history scrolled so
     * that the row is in its upper third, and its find bar open with [query], which shows every match of it there.
     */
    fun openSearchResult(terminal: TerminalId, row: Long, query: String) {
        searchingAll = false
        if (state.locate(terminal) == null) return
        openTerminal(terminal)
        handles[terminal]?.session?.let { session ->
            val rows = session.frames.value.rows.size
            session.scrollToRow((row - rows / 3).coerceAtLeast(0))
        }
        findQuery = query
        findRequest++
        findingTerminal = terminal
    }

    /** A find bar that is opened by the user starts empty. */
    fun openFind(terminal: TerminalId?) {
        if (findingTerminal != terminal) findQuery = ""
        findingTerminal = terminal
    }

    /** Opens a project of the recent list again, and goes to it. */
    fun openRecentProject(directory: String, name: String) {
        recentOpen = false
        goToProject(store.addProject(directory, name))
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

    /**
     * Opens what was closed last again (a terminal at its place, or a whole workspace: see `KattyStore.reopenClosed`), and shows it with the keyboard,
     * whatever the window shows. Nothing happens when nothing was closed lately.
     */
    fun reopenClosed() {
        store.reopenClosed() ?: return
        showTerminals()
        focusTick++
    }

    /** Goes to a terminal, whatever the window is showing: what it finished or failed at has been seen then. */
    fun openTerminal(id: TerminalId) {
        store.updateSettings { it.copy(view = MainView.TERMINALS) }
        store.focus(id)
        markSeen(id)
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
    /**
     * Whether to quit: at once when nothing would be stopped, else when the user says so in the dialog that lists what would be ([stoppedByQuitting]).
     * A second request while the question waits (the close button, then ⌘Q) has the same answer.
     */
    suspend fun confirmQuit(): Boolean {
        if (state.quitDecision(::stateOf, ::running) == CloseDecision.NOW) return true
        val request = quitting ?: QuitRequest().also { quitting = it }
        return try {
            request.answer.await()
        } finally {
            if (quitting === request) quitting = null
        }
    }

    /** The terminals that quitting would stop, with where they are. */
    fun stoppedByQuitting(): List<KattyState.Location> = state.stoppedByQuitting(::stateOf, ::running)

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
            openFind(state.selection.terminal)
        } else {
            handles[searched]?.session?.search?.let { if (forward) it.next() else it.previous() }
        }
    }

    fun jumpToWaiting() {
        state.attention(infos, commands).next(state, state.selection.terminal)?.let(::openTerminal)
    }

    fun maximize() {
        val terminal = state.selection.terminal ?: return
        store.maximize(if (state.selection.maximized == null) terminal else null)
    }

    fun openProjectIn(id: ProjectId, ide: Ide) {
        openIn(state.projects.firstOrNull { it.id == id }?.directory ?: return, ide)
    }

    private fun openIn(directory: String, ide: Ide) {
        val bundleId = installedIdes[ide] ?: return
        platform.openIn(bundleId, directory)
    }

    /**
     * Opens the file or the folder whose path was clicked in [terminal]: in the IDE of its workspace when it has one that is installed, else the user
     * is asked which (an IDE that the workspace keeps and that is not installed any more is asked about again).
     */
    fun openFile(terminal: TerminalId, file: TerminalFilePosition) {
        val workspace = state.locate(terminal)?.workspace ?: return
        val kept = Ide.named(workspace.ide)?.takeIf { it in installedIdes }
        if (kept != null) openFileIn(kept, file) else if (installedIdes.isNotEmpty()) choosingIde = IdeRequest(workspace.id, file)
    }

    /** The user chose [ide] for the file that waits, and whether the workspace keeps it for the next ones. */
    fun answerIde(ide: Ide, remember: Boolean) {
        val request = choosingIde ?: return
        choosingIde = null
        if (remember) store.setWorkspaceIde(request.workspace, ide)
        openFileIn(ide, request.file)
    }

    /** The IDE of a workspace, chosen from its menu: `null` takes it off, and the user is asked again at each file. */
    fun setWorkspaceIde(workspace: WorkspaceId, ide: Ide?) {
        editingWorkspaceIde = null
        store.setWorkspaceIde(workspace, ide)
    }

    private fun openFileIn(ide: Ide, file: TerminalFilePosition) {
        val bundleId = installedIdes[ide] ?: return
        platform.openFile(ide, bundleId, file)
    }

    /**
     * The linked working tree (`git worktree add`) of the selected project's repository that the selected terminal's shell is in (its root,
     * from any folder of it), `null` when it is in none: what the palette's editors open then. It reads a few files of git.
     */
    fun selectedWorktree(): String? {
        val project = state.selectedProject ?: return null
        val directory = state.selection.terminal?.let { state.locate(it) }?.terminal?.directory ?: return null
        return GitReader.linkedWorktree(Path.of(project.directory), Path.of(directory))?.toString()
    }

    fun revealProject(id: ProjectId) {
        state.projects.firstOrNull { it.id == id }?.let { platform.reveal(it.directory) }
    }

    /** Asks in which format the history of [terminal], the selected one by default, is exported: nothing for a terminal that has no session. */
    fun exportHistory(terminal: TerminalId? = state.selection.terminal) {
        if (terminal == null || handles[terminal]?.session == null) return
        exportingHistory = terminal
    }

    /**
     * The user chose [format] for the history that waits: where it goes is asked (the folder of the last export, else the Downloads), and it is
     * written there off the UI thread. A file that cannot be written is told.
     */
    fun answerExport(format: ExportFormat, now: LocalDateTime = LocalDateTime.now()) {
        val terminal = exportingHistory ?: return
        exportingHistory = null
        lastExportFormat = format
        val session = handles[terminal]?.session ?: return
        val title = terminalTitle(terminal)
        val chosen = platform.chooseSaveFile("Export the history of “$title”", lastExportFolder ?: downloadsFolder(), exportFileName(title, now, format)) ?: return
        val path = Path.of(chosen)
        lastExportFolder = path.parent?.toString()
        scope.launch {
            val failure = withContext(Dispatchers.IO) {
                runCatching { writeExport(path, exportContent(session.history(format.text), format, title)) }.exceptionOrNull()
            }
            if (failure != null) exportFailure = "$chosen could not be written: ${exportFailureReason(failure)}."
        }
    }

    // What the terminal is called, as its pane says it.
    private fun terminalTitle(terminal: TerminalId): String {
        val handle = handles[terminal]
        val spec = state.locate(terminal)?.terminal
        return terminalIdentity(spec?.name, infos[terminal]?.report, handle?.processName?.value, handle?.shellName ?: "zsh").title
    }

    private fun downloadsFolder(): String {
        val home = System.getProperty("user.home")
        return File(home, "Downloads").takeIf { it.isDirectory }?.path ?: home
    }

    /** A rename of what is gone, or that nothing draws (the board shows no names, and the rail no field), ends: it would keep the keyboard for good. */
    fun dropStaleRenames() {
        val current = state
        val hostShown = current.settings.view == MainView.TERMINALS && (vertical || !current.settings.sidebarCollapsed)
        if (renamingWorkspace != null && (!hostShown || current.projects.none { p -> p.workspaces.any { it.id == renamingWorkspace } })) renamingWorkspace = null
        if (renamingProject != null && (!hostShown || current.projects.none { it.id == renamingProject })) renamingProject = null
        if (renamingTerminal != null && (current.settings.view != MainView.TERMINALS || renamingTerminal?.let(current::locate) == null)) renamingTerminal = null
        // A question about the IDE of a workspace that was closed meanwhile.
        fun gone(id: WorkspaceId) = current.projects.none { p -> p.workspaces.any { it.id == id } }
        choosingIde?.let { if (gone(it.workspace)) choosingIde = null }
        editingWorkspaceIde?.let { if (gone(it)) editingWorkspaceIde = null }
        // The format of the history of a terminal that is gone.
        exportingHistory?.let { if (current.locate(it) == null) exportingHistory = null }
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
            (paletteOpen && shortcut != AppShortcut.PALETTE) || (searchingAll && shortcut != AppShortcut.SEARCH_ALL)
        ) {
            return true
        }
        if (blockedWhileRenaming(shortcut, state.selection.terminal, state.selection.workspace, renamingTerminal, renamingWorkspace)) return true
        val terminal = state.selection.terminal
        when (shortcut) {
            AppShortcut.ADD_PROJECT -> addProject()
            AppShortcut.OPEN_RECENT -> openRecent()
            AppShortcut.NEW_WORKSPACE -> newWorkspace()
            AppShortcut.CLOSE_WORKSPACE -> state.selection.workspace?.let(::closeWorkspace)
            AppShortcut.SPLIT_MAIN -> onTerminal { store.split(it, SplitDirection.ROW) }
            AppShortcut.SPLIT_OTHER -> onTerminal { store.split(it, SplitDirection.COLUMN) }
            AppShortcut.CLOSE_TERMINAL -> onTerminal(::closeTerminal)
            AppShortcut.RESTART_TERMINAL -> onTerminal(::restartTerminal)
            AppShortcut.REOPEN_CLOSED -> reopenClosed()
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
            AppShortcut.FONT_RESET -> fontReset()
            AppShortcut.MENU -> menuOpen = !menuOpen
            AppShortcut.BOARD -> store.updateSettings { it.copy(view = if (it.view == MainView.BOARD) MainView.TERMINALS else MainView.BOARD) }
            // The vertical layout has no sidebar, and the board has its own.
            AppShortcut.SIDEBAR -> if (!vertical && onTerminals) toggleSidebar()
            AppShortcut.PALETTE -> paletteOpen = !paletteOpen
            AppShortcut.PRESENTATION -> togglePresentation()
            AppShortcut.FOCUS_LEFT -> onTerminals { focusNeighbour(Direction.LEFT) }
            AppShortcut.FOCUS_RIGHT -> onTerminals { focusNeighbour(Direction.RIGHT) }
            AppShortcut.FOCUS_UP -> onTerminals { focusNeighbour(Direction.UP) }
            AppShortcut.FOCUS_DOWN -> onTerminals { focusNeighbour(Direction.DOWN) }
            AppShortcut.CLEAR_SCREEN -> onTerminal { handles[it]?.session?.clearScreen() }
            AppShortcut.FIND -> onTerminals { openFind(terminal) }
            AppShortcut.SEARCH_ALL -> if (searchingAll) searchingAll = false else searchAll()
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

    /** Starts presentation mode, with the font of the settings half as big again, or stops it, which gives the terminals the setting's back. */
    fun togglePresentation() {
        if (!presenting) presentationFontSize = presentationFontSize(state.settings.fontSize)
        presenting = !presenting
        presentedKeys.clear()
    }

    // The font shortcuts change the presentation's font while it is on: the setting is what comes back after it.
    private fun fontBy(step: Float) {
        if (presenting) presentationFontSize = (presentationFontSize + step).coerceIn(Settings.MIN_FONT_SIZE, Settings.MAX_FONT_SIZE)
        else store.updateSettings { it.copy(fontSize = it.fontSize + step) }
    }

    private fun fontReset() {
        if (presenting) presentationFontSize = presentationFontSize(state.settings.fontSize)
        else store.updateSettings { it.copy(fontSize = Settings.DEFAULT_FONT_SIZE) }
    }

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
    fun paletteHandlers(worktree: String? = selectedWorktree(), closed: ClosedItem? = store.lastClosed()): PaletteHandlers = PaletteHandlers(
        addProject = ::addProject,
        // What would come back, read when the palette's commands are made.
        reopenClosed = closed?.let { { reopenClosed() } },
        reopenLabel = reopenLabel(closed),
        openRecent = ::openRecent,
        newWorkspace = ::newWorkspace,
        renameWorkspace = ::renameWorkspace,
        closeWorkspace = { state.selection.workspace?.let(::closeWorkspace) },
        splitRight = { onTerminal { store.splitVisually(it, right = true, flipped = vertical) } },
        splitDown = { onTerminal { store.splitVisually(it, right = false, flipped = vertical) } },
        renameTerminal = ::renameTerminal,
        closeTerminal = { onTerminal(::closeTerminal) },
        restartTerminal = { onTerminal(::restartTerminal) },
        find = { onTerminals { openFind(state.selection.terminal) } },
        searchAll = ::searchAll,
        clearScreen = { onTerminal { handles[it]?.session?.clearScreen() } },
        maximize = { onTerminals { maximize() } },
        colorScheme = { state.selection.terminal?.let { picking = SchemeTarget.Terminal(it) } },
        toggleSidebar = ::toggleSidebar,
        showView = { view -> store.updateSettings { it.copy(view = view) } },
        setLayout = { mode -> store.updateSettings { it.copy(layout = mode) } },
        setTheme = { theme -> store.updateSettings { it.copy(theme = theme) } },
        setSecureInput = { secure -> store.updateSettings { it.copy(secureInput = secure) } },
        setCursorShape = { shape -> store.updateSettings { it.copy(cursorShape = shape) } },
        toggleCursorBlink = { store.updateSettings { it.copy(cursorBlink = !it.cursorBlink) } },
        toggleRestoreContents = { store.updateSettings { it.copy(restoreContents = !it.restoreContents) } },
        setOptionKey = { key -> store.updateSettings { it.copy(optionKey = key) } },
        setBell = { mode -> store.updateSettings { it.copy(bell = mode) } },
        toggleCommandMarks = { store.updateSettings { it.copy(commandMarks = !it.commandMarks) } },
        toggleCopyOnSelect = { store.updateSettings { it.copy(copyOnSelect = !it.copyOnSelect) } },
        toggleBoldIsBright = { store.updateSettings { it.copy(boldIsBright = !it.boldIsBright) } },
        setMinimumContrast = { contrast -> store.updateSettings { it.copy(minimumContrast = contrast) } },
        setScrollback = { size -> store.updateSettings { it.copy(scrollback = size) } },
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
        ides = installedIdes.keys.filter { it.forProjects },
        // Read once for the palette that is opened: what it says is what it opens.
        worktree = worktree,
        workspaceIde = if (installedIdes.isEmpty()) null else ({ state.selection.workspace?.let { editingWorkspaceIde = it } }),
        openProjectIn = { ide -> if (worktree != null) openIn(worktree, ide) else state.selection.project?.let { openProjectIn(it, ide) } },
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
        exportHistory = { onTerminal { exportHistory(it) } },
        defaultColorScheme = { dark -> picking = SchemeTarget.Default(dark) },
        toggleLigatures = { store.updateSettings { it.copy(ligatures = !it.ligatures) } },
        refreshUsage = { refreshUsage() },
        toggleCompactUsage = { store.updateSettings { it.copy(compactUsage = !it.compactUsage) } },
        toggleShowUsage = { store.updateSettings { it.copy(showUsage = !it.showUsage) } },
        toggleUsageInWindow = { store.updateSettings { it.copy(usageInWindow = !it.usageInWindow) } },
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
        fontReset = ::fontReset,
        togglePresentation = ::togglePresentation,
        openMenu = { menuOpen = true },
        previousWorkspace = { stepWorkspace(-1) },
        nextWorkspace = { stepWorkspace(1) },
    )
}

/** What the palette calls the command that opens [closed] again. */
internal fun reopenLabel(closed: ClosedItem?): String =
    if (closed?.isWorkspace == true) "Reopen closed workspace “${closed.workspace.name}”" else "Reopen closed terminal"

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
