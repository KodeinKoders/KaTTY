package app.katty

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.unit.dp
import app.katty.core.usageKey
import app.katty.core.AgentState
import app.katty.core.Agent
import app.katty.core.AgentDetection
import app.katty.core.AgentStates
import app.katty.core.AttentionItem
import app.katty.core.CloseDecision
import app.katty.core.Direction
import app.katty.core.KattyDirectories
import app.katty.core.UsageService
import app.katty.core.Settings
import app.katty.core.splitToNudge
import app.katty.core.usageSources
import app.katty.core.KattyStore
import app.katty.core.MainView
import app.katty.core.Orientation
import app.katty.core.orientation
import app.katty.core.Project
import app.katty.core.ProjectId
import app.katty.core.SplitDirection
import app.katty.core.TerminalId
import app.katty.core.TerminalSpec
import app.katty.core.attention
import app.katty.core.terminalIdentity
import app.katty.core.board
import app.katty.core.closeTerminalDecision
import app.katty.core.closeWorkspaceDecision
import app.katty.core.WorkspaceId
import app.katty.core.neighbour
import app.katty.core.terminals
import app.katty.screens.AgentBoard
import app.katty.screens.AgentToast
import app.katty.screens.CommandPalette
import app.katty.screens.ShortcutsDialog
import app.katty.screens.PaletteHandlers
import app.katty.screens.splitVisually
import app.katty.screens.paletteCommands
import app.katty.screens.AttentionBell
import app.katty.screens.AttentionPopover
import app.katty.screens.BoardSidebar
import app.katty.screens.BoardTabs
import app.katty.screens.CloseLastTerminalDialog
import app.katty.screens.DetectedAgents
import app.katty.screens.UsageBarRow
import app.katty.screens.UsageList
import app.katty.screens.UsageSection
import app.katty.screens.UsageVariant
import app.katty.screens.ColorSchemePicker
import app.katty.screens.MainMenu
import app.katty.screens.CloseWorkspaceDialog
import app.katty.screens.PaneActions
import app.katty.screens.ProjectTabs
import app.katty.screens.Rail
import app.katty.screens.WorkspaceTabs
import app.katty.screens.Sidebar
import app.katty.screens.TerminalSummary
import app.katty.screens.destinationsOf
import app.katty.screens.TitleBarContent
import app.katty.screens.Welcome
import app.katty.screens.WorkspaceArea
import app.katty.terminals.AgentTracker
import app.katty.terminals.SessionRegistry
import app.katty.ui.glyph
import app.katty.terminal.compose.TerminalFont
import app.katty.terminal.themes.ITermColorSchemes
import app.katty.ui.ConfirmDialog
import app.katty.ui.KattyIconButton
import app.katty.ui.KattyIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
fun DecoratedWindowScope.KattyWindowContent(
    store: KattyStore,
    registry: SessionRegistry,
    tracker: AgentTracker,
    directories: KattyDirectories,
    bridge: WindowBridge = WindowBridge(),
    onOpenBoardWindow: () -> Unit = {},
    keys: WindowKeys = WindowKeys(),
) {
    val state by store.state.collectAsState()
    val settings = state.settings
    var renamingWorkspace by remember { mutableStateOf<WorkspaceId?>(null) }
    var renamingProject by remember { mutableStateOf<ProjectId?>(null) }
    var removing by remember { mutableStateOf<ProjectId?>(null) }
    var renamingTerminal by remember { mutableStateOf<TerminalId?>(null) }
    var findingTerminal by remember { mutableStateOf<TerminalId?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var attentionOpen by remember { mutableStateOf(false) }
    var paletteOpen by remember { mutableStateOf(false) }
    var editingShortcuts by remember { mutableStateOf(false) }
    // The terminal that is selected takes the keyboard back whenever this changes: when what had it (a palette, a menu, a rename) is gone, and when a
    // terminal is opened, even if it is the one that was selected.
    var focusTick by remember { mutableIntStateOf(0) }
    // The project that the board shows, `null` for every project.
    var boardFilter by remember { mutableStateOf<ProjectId?>(null) }
    var toast by remember { mutableStateOf<AttentionItem?>(null) }
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
    val processNames by registry.processNames.collectAsState()
    // Where each terminal is in git: a workspace says the branch that its terminals share.
    val gits by registry.gitInfos.collectAsState()
    // What each terminal is doing comes from what its program reports (OSC 7501).
    val infos by tracker.infos.collectAsState()
    val agentStates = remember(infos) { object : AgentStates { override fun stateOf(id: TerminalId) = infos[id]?.state ?: AgentState.IDLE } }
    val stateOf: (TerminalId) -> AgentState = agentStates::stateOf
    LaunchedEffect(handles) { tracker.sync(handles) }
    // The agents that are installed: looked for on the PATH of the user's login shell, which is read once, and again when the window gets
    // the focus, so that what is installed meanwhile appears.
    val loginPath by produceState<String?>(null) { value = withContext(Dispatchers.IO) { AgentDetection.loginShellPath() } }
    var detectTick by remember { mutableStateOf(0) }
    val detected by produceState(emptyMap<Agent, java.nio.file.Path>(), loginPath, detectTick) {
        value = withContext(Dispatchers.IO) { AgentDetection.detect(loginPath ?: System.getenv("PATH").orEmpty()) }
    }
    val detectedAgents = Agent.entries.filter { it in detected }
    val usageScope = rememberCoroutineScope()
    val usage = remember(detected) { UsageService(usageScope, usageSources(detected, directories.usageDirectory)) }
    DisposableEffect(usage) { onDispose { usage.stop() } }
    LaunchedEffect(usage, settings.usageShown) { usage.want(detectedAgents.filter { it.id in settings.usageShown }.toSet()) }
    val readings by usage.readings.collectAsState()
    val refreshingUsage by usage.refreshing.collectAsState()
    val setShown: (Agent, Boolean) -> Unit = { agent, on ->
        store.updateSettings { it.copy(usageShown = if (on) it.usageShown + agent.id else it.usageShown - agent.id) }
    }
    val setHidden: (Agent, String, Boolean) -> Unit = { agent, label, hide ->
        val key = usageKey(agent, label)
        store.updateSettings { it.copy(usageHidden = if (hide) it.usageHidden + key else it.usageHidden - key) }
    }
    val setCompact: (Boolean) -> Unit = { on -> store.updateSettings { it.copy(compactUsage = on) } }
    val attention = remember(state.projects, infos) { state.attention(infos) }
    // The time that ages are told from moves on by itself.
    val now = rememberNow()
    val usageNow = java.time.Instant.ofEpochMilli(now)
    // Seen: what a terminal finished or failed at stays until the user has it in front of them, in a window that has the focus.
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    val selectedState = state.selection.terminal?.let { infos[it]?.state }
    LaunchedEffect(windowFocused) { if (windowFocused) detectTick++ }
    LaunchedEffect(selectedState, windowFocused, state.selection.terminal, settings.view) {
        val id = state.selection.terminal
        if (id != null && windowFocused && settings.view == MainView.TERMINALS && (selectedState == AgentState.DONE || selectedState == AgentState.ERROR)) {
            tracker.markSeen(id)
        }
    }
    // The shape of the window decides the layout when the user left it to Auto.
    val size = LocalWindowInfo.current.containerSize
    val vertical = settings.layout.orientation(size.width.toFloat(), size.height.toFloat()) == Orientation.VERTICAL
    val describe: (TerminalSpec) -> TerminalSummary = { spec ->
        val identity = terminalIdentity(spec.name, infos[spec.id]?.report, handles[spec.id]?.processName?.value, handles[spec.id]?.shellName ?: "shell")
        TerminalSummary(spec.id, identity.title, identity.agent.glyph(), stateOf(spec.id))
    }

    // The sessions follow the terminals of the store, and the ones that are not on the screen are paused.
    LaunchedEffect(state.projects, state.settings.colorScheme) { registry.sync(state) }
    // No terminal is on the screen while the board is.
    val shown = if (settings.view == MainView.TERMINALS) state.selectedWorkspace?.layout?.terminals()?.map { it.id }?.toSet().orEmpty() else emptySet()
    LaunchedEffect(shown, handles.keys) { registry.setVisible(shown) }

    // What shows a rename of a workspace is the sidebar (not folded into the rail) or the tabs, over the terminals and not the board: when it is
    // not there, nothing could end the rename, and the terminals would not get the keyboard back.
    val showWorkspaceHost = {
        val current = store.state.value.settings
        if (current.view != MainView.TERMINALS || (!vertical && current.sidebarCollapsed)) {
            store.updateSettings { it.copy(view = MainView.TERMINALS, sidebarCollapsed = if (vertical) it.sidebarCollapsed else false) }
        }
    }
    val showTerminals = {
        if (store.state.value.settings.view != MainView.TERMINALS) store.updateSettings { it.copy(view = MainView.TERMINALS) }
    }
    val addProject = {
        val folder = chooseFolder(window, "Add project folder", state.selectedProject?.directory)
        if (folder != null) {
            val before = state.projects.size
            store.addProject(folder)
            // A project that is new has a workspace with its default name, which opens for editing as a new workspace does.
            if (store.state.value.projects.size > before) {
                showWorkspaceHost()
                renamingWorkspace = store.state.value.selection.workspace
            }
        }
        Unit
    }
    val newWorkspace = {
        state.selection.project?.let { project ->
            showWorkspaceHost()
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
    // Goes to a terminal, whatever the window is showing.
    val openTerminal: (TerminalId) -> Unit = { id ->
        store.updateSettings { it.copy(view = MainView.TERMINALS) }
        store.focus(id)
        // Where it is can be seen.
        state.locate(id)?.project?.id?.let { store.setProjectOpen(it, true) }
        attentionOpen = false
        toast = null
        focusTick++
    }
    // A card that is clicked in the board window: its terminal is selected here, and this window comes to the front.
    LaunchedEffect(bridge) {
        bridge.openTerminal.collect { id ->
            openTerminal(id)
            window.extendedState = window.extendedState and java.awt.Frame.ICONIFIED.inv()
            window.toFront()
            window.requestFocus()
        }
    }
    // The board window that opened by itself at launch took the focus: it goes back to this one.
    LaunchedEffect(bridge) {
        bridge.focusMain.collect {
            window.toFront()
            window.requestFocus()
        }
    }
    // Goes to a workspace, whatever the window is showing, and gives the keyboard to its terminal.
    val goToWorkspace: (WorkspaceId) -> Unit = { id ->
        store.selectWorkspace(id)
        if (store.state.value.settings.view != MainView.TERMINALS) store.updateSettings { it.copy(view = MainView.TERMINALS) }
        focusTick++
    }
    // Goes to a project, at the workspace that was selected in it last, whatever the window is showing.
    val goToProject: (ProjectId) -> Unit = { id ->
        store.selectProject(id)
        store.setProjectOpen(id, true)
        showTerminals()
        focusTick++
    }
    // The state of the store, not the one of the last drawing: keys that repeat come faster than that.
    val stepProject: (Int) -> Unit = { delta -> store.state.value.projectStep(delta)?.let(goToProject) }
    val stepWorkspace: (Int) -> Unit = { delta -> store.state.value.workspaceStep(delta)?.let(goToWorkspace) }
    // The vertical layout draws every split turned.
    val flipped = vertical
    val focusNeighbour = { direction: Direction ->
        val workspace = state.selectedWorkspace
        val from = state.selection.terminal
        if (workspace != null && from != null) workspace.layout.neighbour(from, direction, flipped)?.let(store::focus)
        Unit
    }
    // The session of the selected terminal: what the commands on the terminal's own keys act on.
    val selectedSession = { state.selection.terminal?.let { handles[it]?.session } }
    // The next or the previous match of the search of the terminal that is searched: a terminal that is not opens its search, since there is no match to
    // go to (the shortcut does the same).
    val stepFind: (Boolean) -> Unit = { forward ->
        val searched = findingTerminal
        if (searched == null) {
            findingTerminal = state.selection.terminal
        } else {
            handles[searched]?.session?.search?.let { if (forward) it.next() else it.previous() }
        }
    }
    // The nearest divider of the focused pane that the arrow goes along moves one step: as the screen shows the splits, turned in the vertical layout.
    val resizeSplit: (Direction) -> Unit = { direction ->
        val current = store.state.value
        val workspace = current.selectedWorkspace
        val from = current.selection.terminal
        if (workspace != null && from != null) {
            workspace.layout.splitToNudge(from, direction, SPLIT_STEP, vertical)?.let { (split, ratio) -> store.setSplitRatio(workspace.id, split, ratio) }
        }
    }
    val jumpToWaiting = { attention.nextWaiting(state, state.selection.terminal)?.let(openTerminal); Unit }
    // A terminal that starts needing the user, finishes or fails, in one that the user is not looking at, is told in a notice.
    val windowFocusedNow by rememberUpdatedState(LocalWindowInfo.current.isWindowFocused)
    LaunchedEffect(Unit) {
        tracker.transitions.collect { transition ->
            val current = store.state.value
            val looking = transition.terminal == current.selection.terminal && windowFocusedNow && current.settings.view == MainView.TERMINALS
            if (looking || transition.info.state !in setOf(AgentState.NEEDS_INPUT, AgentState.ERROR, AgentState.DONE)) return@collect
            val location = current.locate(transition.terminal) ?: return@collect
            toast = AttentionItem(location.terminal, location.project, location.workspace, transition.info)
        }
    }
    LaunchedEffect(toast) {
        if (toast != null) {
            delay(TOAST_MS)
            toast = null
        }
    }
    val paletteHandlers = PaletteHandlers(
        addProject = addProject,
        newWorkspace = newWorkspace,
        renameWorkspace = { showWorkspaceHost(); renamingWorkspace = state.selection.workspace },
        closeWorkspace = { state.selection.workspace?.let(closeWorkspace) },
        splitRight = { state.selection.terminal?.let { store.splitVisually(it, right = true, flipped = vertical) } },
        splitDown = { state.selection.terminal?.let { store.splitVisually(it, right = false, flipped = vertical) } },
        renameTerminal = { showTerminals(); renamingTerminal = state.selection.terminal },
        closeTerminal = { state.selection.terminal?.let(closeTerminal) },
        restartTerminal = { state.selection.terminal?.let(registry::restart) },
        find = { findingTerminal = state.selection.terminal },
        clearScreen = { state.selection.terminal?.let { handles[it]?.session?.clearScreen() } },
        maximize = { store.maximize(if (state.selection.maximized == null) state.selection.terminal else null) },
        colorScheme = { state.selection.terminal?.let { picking = SchemeTarget.Terminal(it) } },
        toggleSidebar = { store.updateSettings { it.copy(sidebarCollapsed = !it.sidebarCollapsed) } },
        showView = { view -> store.updateSettings { it.copy(view = view) } },
        setLayout = { mode -> store.updateSettings { it.copy(layout = mode) } },
        setTheme = { theme -> store.updateSettings { it.copy(theme = theme) } },
        jumpToWaiting = jumpToWaiting,
        markAllSeen = tracker::markAllSeen,
        openBoardWindow = onOpenBoardWindow,
        openTerminal = openTerminal,
        openWorkspace = goToWorkspace,
        selectProject = goToProject,
        previousProject = { stepProject(-1) },
        nextProject = { stepProject(1) },
        renameProject = { showWorkspaceHost(); renamingProject = state.selection.project },
        removeProject = { removing = state.selection.project },
        revealProject = { state.selectedProject?.directory?.let(::revealInFileManager) },
        moveProject = { delta ->
            val projects = state.projects
            val from = projects.indexOfFirst { it.id == state.selection.project }
            if (from >= 0) store.moveProject(projects[from].id, (from + delta).coerceIn(projects.indices))
        },
        moveWorkspace = { delta ->
            val workspaces = state.selectedProject?.workspaces.orEmpty()
            val from = workspaces.indexOfFirst { it.id == state.selection.workspace }
            if (from >= 0) store.moveWorkspace(workspaces[from].id, (from + delta).coerceIn(workspaces.indices))
        },
        moveTerminalTo = { workspace -> state.selection.terminal?.let { store.moveTerminal(it, workspace) } },
        useProcessName = { state.selection.terminal?.let { store.renameTerminal(it, null) } },
        copyDirectory = { state.selection.terminal?.let { id -> state.locate(id)?.terminal?.directory?.let(::copyToClipboard) } },
        defaultColorScheme = { picking = SchemeTarget.Default },
        toggleLigatures = { store.updateSettings { it.copy(ligatures = !it.ligatures) } },
        refreshUsage = usage::refreshNow,
        toggleCompactUsage = { setCompact(!settings.compactUsage) },
        focusPane = focusNeighbour,
        resizeSplit = resizeSplit,
        findNext = { stepFind(true) },
        findPrevious = { stepFind(false) },
        copy = { selectedSession()?.selectedText()?.let(::copyToClipboard) },
        paste = { selectedSession()?.let { session -> readClipboard()?.let(session::paste) } },
        selectAll = { selectedSession()?.selectAll() },
        previousPrompt = { selectedSession()?.scrollToPrompt(-1) },
        nextPrompt = { selectedSession()?.scrollToPrompt(1) },
        scrollPageUp = { selectedSession()?.scrollPage(-1) },
        scrollPageDown = { selectedSession()?.scrollPage(1) },
        scrollToTop = { selectedSession()?.scrollToTop() },
        scrollToBottom = { selectedSession()?.scrollToBottom() },
        editShortcuts = { editingShortcuts = true },
        openAttention = { attentionOpen = true },
        uiBigger = { store.updateSettings { it.copy(uiScale = it.steppedUiScale(1)) } },
        uiSmaller = { store.updateSettings { it.copy(uiScale = it.steppedUiScale(-1)) } },
        uiReset = { store.updateSettings { it.copy(uiScale = Settings.DEFAULT_UI_SCALE) } },
        fontBigger = { store.updateSettings { it.copy(fontSize = it.fontSize + FONT_STEP) } },
        fontSmaller = { store.updateSettings { it.copy(fontSize = it.fontSize - FONT_STEP) } },
        fontReset = { store.updateSettings { it.copy(fontSize = Settings.DEFAULT_FONT_SIZE) } },
        openMenu = { menuOpen = true },
        previousWorkspace = { stepWorkspace(-1) },
        nextWorkspace = { stepWorkspace(1) },
    )
    val closeSelectedWorkspace = { state.selection.workspace?.let(closeWorkspace); Unit }

    // What is on top of the window has the keys, and ends in its own way: the palette with its shortcut, a dialog with its buttons.
    val blockingDialog = picking != null || closingWorkspace != null || closingTerminal != null || removing != null
    val overlayOpen = blockingDialog || editingShortcuts || paletteOpen || menuOpen || attentionOpen || renamingWorkspace != null || renamingProject != null || renamingTerminal != null
    LaunchedEffect(overlayOpen) { if (!overlayOpen) focusTick++ }
    // A rename that nothing draws would keep the terminals from the keyboard for good: the board shows no names, and the rail no field.
    LaunchedEffect(renamingWorkspace, renamingProject, settings.view, settings.sidebarCollapsed, vertical) {
        val current = store.state.value.settings
        val shown = current.view == MainView.TERMINALS && (vertical || !current.sidebarCollapsed)
        if (renamingWorkspace != null && !shown) renamingWorkspace = null
        if (renamingProject != null && !shown) renamingProject = null
    }
    LaunchedEffect(renamingTerminal, settings.view) {
        if (renamingTerminal != null && store.state.value.settings.view != MainView.TERMINALS) renamingTerminal = null
    }
    // The shortcuts of the window, on the window itself: they work whatever has the focus, and when nothing has it.
    val shortcutHandler: (KeyEvent) -> Boolean = handler@{ event ->
        // The dialog where they are changed sees every key, the ones that are shortcuts too: they are what it records.
        if (editingShortcuts) return@handler false
        val shortcut = event.appShortcut() ?: return@handler false
        if (blockingDialog || (menuOpen && shortcut != AppShortcut.MENU) || (attentionOpen && shortcut != AppShortcut.ATTENTION) || (paletteOpen && shortcut != AppShortcut.PALETTE)) return@handler true
        val terminal = state.selection.terminal
        // What acts on a terminal is for the terminals, not for the board, which shows none.
        val onTerminals = settings.view == MainView.TERMINALS
        when (shortcut) {
            AppShortcut.ADD_PROJECT -> addProject()
            AppShortcut.NEW_WORKSPACE -> newWorkspace()
            AppShortcut.CLOSE_WORKSPACE -> closeSelectedWorkspace()
            AppShortcut.SPLIT_MAIN -> if (onTerminals) terminal?.let { store.split(it, SplitDirection.ROW) }
            AppShortcut.SPLIT_OTHER -> if (onTerminals) terminal?.let { store.split(it, SplitDirection.COLUMN) }
            AppShortcut.CLOSE_TERMINAL -> if (onTerminals) terminal?.let(closeTerminal)
            AppShortcut.RESTART_TERMINAL -> if (onTerminals) terminal?.let(registry::restart)
            AppShortcut.JUMP_TO_WAITING -> jumpToWaiting()
            AppShortcut.ATTENTION -> attentionOpen = !attentionOpen
            AppShortcut.MAXIMIZE -> if (onTerminals) terminal?.let { store.maximize(if (state.selection.maximized == null) it else null) }
            AppShortcut.RESIZE_LEFT -> if (onTerminals) resizeSplit(Direction.LEFT)
            AppShortcut.RESIZE_RIGHT -> if (onTerminals) resizeSplit(Direction.RIGHT)
            AppShortcut.RESIZE_UP -> if (onTerminals) resizeSplit(Direction.UP)
            AppShortcut.RESIZE_DOWN -> if (onTerminals) resizeSplit(Direction.DOWN)
            AppShortcut.FIND_NEXT -> if (onTerminals) stepFind(true)
            AppShortcut.FIND_PREVIOUS -> if (onTerminals) stepFind(false)
            AppShortcut.FONT_BIGGER -> store.updateSettings { it.copy(fontSize = it.fontSize + FONT_STEP) }
            AppShortcut.FONT_SMALLER -> store.updateSettings { it.copy(fontSize = it.fontSize - FONT_STEP) }
            AppShortcut.FONT_RESET -> store.updateSettings { it.copy(fontSize = Settings.DEFAULT_FONT_SIZE) }
            AppShortcut.MENU -> menuOpen = !menuOpen
            AppShortcut.BOARD -> store.updateSettings { it.copy(view = if (it.view == MainView.BOARD) MainView.TERMINALS else MainView.BOARD) }
            // The vertical layout has no sidebar, and the board has its own.
            AppShortcut.SIDEBAR -> if (!vertical && onTerminals) store.updateSettings { it.copy(sidebarCollapsed = !it.sidebarCollapsed) }
            AppShortcut.PALETTE -> paletteOpen = !paletteOpen
            AppShortcut.FOCUS_LEFT -> if (onTerminals) focusNeighbour(Direction.LEFT)
            AppShortcut.FOCUS_RIGHT -> if (onTerminals) focusNeighbour(Direction.RIGHT)
            AppShortcut.FOCUS_UP -> if (onTerminals) focusNeighbour(Direction.UP)
            AppShortcut.FOCUS_DOWN -> if (onTerminals) focusNeighbour(Direction.DOWN)
            AppShortcut.CLEAR_SCREEN -> if (onTerminals) terminal?.let { handles[it]?.session?.clearScreen() }
            AppShortcut.FIND -> if (onTerminals) findingTerminal = terminal
            AppShortcut.RENAME_TERMINAL -> if (terminal != null) { showTerminals(); renamingTerminal = terminal }
            AppShortcut.RENAME_WORKSPACE -> state.selection.workspace?.let { showWorkspaceHost(); renamingWorkspace = it }
            // The workspaces are listed in a column by the sidebar and in a row by the tabs: the arrows that go along the list are the ones that count.
            AppShortcut.WORKSPACE_UP -> if (!vertical) stepWorkspace(-1)
            AppShortcut.WORKSPACE_DOWN -> if (!vertical) stepWorkspace(1)
            AppShortcut.WORKSPACE_LEFT -> if (vertical) stepWorkspace(-1)
            AppShortcut.WORKSPACE_RIGHT -> if (vertical) stepWorkspace(1)
            AppShortcut.WORKSPACE_PREVIOUS -> stepWorkspace(-1)
            AppShortcut.WORKSPACE_NEXT -> stepWorkspace(1)
            AppShortcut.PROJECT_PREVIOUS -> stepProject(-1)
            AppShortcut.PROJECT_NEXT -> stepProject(1)
            AppShortcut.WORKSPACE_NUMBER -> event.shortcutNumber()?.let { number -> store.state.value.workspaceByNumber(number)?.let(goToWorkspace) }
            AppShortcut.PANE_NUMBER -> event.shortcutNumber()?.let { number -> store.state.value.terminalByNumber(number)?.let(openTerminal) }
        }
        true
    }
    SideEffect { keys.handler = shortcutHandler }
    // What the user bound the shortcuts to, for the whole application: what shows a label draws it again when that changes.
    val shortcutMap = remember(settings.shortcuts) { ShortcutMap.of(settings.shortcuts) }
    SideEffect { Shortcuts.map = shortcutMap }

    TitleBar {
        TitleBarContent(
            state,
            onToggleSidebar = { store.updateSettings { it.copy(sidebarCollapsed = !it.sidebarCollapsed) } },
            showSidebarToggle = !vertical && settings.view == MainView.TERMINALS,
            view = settings.view,
            onView = { view -> store.updateSettings { it.copy(view = view) } },
            boardTitle = state.projects.firstOrNull { it.id == boardFilter }?.name ?: "All projects",
            compactPalette = vertical,
            onPalette = { paletteOpen = !paletteOpen },
            trailing = {
                Box {
                    AttentionBell(attention.count, { attentionOpen = !attentionOpen })
                    if (attentionOpen) {
                        AttentionPopover(
                            attention = attention,
                            now = now,
                            onOpen = openTerminal,
                            onMarkAllSeen = tracker::markAllSeen,
                            onJumpToNext = jumpToWaiting,
                            onDismiss = { attentionOpen = false },
                        )
                    }
                }
                Box {
                    KattyIconButton(KattyIcons.Menu, "Main menu", { menuOpen = !menuOpen }, shortcut = AppShortcut.MENU.label)
                    if (menuOpen) {
                        MainMenu(
                            settings = settings,
                            onChange = store::updateSettings,
                            colorScheme = ITermColorSchemes.entries.firstOrNull { it.name == settings.colorScheme },
                            fontFamilies = families,
                            onPickColorScheme = { menuOpen = false; picking = SchemeTarget.Default },
                            onDismiss = { menuOpen = false },
                            onEditShortcuts = { menuOpen = false; editingShortcuts = true },
                        )
                    }
                }
            },
        )
    }

    Box(Modifier.fillMaxSize().background(Katty.colors.bg)) {
        if (state.projects.isEmpty()) {
            Welcome(onAddProject = addProject, detected = { DetectedAgents(detected) })
        } else {
            val workspace = state.selectedWorkspace
            val area: @Composable (Modifier, Boolean) -> Unit = { areaModifier, header ->
                if (workspace == null) {
                    EmptyProject(areaModifier, onNewWorkspace = newWorkspace)
                } else {
                    WorkspaceArea(
                        workspace = workspace,
                        selectedTerminal = state.selection.terminal,
                        handles = handles,
                        settings = settings,
                        store = store,
                        flipped = flipped,
                        focusTerminals = !overlayOpen,
                        focusTick = focusTick,
                        actions = PaneActions(
                            renaming = renamingTerminal,
                            onRename = { renamingTerminal = it },
                            onRenamed = { id, name ->
                                renamingTerminal = null
                                // A name that is blank is no name: the terminal goes back to its default one only when the user clears it on purpose.
                                if (name != null) store.renameTerminal(id, name)
                            },
                            onClose = closeTerminal,
                            destinations = state.selection.terminal?.let { destinationsOf(state, it) }.orEmpty(),
                            onMove = { id, to -> store.moveTerminal(id, to) },
                            finding = findingTerminal,
                            onFind = { findingTerminal = it },
                            onFindClosed = { findingTerminal = null },
                            onReopen = registry::restart,
                            onColorScheme = { picking = SchemeTarget.Terminal(it) },
                        ),
                        maximized = state.selection.maximized,
                        font = font,
                        showHeader = header,
                        infos = infos,
                        modifier = areaModifier,
                    )
                }
            }
            val usageSection: @Composable () -> Unit = {
                UsageSection(detectedAgents, readings, settings.usageShown, settings.compactUsage, usageNow, setCompact, setShown, hidden = settings.usageHidden, onHidden = setHidden, refreshing = refreshingUsage, onRefresh = usage::refreshNow)
            }
            val usageBar: @Composable () -> Unit = {
                UsageBarRow(detectedAgents, readings, settings.usageShown, settings.compactUsage, usageNow, setCompact, setShown, hidden = settings.usageHidden, onHidden = setHidden, refreshing = refreshingUsage, onRefresh = usage::refreshNow)
            }
            val reveal: (ProjectId) -> Unit = { id -> state.projects.firstOrNull { it.id == id }?.let { revealInFileManager(it.directory) } }
            if (settings.view == MainView.BOARD) {
                // A project that was removed is not a filter any more.
                val filter = boardFilter?.takeIf { id -> state.projects.any { it.id == id } }
                val columns = remember(state.projects, infos, processNames, filter) { state.board(filter, infos, processNames) }
                // "All projects" says how many agents there are whatever the filter is.
                val agentsOnBoard = remember(state.projects, infos, processNames) { state.board(null, infos, processNames).values.sumOf { it.size } }
                val board: @Composable (Modifier) -> Unit = { boardModifier ->
                    AgentBoard(columns, handles, now, vertical, onOpen = openTerminal, modifier = boardModifier, onOpenWindow = onOpenBoardWindow)
                }
                if (vertical) {
                    Column(Modifier.fillMaxSize()) {
                        BoardTabs(state, filter, { boardFilter = it }, states = agentStates)
                        board(Modifier.weight(1f))
                        usageBar()
                    }
                } else {
                    Row(Modifier.fillMaxSize()) {
                        BoardSidebar(state, filter, { boardFilter = it }, states = agentStates, usage = { usageSection() }, agents = agentsOnBoard)
                        board(Modifier.weight(1f))
                    }
                }
            } else if (vertical) {
                Column(Modifier.fillMaxSize()) {
                    ProjectTabs(
                        state, store,
                        onAddProject = addProject,
                        onRemoveProject = { removing = it },
                        onReveal = reveal,
                        renamingProject = renamingProject,
                        onRenamingProject = { renamingProject = it },
                        onNewWorkspace = { project -> renamingWorkspace = store.addWorkspace(project) },
                        states = agentStates,
                    )
                    WorkspaceTabs(
                        project = state.selectedProject,
                        selectedWorkspace = state.selection.workspace,
                        selectedTerminal = state.selection.terminal,
                        store = store,
                        renamingWorkspace = renamingWorkspace,
                        onRenamingWorkspace = { renamingWorkspace = it },
                        onCloseWorkspace = closeWorkspace,
                        onNewWorkspace = newWorkspace,
                        states = agentStates,
                        gits = gits,
                    )
                    area(Modifier.weight(1f).fillMaxWidth(), false)
                    usageBar()
                }
            } else {
                Row(Modifier.fillMaxSize()) {
                    if (settings.sidebarCollapsed) {
                        Rail(
                            state, store, onAddProject = addProject, states = agentStates, gits = gits,
                            usage = { UsageList(detectedAgents.filter { it.id in settings.usageShown }, readings, usageNow, UsageVariant.RAIL, Modifier.padding(vertical = 8.dp), settings.usageHidden) },
                        )
                    } else {
                        Sidebar(
                            state = state,
                            store = store,
                            onAddProject = addProject,
                            onRemoveProject = { removing = it },
                            onCloseWorkspace = closeWorkspace,
                            onReveal = reveal,
                            renamingProject = renamingProject,
                            onRenamingProject = { renamingProject = it },
                            renamingWorkspace = renamingWorkspace,
                            onRenamingWorkspace = { renamingWorkspace = it },
                            states = agentStates,
                            gits = gits,
                            usage = { usageSection() },
                        )
                    }
                    area(Modifier.weight(1f).fillMaxHeight(), true)
                }
            }
        }

        if (editingShortcuts) {
            ShortcutsDialog(
                map = shortcutMap,
                onChange = { changed -> store.updateSettings { it.copy(shortcuts = changed.overrides) } },
                onClose = { editingShortcuts = false },
            )
        }
        if (paletteOpen) {
            val commands = remember(state, infos, handles, vertical, shortcutMap) {
                // Whether something is selected is asked when the palette opens: that is when there is a choice to make.
                paletteCommands(
                    state, infos, titleOf = { spec -> describe(spec).title }, handlers = paletteHandlers, vertical = vertical,
                    hasSelection = selectedSession()?.selectedText() != null,
                )
            }
            CommandPalette(commands.entries, onRun = { entry -> paletteOpen = false; commands.run(entry) }, onDismiss = { paletteOpen = false })
        }
        toast?.let { item ->
            AgentToast(item, now, onClick = { openTerminal(item.terminal.id) }, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp))
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

/** How long a notice stays. */
private const val TOAST_MS = 6_000L

/** How much of a split a press of the resize shortcut moves, and how much the font grows or shrinks for one of the zoom ones. */
private const val SPLIT_STEP = 0.05f
private const val FONT_STEP = 1f

/** Whose color scheme the picker chooses. */
private sealed interface SchemeTarget {
    /** The default of the settings, which the terminals that have no scheme of their own follow. */
    data object Default : SchemeTarget

    data class Terminal(val id: TerminalId) : SchemeTarget
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
