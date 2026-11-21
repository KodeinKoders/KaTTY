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
import app.katty.core.AgentDetection
import app.katty.core.AiDiagnosis
import app.katty.core.AiPrompt
import app.katty.core.CommandQuestion
import app.katty.core.CommandRun
import app.katty.core.AiAnswer
import app.katty.core.commandRequestPrompt
import app.katty.core.isTypable
import app.katty.core.Direction
import app.katty.core.EnvironmentChange
import app.katty.core.Environments
import app.katty.core.environmentOf
import app.katty.screens.homeAbbreviated
import app.katty.core.GitChangesDisplay
import app.katty.core.GitReader
import app.katty.core.Ide
import app.katty.core.KattyState
import app.katty.core.KattyStore
import app.katty.core.MainView
import app.katty.core.Project
import app.katty.core.ProjectId
import app.katty.core.RecentProjects
import app.katty.core.Settings
import app.katty.core.SplitDirection
import app.katty.core.TerminalId
import app.katty.core.WorkspaceId
import app.katty.core.attention
import app.katty.core.closeTerminalDecision
import app.katty.core.closeWorkspaceDecision
import app.katty.core.neighbour
import app.katty.core.quitDecision
import app.katty.core.runCommandFor
import app.katty.core.terminalToFocus
import app.katty.core.stoppedByQuitting
import app.katty.core.splitToNudge
import app.katty.core.terminalIdentity
import app.katty.core.terminals
import app.katty.screens.PaletteHandlers
import app.katty.screens.RunCommandState
import app.katty.screens.SettingsPage
import app.katty.screens.splitVisually
import app.katty.terminal.TerminalFilePosition
import app.katty.terminal.TerminalSession
import app.katty.terminals.ExportFormat
import app.katty.terminals.RunTerminal
import app.katty.terminals.RunTerminals
import app.katty.terminals.TerminalHandle
import app.katty.terminals.exportContent
import app.katty.terminals.exportFailureReason
import app.katty.terminals.exportFileName
import app.katty.terminals.writeExport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Path
import app.katty.screens.commandQuestion
import app.katty.terminal.TerminalTextFormat
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

/** A folder to add whose project was removed with its state ([saved]), which the user may restore. */
internal data class RestoreRequest(val directory: String, val saved: Project)

/** The path of a [file] that was clicked in a terminal of [workspace], whose IDE the user chooses. */
internal data class IdeRequest(val workspace: WorkspaceId, val file: TerminalFilePosition)

/**
 * The question of what the run terminal of [workspace] runs, in [directory]: it starts with [initial], the command that was run last, and says that
 * the command that runs will be stopped when [interrupts].
 */
/**
 * The shells of [workspaces] to start again with [environment]. [later]: the change is made already (a project's), and declining leaves the
 * workspaces with the environment they have; otherwise (a workspace's) declining cancels it. [fromSettings]: asked from the settings window, which
 * shows it.
 */
internal data class EnvironmentRestart(
    val workspaces: Set<WorkspaceId>,
    val environment: String?,
    val later: Boolean,
    val fromSettings: Boolean = false,
)

internal data class RunRequest(val workspace: WorkspaceId, val directory: String, val initial: String, val interrupts: Boolean)

/** Where the command that the AI is asked about ran: a terminal, or the run of a workspace. */
internal sealed interface CommandTarget {
    data class Terminal(val id: TerminalId) : CommandTarget

    data class Run(val workspace: WorkspaceId) : CommandTarget
}

/**
 * The question to the AI about the command [run], in [directory]: [question] is why it failed or what it did, and [answer] is `null` while it is
 * asked. [note] says why the correction could not be typed (the shell is not at its prompt any more).
 */
internal data class Diagnosis(
    val target: CommandTarget,
    val question: CommandQuestion,
    val run: CommandRun,
    val directory: String,
    val answer: AiDiagnosis? = null,
    val note: String? = null,
)

/**
 * A command that the AI is asked for in plain words, to be typed in the terminal [terminal], whose shell is [shell] in [directory]: [answer] is the
 * last answer (`null` before the first), [asking] is whether a question is on its way, and [note] says why the command could not be typed.
 */
internal data class CommandRequest(
    val terminal: TerminalId,
    val directory: String,
    val shell: String,
    val answer: AiAnswer? = null,
    val asking: Boolean = false,
    val note: String? = null,
)

/** A run that the user asked for, over the panes or [minimized], which stops the command that runs: they confirm it first. */
internal data class RunLaunch(val workspace: WorkspaceId, val command: String, val directory: String, val minimized: Boolean = false)

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
 * @param runs The run terminals of the workspaces, `null` for none (a test that does not run any).
 */
internal class WindowController(
    private val store: KattyStore,
    private val scope: CoroutineScope,
    private val platform: WindowPlatform,
    private val restart: (TerminalId) -> Unit,
    private val markAllSeen: () -> Unit,
    private val markSeen: (TerminalId) -> Unit = {},
    private val recent: RecentProjects? = null,
    private val runs: RunTerminals? = null,
    // What the environment of a workspace changes for the AI asked from it: read from its files, off the UI thread. Tests give their own.
    private val environmentOf: (WorkspaceId) -> EnvironmentChange = { id ->
        Environments.resolve(store.state.value.environmentOf(id), store.state.value.settings.environmentDirectories).change
    },
    // Starts the shells of terminals again with the environment of their workspace (`SessionRegistry.restartWithEnvironment`).
    private val restartWithEnvironment: (Collection<TerminalId>) -> Unit = {},
) {
    /** A change of environment that starts shells again, waiting for the user's answer (see [answerEnvironmentRestart]). */
    var environmentRestart by mutableStateOf<EnvironmentRestart?>(null)

    /** The environments that exist, which the palette offers. */
    var environmentNames: Set<String> = emptySet()
    var renamingWorkspace by mutableStateOf<WorkspaceId?>(null)
    var renamingProject by mutableStateOf<ProjectId?>(null)
    var renamingTerminal by mutableStateOf<TerminalId?>(null)
    var removing by mutableStateOf<ProjectId?>(null)
    var findingTerminal by mutableStateOf<TerminalId?>(null)

    /** Whether the settings window is open, the page that it shows, and a number that changes when it is asked for while open, to bring it to the front. */
    var settingsOpen by mutableStateOf(false)
    var settingsPage by mutableStateOf(SettingsPage.APPEARANCE)
    var settingsFocusTick by mutableIntStateOf(0)
    var attentionOpen by mutableStateOf(false)
    var paletteOpen by mutableStateOf(false)

    /** The list of the projects that were opened before, which works like the palette. */
    var recentOpen by mutableStateOf(false)

    /** A folder that was chosen to be added, whose project was removed with its state: the user is asked whether it comes back as it was. */
    var restoring by mutableStateOf<RestoreRequest?>(null)

    /** The search of every terminal's history, which works like the palette. */
    var searchingAll by mutableStateOf(false)

    // What the find bar that opens starts with, and a number that changes at each such opening, so that a bar that is open is made again with it:
    // the text that was searched in every terminal, when one of its rows is gone to.
    var findQuery by mutableStateOf("")
    var findRequest by mutableIntStateOf(0)

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

    // What the run terminal of a workspace is asked to run, and a run that stops the command that runs, until the user confirms it.
    var runRequest by mutableStateOf<RunRequest?>(null)
    var confirmingRun by mutableStateOf<RunLaunch?>(null)

    // The run whose command runs, which the user asked to stop: they confirm it first, as for a program in a terminal.
    var stoppingRun by mutableStateOf<WorkspaceId?>(null)

    // The run whose command runs, which the user asked to run again: they confirm that it is stopped first, as for a terminal that is restarted.
    var restartingRun by mutableStateOf<WorkspaceId?>(null)

    // A number that changes when the question of the run is to ask the AI for the command (⌘R while it is open): the question has the field.
    var askRunTick by mutableIntStateOf(0)

    /** The AI command in use (`aiCommand` of the settings and the installed agents), `null` while the AI is off: the window keeps it here. */
    var aiCommand: String? = null

    // Why a command failed, as the AI is asked it, and what asks it.
    var diagnosing by mutableStateOf<Diagnosis?>(null)
    private var diagnosisJob: Job? = null

    // A command asked for in plain words, as the AI is asked for it, and what asks it.
    var requestingCommand by mutableStateOf<CommandRequest?>(null)
    private var requestJob: Job? = null

    // The workspace whose run terminal has its find bar open.
    var findingRun by mutableStateOf<WorkspaceId?>(null)

    /**
     * Presentation mode: the window shows only the terminals, in a font of their own ([presentationFontSize], which the font shortcuts change
     * then, and not the setting), with the keys that are pressed. A mode of the window and not a setting: it is not saved.
     */
    var presenting by mutableStateOf(false)
        private set
    var presentationFontSize by mutableFloatStateOf(Settings.DEFAULT_FONT_SIZE)
        private set

    /**
     * Teach mode: the window shows the keys that are pressed, as presentation mode does, and nothing else of it changes (the layout, the font, the
     * notices and the sounds are as they were). A mode of the window, not saved.
     */
    var teaching by mutableStateOf(false)
        private set

    /** Whether the keys that are pressed are shown: in presentation mode and in teach mode. */
    val showingKeys: Boolean get() = presenting || teaching

    /** The keys that presentation mode and teach mode show. */
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
            quitting != null || recentOpen || restoring != null || choosingIde != null || editingWorkspaceIde != null || exportingHistory != null || exportFailure != null ||
            runRequest != null || confirmingRun != null || stoppingRun != null || restartingRun != null || diagnosing != null || requestingCommand != null ||
            environmentRestart?.fromSettings == false
    }

    /** Whether anything is over the terminals, which then do not take the keyboard. */
    val overlayOpen: Boolean by derivedStateOf {
        blockingDialog || paletteOpen || searchingAll || attentionOpen || renamingWorkspace != null || renamingProject != null ||
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
        // A folder whose project was removed with its state: the user says whether it comes back as it was.
        val saved = recent?.saved(folder)
        if (saved != null && state.projects.none { it.directory == folder }) {
            restoring = RestoreRequest(folder, saved)
            return
        }
        addFreshProject(folder)
    }

    /** The answer to [restoring]: the project as it was, or a new one in its folder; its state is dropped either way. */
    fun answerRestore(restore: Boolean) {
        val request = restoring ?: return
        restoring = null
        if (restore) {
            restoreProject(request.directory, request.saved)
        } else {
            recent?.dropSaved(request.directory)
            addFreshProject(request.directory)
        }
    }

    // The project as it was, then its state dropped: in that order, so that what its terminals showed is never taken for nobody's.
    private fun restoreProject(directory: String, saved: Project) {
        val id = store.restoreProject(saved)
        recent?.dropSaved(directory)
        goToProject(id)
    }

    private fun addFreshProject(folder: String) {
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
        recentOpen = true
    }

    /** Opens the search of every terminal's history, over whatever the window shows. */
    fun searchAll() {
        paletteOpen = false
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

    /** Opens a project of the recent list again, as it was when it was removed when its state is kept, and goes to it. */
    fun openRecentProject(directory: String, name: String) {
        recentOpen = false
        val saved = recent?.saved(directory)
        if (saved != null && state.projects.none { it.directory == directory }) restoreProject(directory, saved) else goToProject(store.addProject(directory, name))
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

    // Closing asks first when a program, or an agent that is active, would be stopped, and does not otherwise: the command of its run is one.
    fun closeWorkspace(id: WorkspaceId) {
        if (state.closeWorkspaceDecision(id, ::stateOf, ::running) == CloseDecision.NOW && runs?.active(id) != true) store.closeWorkspace(id) else closingWorkspace = id
    }

    // ----- Environments

    /**
     * Gives the workspace [id] the environment [environment] (`null` for none): its shells are started again with it, each where it was. Asked first
     * when a program, or an agent that is active, would be stopped.
     */
    fun setWorkspaceEnvironment(id: WorkspaceId, environment: String?) {
        val workspace = state.projects.flatMap { it.workspaces }.firstOrNull { it.id == id } ?: return
        if (workspace.environment == environment) return
        val request = EnvironmentRestart(setOf(id), environment, later = false)
        if (state.closeWorkspaceDecision(id, ::stateOf, ::running) == CloseDecision.NOW) startAgain(request) else environmentRestart = request
    }

    /** Gives the workspace [id] the environment of its project, as [setWorkspaceEnvironment] does. */
    fun useProjectEnvironment(id: WorkspaceId) {
        val project = state.projects.firstOrNull { project -> project.workspaces.any { it.id == id } } ?: return
        setWorkspaceEnvironment(id, project.environment)
    }

    /**
     * Gives the project [id] the environment [environment] (`null` for none), which its new workspaces get. The ones that had its environment are
     * offered to start again with the new one, now or later: later, they keep theirs.
     */
    fun setProjectEnvironment(id: ProjectId, environment: String?) {
        val following = store.setProjectEnvironment(id, environment)
        if (following.isNotEmpty()) environmentRestart = EnvironmentRestart(following, environment, later = true)
    }

    /** Gives the default environment to every project that has none, as the settings' "Apply" does, and offers their workspaces to start again. */
    fun applyDefaultEnvironment() {
        val default = state.settings.defaultEnvironment ?: return
        val following = store.applyEnvironmentToProjectsWithout(default)
        if (following.isNotEmpty()) environmentRestart = EnvironmentRestart(following, default, later = true, fromSettings = true)
    }

    /** The answer to [environmentRestart]: [now] starts the shells again; otherwise nothing changes (for a workspace) or they keep theirs (later). */
    fun answerEnvironmentRestart(now: Boolean) {
        val request = environmentRestart ?: return
        environmentRestart = null
        if (now) startAgain(request)
    }

    private fun startAgain(request: EnvironmentRestart) {
        store.setWorkspacesEnvironment(request.workspaces, request.environment)
        val terminals = state.projects.flatMap { it.workspaces }.filter { it.id in request.workspaces }.flatMap { workspace -> workspace.layout.terminals().map { it.id } }
        restartWithEnvironment(terminals)
    }

    /** Asks for a folder of environments, and adds it after the others: its files are read last, and win. */
    fun addEnvironmentFolder() {
        val folder = platform.chooseFolder("Add environments folder", null) ?: return
        val written = homeAbbreviated(folder)
        store.updateSettings { if (written in it.environmentDirectories) it else it.copy(environmentDirectories = it.environmentDirectories + written) }
    }

    /** Shows the folder of environments [folder] (the first of the settings by default) in the Finder, made first when it is not there yet. */
    fun openEnvironmentsFolder(folder: String? = state.settings.environmentDirectories.firstOrNull()) {
        val path = Environments.expandHome(folder ?: Environments.DEFAULT_DIRECTORY)
        try {
            java.nio.file.Files.createDirectories(Path.of(path))
        } catch (e: java.io.IOException) {
            org.slf4j.LoggerFactory.getLogger("app.katty.WindowController").warn("Could not make {}", path, e)
        }
        platform.reveal(path)
    }

    /** The run terminal of [workspace], when it has one. */
    fun runOf(workspace: WorkspaceId?): RunTerminal? = workspace?.let { runs?.terminals?.value?.get(it) }

    /**
     * Asks what to run in the run terminal of [workspace], which is shown: in the directory of its terminal that is selected, or that would be, with
     * the command that it, else its project, ran last. When a command runs there, the question says that it will be stopped.
     */
    fun openRun(workspace: WorkspaceId, initial: String? = null) {
        runs ?: return
        val current = state
        val target = current.projects.firstNotNullOfOrNull { project -> project.workspaces.firstOrNull { it.id == workspace } } ?: return
        if (current.selection.workspace != workspace || current.settings.view != MainView.TERMINALS) goToWorkspace(workspace)
        val terminal = (if (current.selection.workspace == workspace) current.selection.terminal else null) ?: target.terminalToFocus()
        val directory = target.layout.terminals().firstOrNull { it.id == terminal }?.directory ?: return
        runRequest = RunRequest(workspace, directory, initial ?: current.runCommandFor(workspace), interrupts = runs.active(workspace))
    }

    /**
     * The command that the question was answered with: it runs, over the panes or [minimized] into the header of the workspace, after the confirmation
     * that stopping the command that runs needs.
     */
    fun answerRun(command: String, minimized: Boolean = false) {
        val request = runRequest ?: return
        val trimmed = command.trim()
        if (trimmed.isEmpty()) return
        runRequest = null
        val launch = RunLaunch(request.workspace, trimmed, request.directory, minimized)
        // What runs now, not what the question saw: the command may have ended meanwhile.
        if (runs?.active(request.workspace) == true) confirmingRun = launch else launch(launch)
    }

    /** The run that stops the command that runs is confirmed, or not. */
    fun answerRunConfirmation(confirmed: Boolean) {
        val launch = confirmingRun ?: return
        confirmingRun = null
        if (confirmed) launch(launch)
    }

    private fun launch(launch: RunLaunch) {
        if (findingRun == launch.workspace) findingRun = null
        store.setRunCommand(launch.workspace, launch.command)
        runs?.run(launch.workspace, launch.command, launch.directory, shown = !launch.minimized)
        // The panes keep the keyboard.
        if (launch.minimized) focusTick++
    }

    /** Shows the run of the selected workspace, or minimizes it. */
    fun toggleRun() {
        val workspace = state.selection.workspace ?: return
        val run = runOf(workspace) ?: return
        if (run.shown) minimizeRun(workspace) else runs?.setShown(workspace, true)
    }

    /** Minimizes the run of [workspace]: the panes take the keyboard back. */
    fun minimizeRun(workspace: WorkspaceId) {
        if (runOf(workspace)?.shown != true) return
        runs?.setShown(workspace, false)
        if (findingRun == workspace) findingRun = null
        focusTick++
    }

    /**
     * Stops the run of [workspace] and its command, or closes it when its command has ended: it is gone. A command that runs is asked about first, as
     * closing a terminal where a program runs is.
     */
    fun stopRun(workspace: WorkspaceId) {
        runOf(workspace) ?: return
        if (runs?.active(workspace) == true) stoppingRun = workspace else endRun(workspace)
    }

    /** The question about stopping the command of a run is answered. */
    fun answerStopRun(confirmed: Boolean) {
        val workspace = stoppingRun ?: return
        stoppingRun = null
        if (confirmed) endRun(workspace)
    }

    private fun endRun(workspace: WorkspaceId) {
        runOf(workspace) ?: return
        runs?.stop(workspace)
        if (findingRun == workspace) findingRun = null
        focusTick++
    }

    /**
     * Stops the command of the run of [workspace] and runs it again in the same place. A command that runs is asked about first, as restarting a
     * terminal where a program runs is; one that has ended runs again at once.
     */
    fun restartRun(workspace: WorkspaceId) {
        runOf(workspace) ?: return
        if (runs?.active(workspace) == true) restartingRun = workspace else rerun(workspace)
    }

    /** The question about running the command of a run again, which stops it, is answered. */
    fun answerRestartRun(confirmed: Boolean) {
        val workspace = restartingRun ?: return
        restartingRun = null
        if (confirmed) rerun(workspace)
    }

    private fun rerun(workspace: WorkspaceId) {
        val run = runOf(workspace) ?: return
        if (findingRun == workspace) findingRun = null
        runs?.run(workspace, run.command, run.directory)
    }

    /** The run of the selected workspace, while it is shown over the panes: what the shortcuts of a terminal act on then. */
    fun shownRun(): RunTerminal? = runOf(state.selection.workspace)?.takeIf { it.shown && onTerminals }

    /**
     * What a shortcut does while the run terminal [run] is shown over the panes, `false` for one that has nothing to do with a terminal (it acts as it
     * always does). What acts on a terminal acts on the run (close stops it, restart runs its command again, maximize minimizes it), and what acts on
     * the panes (split, resize, move the focus, rename) does nothing: the panes under it are not selected.
     */
    private fun handleOnRun(shortcut: AppShortcut, run: RunTerminal): Boolean {
        when (shortcut) {
            AppShortcut.CLOSE_TERMINAL -> stopRun(run.workspace)
            AppShortcut.RESTART_TERMINAL -> restartRun(run.workspace)
            AppShortcut.MAXIMIZE, AppShortcut.RUN_TOGGLE -> minimizeRun(run.workspace)
            AppShortcut.CLEAR_SCREEN -> run.session?.clearScreen()
            AppShortcut.FIND -> findingRun = run.workspace
            AppShortcut.FIND_NEXT -> stepRunFind(run, forward = true)
            AppShortcut.FIND_PREVIOUS -> stepRunFind(run, forward = false)
            // The run is no shell: there is no command line to type a command on.
            AppShortcut.ASK_AI_COMMAND -> Unit
            AppShortcut.SPLIT_MAIN, AppShortcut.SPLIT_OTHER, AppShortcut.RESIZE_LEFT, AppShortcut.RESIZE_RIGHT, AppShortcut.RESIZE_UP, AppShortcut.RESIZE_DOWN,
            AppShortcut.FOCUS_LEFT, AppShortcut.FOCUS_RIGHT, AppShortcut.FOCUS_UP, AppShortcut.FOCUS_DOWN, AppShortcut.RENAME_TERMINAL, AppShortcut.PANE_NUMBER,
            -> Unit
            else -> return false
        }
        return true
    }

    // The find shortcuts open the find bar of the run, and step its search once it is open.
    private fun stepRunFind(run: RunTerminal, forward: Boolean) {
        if (findingRun != run.workspace) findingRun = run.workspace else run.session?.search?.let { if (forward) it.next() else it.previous() }
    }

    /** The runs whose command runs, which quitting stops. */
    fun activeRuns(): List<RunTerminal> = runs?.terminals?.value?.values?.filter { !it.ended }.orEmpty()

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

    /** The session of the selected terminal, or of the run that is shown over it: what the commands on the terminal's own keys act on. */
    fun selectedSession(): TerminalSession? {
        shownRun()?.let { return it.session }
        return state.selection.terminal?.let { handles[it]?.session }
    }

    /**
     * Asks the user before a paste that could run commands, whatever pastes it (a terminal's keys and middle click, its menu, the palette): one
     * question at a time, and a new one answers no to the one that was still waiting.
     */
    /**
     * Whether to quit: at once when nothing would be stopped, else when the user says so in the dialog that lists what would be ([stoppedByQuitting]).
     * A second request while the question waits (the close button, then ⌘Q) has the same answer.
     */
    suspend fun confirmQuit(): Boolean {
        if (state.quitDecision(::stateOf, ::running) == CloseDecision.NOW && activeRuns().isEmpty()) return true
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

    /**
     * What the AI can be asked about the last command of [id]: why it failed, or what its result says when it ended otherwise; `null` while the AI is
     * off, or when that command has not ended (or there is none). It takes the session's lock.
     */
    fun lastCommandQuestion(id: TerminalId): CommandQuestion? {
        if (aiCommand == null) return null
        return handles[id]?.session?.lastCommand()?.outcome?.let(::commandQuestion)
    }

    /** Whether the AI can be asked why the command of the run of [workspace] failed: it is on, and the command could not start or ended with an error. */
    fun runFailed(workspace: WorkspaceId): Boolean {
        val run = runOf(workspace) ?: return false
        return aiCommand != null && (run.failure != null || (run.session?.exited?.value ?: 0) != 0)
    }

    /**
     * Asks the AI about the last command of [id], with its line, its exit code and the end of its output: why it failed, or what its result says when
     * it ended otherwise. The dialog opens at once.
     */
    fun explainLastCommand(id: TerminalId) {
        val session = handles[id]?.session ?: return
        val directory = session.workingDirectory.value ?: state.locate(id)?.terminal?.directory ?: return
        scope.launch {
            // Under the session's lock, which the reader writes under: not on the thread that draws.
            val (command, output) = withContext(Dispatchers.IO) { session.lastCommand() to session.lastCommandOutput() }
            val question = command?.outcome?.let(::commandQuestion) ?: return@launch
            ask(CommandTarget.Terminal(id), question, command.toRun(output), directory)
        }
    }

    /**
     * Asks the AI about the command of [id] that the mark [markId] is the bar of: why it failed, for one that failed, else what it did. Its line, its
     * exit code and the end of its output are sent; the dialog opens at once.
     */
    fun explainCommand(id: TerminalId, markId: Long) {
        val session = handles[id]?.session ?: return
        val directory = session.workingDirectory.value ?: state.locate(id)?.terminal?.directory ?: return
        scope.launch {
            val (command, output) = withContext(Dispatchers.IO) { session.command(markId) to session.commandOutput(markId) }
            val question = command?.outcome?.let(::commandQuestion) ?: return@launch
            ask(CommandTarget.Terminal(id), question, command.toRun(output), directory)
        }
    }

    private fun app.katty.terminal.TerminalCommand.toRun(output: String?) = CommandRun(command ?: "(not known)", exitCode, output.orEmpty())

    /** Asks the AI why the command of the run of [workspace] failed: it has no marks, its whole text is its output, of which the end is sent. */
    fun explainRunFailure(workspace: WorkspaceId) {
        val run = runOf(workspace) ?: return
        scope.launch {
            val output = run.failure ?: withContext(Dispatchers.IO) { run.session?.history(TerminalTextFormat.PLAIN)?.text.orEmpty() }
            ask(CommandTarget.Run(workspace), CommandQuestion.WHY_FAILED, CommandRun(run.command, run.session?.exited?.value, output), run.directory)
        }
    }

    /** Asks the AI again, the same question: its last answer failed. */
    fun retryDiagnosis() {
        val diagnosis = diagnosing ?: return
        ask(diagnosis.target, diagnosis.question, diagnosis.run, diagnosis.directory)
    }

    /** Closes the dialog, and forgets an answer that has not come: the agent's process is not killed, it ends by itself or at its time limit. */
    fun closeDiagnosis() {
        diagnosisJob?.cancel()
        diagnosisJob = null
        diagnosing = null
    }

    /**
     * Puts the corrected command where it can be run, never running it: typed on the command line of the terminal, only while its shell waits
     * for one (it would otherwise be the input of the program that runs); in the question of the run, for the run.
     */
    fun typeCorrection() {
        val diagnosis = diagnosing ?: return
        val command = (diagnosis.answer as? AiDiagnosis.Explained)?.command ?: return
        when (val target = diagnosis.target) {
            is CommandTarget.Terminal -> {
                val session = handles[target.id]?.session ?: return
                if (!session.atPrompt()) {
                    diagnosing = diagnosis.copy(note = "The shell is not waiting for a command: copy it instead.")
                    return
                }
                closeDiagnosis()
                session.sendText(command)
                openTerminal(target.id)
            }
            is CommandTarget.Run -> {
                closeDiagnosis()
                openRun(target.workspace, initial = command)
            }
        }
    }

    /** What the environment of the workspace [id] changes, read from its files: for the AI asked from it. Not on the UI thread. */
    fun environmentFor(id: WorkspaceId): EnvironmentChange = environmentOf(id)

    /** Whether the AI can be asked for a command to type in [id]: it is on, and the terminal has a shell. */
    fun canRequestCommand(id: TerminalId): Boolean = aiCommand != null && handles[id]?.session != null

    /**
     * Opens the question that asks the AI for a command, in plain words, to type in [id]: nothing while the AI is off. The command is for the shell of
     * the terminal, in the directory it is in.
     */
    fun openCommandRequest(id: TerminalId) {
        if (!canRequestCommand(id)) return
        val handle = handles[id] ?: return
        val directory = handle.session?.workingDirectory?.value ?: state.locate(id)?.terminal?.directory ?: return
        requestJob?.cancel()
        requestingCommand = CommandRequest(id, directory, handle.shellName)
    }

    /** Asks the AI for a command that does what [request] says; the answer replaces the last one. */
    fun askForCommand(request: String) {
        val asked = requestingCommand ?: return
        val template = aiCommand ?: return
        if (request.isBlank()) return
        requestJob?.cancel()
        val pending = asked.copy(asking = true, note = null)
        requestingCommand = pending
        // The agent of the account of the terminal's workspace.
        val workspace = state.locate(asked.terminal)?.workspace?.id
        requestJob = scope.launch {
            val answer = withContext(Dispatchers.IO) {
                val directory = Path.of(asked.directory)
                val environment = workspace?.let(environmentOf) ?: EnvironmentChange.NONE
                when (val answer = AiPrompt.ask(template, commandRequestPrompt(request, asked.shell, directory), directory, AgentDetection.knownLoginPath, environment = environment)) {
                    is AiAnswer.Command -> if (isTypable(answer.line)) answer else AiAnswer.Failed("it gave a command with control characters")
                    is AiAnswer.Failed -> answer
                }
            }
            if (requestingCommand == pending) requestingCommand = pending.copy(answer = answer, asking = false)
        }
    }

    /** Types [command] on the command line of the terminal, never running it: only while its shell waits for a command. */
    fun typeRequestedCommand(command: String) {
        val request = requestingCommand ?: return
        if (!isTypable(command)) return
        val session = handles[request.terminal]?.session ?: return
        if (!session.atPrompt()) {
            requestingCommand = request.copy(note = "The shell is not waiting for a command: copy it instead.")
            return
        }
        closeCommandRequest()
        session.sendText(command)
        openTerminal(request.terminal)
    }

    /** Closes the question, and forgets an answer that has not come: the agent's process is not killed, it ends by itself or at its time limit. */
    fun closeCommandRequest() {
        requestJob?.cancel()
        requestJob = null
        requestingCommand = null
    }

    // What asks the AI about the command, for the run that is shown or the selected terminal: why it failed ([question] WHY_FAILED), or what the
    // result of the terminal's last command says (EXPLAIN); `null` when that is not the question. Read once, when the palette opens, as what is
    // selected is.
    private fun askAboutCommandHere(question: CommandQuestion): (() -> Unit)? {
        if (!onTerminals) return null
        shownRun()?.let { run ->
            return if (question == CommandQuestion.WHY_FAILED && runFailed(run.workspace)) ({ explainRunFailure(run.workspace) }) else null
        }
        val id = state.selection.terminal ?: return null
        return if (lastCommandQuestion(id) == question) ({ explainLastCommand(id) }) else null
    }

    private fun ask(target: CommandTarget, question: CommandQuestion, run: CommandRun, directory: String) {
        val template = aiCommand ?: return
        diagnosisJob?.cancel()
        val asked = Diagnosis(target, question, run, directory)
        diagnosing = asked
        // The agent of the account of the workspace that the command ran in.
        val workspace = when (target) {
            is CommandTarget.Terminal -> state.locate(target.id)?.workspace?.id
            is CommandTarget.Run -> target.workspace
        }
        diagnosisJob = scope.launch {
            val answer = withContext(Dispatchers.IO) {
                val environment = workspace?.let(environmentOf) ?: EnvironmentChange.NONE
                AiPrompt.askAbout(template, question, run, Path.of(directory), AgentDetection.knownLoginPath, environment = environment)
            }
            if (diagnosing == asked) diagnosing = asked.copy(answer = answer)
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
        // A run of a workspace that is gone.
        runRequest?.let { if (gone(it.workspace)) runRequest = null }
        confirmingRun?.let { if (gone(it.workspace)) confirmingRun = null }
        findingRun?.let { if (gone(it)) findingRun = null }
        stoppingRun?.let { if (gone(it)) stoppingRun = null }
        restartingRun?.let { if (gone(it)) restartingRun = null }
    }

    /**
     * A find bar that is not on the screen any more (another workspace, the board, a terminal closed or started again) is closed: its query was
     * cleared with it, and the find shortcuts would step a search that nobody sees.
     */
    fun dropStaleFind() {
        if (findingTerminal != null && findTarget(findingTerminal, shown(), handles.filterValues { it.session != null }.keys) == null) findingTerminal = null
    }

    /**
     * Opens the settings window on [page] (on the page it showed last when `null`), or brings it to the front when it is open. It is a window of its
     * own, not a dialog: the main window stays usable, and its keys are its own (the shortcuts editor is there).
     */
    fun openSettings(page: SettingsPage? = null) {
        page?.let { settingsPage = it }
        if (settingsOpen) settingsFocusTick++ else settingsOpen = true
    }

    /**
     * What a shortcut of the window does, `true` when it is consumed. While a dialog, the colour picker or a popup is open the others are swallowed,
     * except the one that closes what is open.
     *
     * @param number The digit of a shortcut that takes one (a workspace or a pane by its number).
     */
    fun handle(shortcut: AppShortcut?, number: Int?): Boolean {
        shortcut ?: return false
        // In the question of the run, what opens it asks the AI for the command instead.
        if (shortcut == AppShortcut.RUN && runRequest != null && confirmingRun == null) {
            askRunTick++
            return true
        }
        if (blockingDialog || (attentionOpen && shortcut != AppShortcut.ATTENTION) ||
            (paletteOpen && shortcut != AppShortcut.PALETTE) || (searchingAll && shortcut != AppShortcut.SEARCH_ALL)
        ) {
            return true
        }
        if (blockedWhileRenaming(shortcut, state.selection.terminal, state.selection.workspace, renamingTerminal, renamingWorkspace)) return true
        shownRun()?.let { run -> if (handleOnRun(shortcut, run)) return true }
        val terminal = state.selection.terminal
        when (shortcut) {
            AppShortcut.ADD_PROJECT -> addProject()
            AppShortcut.OPEN_RECENT -> openRecent()
            AppShortcut.NEW_WORKSPACE -> newWorkspace()
            AppShortcut.CLOSE_WORKSPACE -> state.selection.workspace?.let(::closeWorkspace)
            AppShortcut.REMOVE_PROJECT -> state.selection.project?.let { removing = it }
            AppShortcut.SPLIT_MAIN -> onTerminal { store.split(it, SplitDirection.ROW) }
            AppShortcut.SPLIT_OTHER -> onTerminal { store.split(it, SplitDirection.COLUMN) }
            AppShortcut.CLOSE_TERMINAL -> onTerminal(::closeTerminal)
            AppShortcut.RESTART_TERMINAL -> onTerminal(::restartTerminal)
            AppShortcut.REOPEN_CLOSED -> reopenClosed()
            AppShortcut.RUN -> onTerminals { state.selection.workspace?.let(::openRun) }
            AppShortcut.RUN_TOGGLE -> onTerminals { toggleRun() }
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
            AppShortcut.SETTINGS -> openSettings()
            AppShortcut.BOARD -> store.updateSettings { it.copy(view = if (it.view == MainView.BOARD) MainView.TERMINALS else MainView.BOARD) }
            // The vertical layout has no sidebar, and the board has its own.
            AppShortcut.SIDEBAR -> if (!vertical && onTerminals) toggleSidebar()
            AppShortcut.PALETTE -> paletteOpen = !paletteOpen
            AppShortcut.PRESENTATION -> togglePresentation()
            AppShortcut.TEACH -> toggleTeaching()
            AppShortcut.FOCUS_LEFT -> onTerminals { focusNeighbour(Direction.LEFT) }
            AppShortcut.FOCUS_RIGHT -> onTerminals { focusNeighbour(Direction.RIGHT) }
            AppShortcut.FOCUS_UP -> onTerminals { focusNeighbour(Direction.UP) }
            AppShortcut.FOCUS_DOWN -> onTerminals { focusNeighbour(Direction.DOWN) }
            AppShortcut.CLEAR_SCREEN -> onTerminal { handles[it]?.session?.clearScreen() }
            AppShortcut.FIND -> onTerminals { openFind(terminal) }
            AppShortcut.SEARCH_ALL -> if (searchingAll) searchingAll = false else searchAll()
            AppShortcut.ASK_AI_COMMAND -> onTerminal(::openCommandRequest)
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

    /** Starts teach mode, or stops it: the keys that were shown go with it. */
    fun toggleTeaching() {
        teaching = !teaching
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
        environments = environmentNames,
        setProjectEnvironment = { name -> state.selection.project?.let { setProjectEnvironment(it, name) } },
        setWorkspaceEnvironment = { name -> state.selection.workspace?.let { setWorkspaceEnvironment(it, name) } },
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
        run = { onTerminals { state.selection.workspace?.let(::openRun) } },
        runTerminal = runOf(state.selection.workspace)?.let { RunCommandState(shown = it.shown, ended = it.ended) },
        toggleRun = { onTerminals { toggleRun() } },
        stopRun = { shownRun()?.let { stopRun(it.workspace) } },
        restartRun = { shownRun()?.let { restartRun(it.workspace) } },
        find = { onTerminals { shownRun()?.let { findingRun = it.workspace } ?: openFind(state.selection.terminal) } },
        searchAll = ::searchAll,
        clearScreen = { onTerminals { selectedSession()?.clearScreen() } },
        explainFailure = askAboutCommandHere(CommandQuestion.WHY_FAILED),
        explainResult = askAboutCommandHere(CommandQuestion.EXPLAIN),
        askForCommand = state.selection.terminal?.takeIf { onTerminals && shownRun() == null && canRequestCommand(it) }?.let { id -> { openCommandRequest(id) } },
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
        toggleAttentionSound = { store.updateSettings { it.copy(attentionSound = !it.attentionSound) } },
        toggleCommandMarks = { store.updateSettings { it.copy(commandMarks = !it.commandMarks) } },
        toggleGitChanges = {
            store.updateSettings { it.copy(gitChanges = if (it.gitChanges == GitChangesDisplay.DOT) GitChangesDisplay.COUNTS else GitChangesDisplay.DOT) }
        },
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
        findNext = { onTerminals { shownRun()?.let { stepRunFind(it, forward = true) } ?: stepFind(true) } },
        findPrevious = { onTerminals { shownRun()?.let { stepRunFind(it, forward = false) } ?: stepFind(false) } },
        copy = { onTerminals { selectedSession()?.selectedText()?.let(platform::copy) } },
        paste = { onTerminals { selectedSession()?.let { session -> platform.readClipboard()?.let { pasteInto(session, it) } } } },
        selectAll = { onTerminals { selectedSession()?.selectAll() } },
        previousPrompt = { onTerminals { selectedSession()?.scrollToPrompt(-1) } },
        nextPrompt = { onTerminals { selectedSession()?.scrollToPrompt(1) } },
        scrollPageUp = { onTerminals { selectedSession()?.scrollPage(-1) } },
        scrollPageDown = { onTerminals { selectedSession()?.scrollPage(1) } },
        scrollToTop = { onTerminals { selectedSession()?.scrollToTop() } },
        scrollToBottom = { onTerminals { selectedSession()?.scrollToBottom() } },
        editShortcuts = { openSettings(SettingsPage.SHORTCUTS) },
        openAbout = platform::openAbout,
        openAttention = { attentionOpen = true },
        uiBigger = { store.updateSettings { it.copy(uiScale = it.steppedUiScale(1)) } },
        uiSmaller = { store.updateSettings { it.copy(uiScale = it.steppedUiScale(-1)) } },
        uiReset = { store.updateSettings { it.copy(uiScale = Settings.DEFAULT_UI_SCALE) } },
        fontBigger = { fontBy(FONT_STEP) },
        fontSmaller = { fontBy(-FONT_STEP) },
        fontReset = ::fontReset,
        togglePresentation = ::togglePresentation,
        toggleTeaching = ::toggleTeaching,
        editAiCommand = { openSettings(SettingsPage.AGENTS) },
        openSettings = { openSettings() },
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
