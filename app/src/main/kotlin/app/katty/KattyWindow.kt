package app.katty

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.unit.dp
import app.katty.core.usageKey
import app.katty.core.AgentState
import app.katty.core.OPEN_SESSIONS_KEY
import app.katty.core.openSessionsCost
import app.katty.screens.OpenSessionsLine
import app.katty.core.Agent
import app.katty.core.AgentDetection
import app.katty.core.AgentStates
import app.katty.core.AttentionItem
import app.katty.core.KattyDirectories
import app.katty.core.UsageService
import app.katty.core.usageSources
import app.katty.core.KattyStore
import app.katty.core.MainView
import app.katty.core.Orientation
import app.katty.core.orientation
import app.katty.core.Project
import app.katty.core.ProjectId
import app.katty.core.TerminalId
import app.katty.core.TerminalSpec
import app.katty.core.attention
import app.katty.core.terminalIdentity
import app.katty.core.board
import app.katty.core.Ide
import app.katty.core.terminals
import app.katty.screens.AgentBoard
import app.katty.screens.AgentToast
import app.katty.core.RecentProjects
import app.katty.core.RecentProjectsFile
import app.katty.screens.CommandPalette
import app.katty.screens.RecentProjectsPopup
import app.katty.screens.ShortcutsDialog
import app.katty.screens.paletteCommands
import app.katty.screens.AttentionBell
import app.katty.screens.AttentionPopover
import app.katty.screens.BoardSidebar
import app.katty.screens.BoardTabs
import app.katty.screens.CloseLastTerminalDialog
import app.katty.screens.CloseTerminalDialog
import app.katty.screens.DetectedAgents
import app.katty.screens.UsageBarRow
import app.katty.screens.UsageList
import app.katty.screens.UsageSection
import app.katty.screens.UsageVariant
import app.katty.screens.ColorSchemePicker
import app.katty.screens.MainMenu
import app.katty.screens.CloseWorkspaceDialog
import app.katty.screens.RestartTerminalDialog
import app.katty.screens.PasteDialog
import app.katty.screens.QuitDialog
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
import app.katty.terminals.SessionUsageTracker
import app.katty.ui.glyph
import app.katty.terminal.compose.TerminalFont
import app.katty.terminal.themes.ITermColorSchemes
import app.katty.ui.ConfirmDialog
import app.katty.ui.KattyIconButton
import app.katty.ui.KattyIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import app.katty.ui.Katty
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.window.DecoratedWindowScope
import org.jetbrains.jewel.window.TitleBar
import org.jetbrains.jewel.window.TitleBarScope
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
    sessionUsage: SessionUsageTracker,
    directories: KattyDirectories,
    bridge: WindowBridge = WindowBridge(),
    onOpenBoardWindow: () -> Unit = {},
    onOpenAbout: () -> Unit = {},
    keys: WindowKeys = WindowKeys(),
) {
    KattyWindowContent(store, registry, tracker, sessionUsage, directories, bridge, onOpenBoardWindow, onOpenAbout, keys, window) { content ->
        TitleBar { content() }
    }
}

/**
 * The window's content, given the [window] it is in (`null` in a test, where there is none: it is brought to the front and is the parent of
 * the folder chooser) and the [titleBar] that holds what the title bar shows, Jewel's in the window.
 */
@Composable
internal fun KattyWindowContent(
    store: KattyStore,
    registry: SessionRegistry,
    tracker: AgentTracker,
    sessionUsage: SessionUsageTracker,
    directories: KattyDirectories,
    bridge: WindowBridge,
    onOpenBoardWindow: () -> Unit,
    onOpenAbout: () -> Unit,
    keys: WindowKeys,
    window: java.awt.Frame?,
    titleBar: @Composable (content: @Composable TitleBarScope.() -> Unit) -> Unit,
) {
    val state by store.state.collectAsState()
    val settings = state.settings
    val handles by registry.handles.collectAsState()
    // Where each terminal is in git: a workspace says the branch that its terminals share.
    val gits by registry.gitInfos.collectAsState()
    // What each terminal is doing comes from what its program reports (OSC 7501).
    val infos by tracker.infos.collectAsState()
    // What the session of the agent in each terminal has used.
    val usages by sessionUsage.usages.collectAsState()
    // The shape of the window decides the layout when the user left it to Auto.
    val size = LocalWindowInfo.current.containerSize
    val vertical = settings.layout.orientation(size.width.toFloat(), size.height.toFloat()) == Orientation.VERTICAL

    // What the window holds and does: the shortcuts and the palette run its actions. What it is given is what this composition collected.
    val openBoardWindow by rememberUpdatedState(onOpenBoardWindow)
    val openAbout by rememberUpdatedState(onOpenAbout)
    val windowScope = rememberCoroutineScope()
    val controller = remember {
        val platform = object : WindowPlatform {
            override fun chooseFolder(title: String, initial: String?) = chooseFolder(window, title, initial)
            override fun copy(text: String) = copyToClipboard(text)
            override fun readClipboard() = app.katty.readClipboard()
            override fun reveal(directory: String) = revealInFileManager(directory)
            override fun openIn(bundleId: String, directory: String) = openInApplication(bundleId, directory)
            override fun openBoardWindow() = openBoardWindow()
            override fun openAbout() = openAbout()
        }
        WindowController(store, windowScope, platform, registry::restart, tracker::markAllSeen)
    }
    SideEffect {
        controller.handles = handles
        controller.infos = infos
        controller.vertical = vertical
    }
    var renamingWorkspace by controller::renamingWorkspace
    var renamingProject by controller::renamingProject
    var removing by controller::removing
    var renamingTerminal by controller::renamingTerminal
    var findingTerminal by controller::findingTerminal
    var menuOpen by controller::menuOpen
    var attentionOpen by controller::attentionOpen
    var paletteOpen by controller::paletteOpen
    val recentOpen = controller.recentOpen
    // The projects that were opened before: it watches the store, so it is kept for as long as the window is.
    val recent = remember { RecentProjects(store, RecentProjectsFile(directories.recentProjectsFile), windowScope) }
    DisposableEffect(recent) { onDispose { recent.stop() } }
    val openRecent = controller::openRecent
    var editingShortcuts by controller::editingShortcuts
    var focusTick by controller::focusTick
    var boardFilter by controller::boardFilter
    var toast by controller::toast
    var picking by controller::picking
    var closingWorkspace by controller::closingWorkspace
    var closingTerminal by controller::closingTerminal
    var restartingTerminal by controller::restartingTerminal
    val pasting = controller.pasting

    val families by produceState(emptyList<String>()) { value = withContext(Dispatchers.IO) { SystemFonts.monospacedFamilies() } }
    // The font of the terminals: the family that the user chose, or the bundled one when there is none or it cannot be used.
    val font by produceState(TerminalFont.bundled(), settings.fontFamily) {
        value = withContext(Dispatchers.Default) {
            settings.fontFamily?.let { family -> runCatching { TerminalFont.system(family) }.getOrNull() } ?: TerminalFont.bundled()
        }
    }
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
    // The editors that are installed: LaunchServices knows, and is asked again when the window gets the focus, with the agents.
    val installedIdes by produceState(emptyMap<Ide, String>(), detectTick) { value = withContext(Dispatchers.IO) { installedIdes() } }
    SideEffect { controller.installedIdes = installedIdes }
    val ides = installedIdes.keys.toList()
    val usageScope = rememberCoroutineScope()
    val sources = remember(detected) { usageSources(detected, directories.usageDirectory) }
    // Only the agents that KaTTY can read a usage from are offered by the usage panel.
    val detectedAgents = Agent.entries.filter { it in sources }
    val usage = remember(sources) { UsageService(usageScope, sources) }
    DisposableEffect(usage) { onDispose { usage.stop() } }
    SideEffect { controller.refreshUsage = usage::refreshNow }
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
    val openSessions = remember(state.projects, usages) { state.openSessionsCost(usages) }
    val setOpenSessions: (Boolean) -> Unit = { on ->
        store.updateSettings { it.copy(usageHidden = if (on) it.usageHidden - OPEN_SESSIONS_KEY else it.usageHidden + OPEN_SESSIONS_KEY) }
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
    val describe: (TerminalSpec) -> TerminalSummary = { spec ->
        val identity = terminalIdentity(spec.name, infos[spec.id]?.report, handles[spec.id]?.processName?.value, handles[spec.id]?.shellName ?: "shell")
        TerminalSummary(spec.id, identity.title, identity.agent.glyph(), stateOf(spec.id))
    }

    // The sessions follow the terminals of the store, and the ones that are not on the screen are paused.
    // Only when a terminal comes or goes, or its scheme changes: the state changes at every `cd` and every move of a divider.
    val sessionsKey = remember(state.projects, state.settings.colorScheme) {
        state.allTerminals().map { it.terminal.id to (it.terminal.colorScheme ?: state.settings.colorScheme) }
    }
    LaunchedEffect(sessionsKey) { registry.sync(store.state.value) }
    // No terminal is on the screen while the board is.
    val shown = if (settings.view == MainView.TERMINALS) state.selectedWorkspace?.layout?.terminals()?.map { it.id }?.toSet().orEmpty() else emptySet()
    LaunchedEffect(shown, handles) { controller.dropStaleFind() }
    LaunchedEffect(shown, handles.keys) { registry.setVisible(shown) }

    // The actions that the drawing below gives its parts.
    val addProject = controller::addProject
    val newWorkspace = controller::newWorkspace
    val closeWorkspace = controller::closeWorkspace
    val closeTerminal = controller::closeTerminal
    val restartTerminal = controller::restartTerminal
    val openTerminal = controller::openTerminal
    val jumpToWaiting = controller::jumpToWaiting
    val openProjectIn = controller::openProjectIn
    val running = controller::running
    val pasteInto = controller::pasteInto
    val confirmPaste: suspend (String) -> Boolean = controller::confirmPaste
    val selectedSession = controller::selectedSession
    // The vertical layout draws every split turned.
    val flipped = vertical

    // A card that is clicked in the board window: its terminal is selected here, and this window comes to the front.
    LaunchedEffect(bridge) {
        bridge.openTerminal.collect { id ->
            openTerminal(id)
            window?.let {
                it.extendedState = it.extendedState and java.awt.Frame.ICONIFIED.inv()
                it.toFront()
                it.requestFocus()
            }
        }
    }
    // The board window that opened by itself at launch took the focus: it goes back to this one.
    LaunchedEffect(bridge) {
        bridge.focusMain.collect {
            window?.toFront()
            window?.requestFocus()
        }
    }
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

    val overlayOpen = controller.overlayOpen
    LaunchedEffect(overlayOpen) { if (!overlayOpen) focusTick++ }
    // A rename that nothing draws, or of what is gone (closed by a shortcut, a menu, the palette), ends: it would keep the terminals from the keyboard.
    LaunchedEffect(state.projects, renamingTerminal, renamingWorkspace, renamingProject, settings.view, settings.sidebarCollapsed, vertical) {
        controller.dropStaleRenames()
    }
    // Quitting asks this window first, while it is there.
    DisposableEffect(bridge, controller) {
        bridge.confirmQuit = controller::confirmQuit
        onDispose { if (bridge.confirmQuit != null) bridge.confirmQuit = null }
    }
    // The shortcuts of the window, on the window itself: they work whatever has the focus, and when nothing has it.
    SideEffect { keys.handler = { event -> controller.handle(event.appShortcut(), event.shortcutNumber()) } }
    // What the user bound the shortcuts to, for the whole application: what shows a label draws it again when that changes.
    val shortcutMap = remember(settings.shortcuts) { ShortcutMap.of(settings.shortcuts) }
    SideEffect { Shortcuts.map = shortcutMap }

    titleBar {
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
                            onAbout = { menuOpen = false; onOpenAbout() },
                        )
                    }
                }
            },
        )
    }

    Box(Modifier.fillMaxSize().background(Katty.colors.bg)) {
        if (state.projects.isEmpty()) {
            Welcome(onAddProject = addProject, onOpenRecent = openRecent, detected = { DetectedAgents(detected) })
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
                            onReopen = restartTerminal,
                            onColorScheme = { picking = SchemeTarget.Terminal(it) },
                            confirmPaste = confirmPaste,
                            onPaste = pasteInto,
                        ),
                        maximized = state.selection.maximized,
                        font = font,
                        showHeader = header,
                        infos = infos,
                        usages = usages,
                        modifier = areaModifier,
                    )
                }
            }
            // In the panel only while it is shown and not in its own window.
            val usageInPanel = settings.showUsage && !settings.usageInWindow
            val usageSection: @Composable () -> Unit = {
                if (usageInPanel) UsageSection(detectedAgents, readings, settings.usageShown, settings.compactUsage, usageNow, setCompact, setShown, hidden = settings.usageHidden, onHidden = setHidden, refreshing = refreshingUsage, onRefresh = usage::refreshNow, openSessions = openSessions, onOpenSessions = setOpenSessions)
            }
            val usageBar: @Composable () -> Unit = {
                if (usageInPanel) UsageBarRow(detectedAgents, readings, settings.usageShown, settings.compactUsage, usageNow, setCompact, setShown, hidden = settings.usageHidden, onHidden = setHidden, refreshing = refreshingUsage, onRefresh = usage::refreshNow, openSessions = openSessions, onOpenSessions = setOpenSessions)
            }
            val reveal: (ProjectId) -> Unit = controller::revealProject
            if (settings.view == MainView.BOARD) {
                // A project that was removed is not a filter any more.
                val filter = boardFilter?.takeIf { id -> state.projects.any { it.id == id } }
                val columns = remember(state.projects, infos, filter) { state.board(filter, infos) }
                // "All projects" says how many agents there are whatever the filter is.
                val agentsOnBoard = remember(state.projects, infos) { state.board(null, infos).values.sumOf { it.size } }
                val board: @Composable (Modifier) -> Unit = { boardModifier ->
                    AgentBoard(columns, handles, now, vertical, onOpen = openTerminal, usages = usages, modifier = boardModifier, onOpenWindow = onOpenBoardWindow)
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
                        onOpenRecent = openRecent,
                        onRemoveProject = { removing = it },
                        onReveal = reveal,
                        ides = ides,
                        onOpenIn = openProjectIn,
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
                            state, store, onAddProject = addProject, onOpenRecent = openRecent, states = agentStates, gits = gits,
                            usage = {
                                if (usageInPanel) Column(Modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    OpenSessionsLine(openSessions, UsageVariant.RAIL, hidden = settings.usageHidden)
                                    UsageList(detectedAgents.filter { it.id in settings.usageShown }, readings, usageNow, UsageVariant.RAIL, hidden = settings.usageHidden)
                                }
                            },
                        )
                    } else {
                        Sidebar(
                            state = state,
                            store = store,
                            onAddProject = addProject,
                            onOpenRecent = openRecent,
                            onRemoveProject = { removing = it },
                            onCloseWorkspace = closeWorkspace,
                            onReveal = reveal,
                            ides = ides,
                            onOpenIn = openProjectIn,
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

        if (settings.showUsage && settings.usageInWindow) {
            UsageWindow(
                settings.effectiveUiScale, directories.usageWindowFile,
                onClose = { store.updateSettings { it.copy(usageInWindow = false) } },
            ) {
                // As the window is shaped: a wide one has the bar of the vertical layout (the agents side by side), a tall one the list of the sidebar.
                val windowSize = LocalWindowInfo.current.containerSize
                if (windowSize.width > windowSize.height) {
                    Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        UsageBarRow(detectedAgents, readings, settings.usageShown, settings.compactUsage, usageNow, setCompact, setShown, hidden = settings.usageHidden, onHidden = setHidden, refreshing = refreshingUsage, onRefresh = usage::refreshNow, openSessions = openSessions, onOpenSessions = setOpenSessions)
                    }
                } else {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        UsageSection(detectedAgents, readings, settings.usageShown, settings.compactUsage, usageNow, setCompact, setShown, hidden = settings.usageHidden, onHidden = setHidden, refreshing = refreshingUsage, onRefresh = usage::refreshNow, openSessions = openSessions, onOpenSessions = setOpenSessions)
                    }
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
            // Whether something is selected is asked when the palette opens, once: that is when there is a choice to make, and asking takes the lock
            // of the terminal that its output is written under.
            val hasSelection = remember { selectedSession()?.selectedText() != null }
            val commands = remember(state, infos, handles, vertical, shortcutMap) {
                paletteCommands(state, infos, titleOf = { spec -> describe(spec).title }, handlers = controller.paletteHandlers(), vertical = vertical, hasSelection = hasSelection)
            }
            CommandPalette(commands.entries, onRun = { entry -> paletteOpen = false; commands.run(entry) }, onDismiss = { paletteOpen = false })
        }
        if (recentOpen) {
            // The folders that can be opened are asked once, when the list opens.
            val projects = remember { recent.closed(store.state.value) }
            RecentProjectsPopup(projects, onOpen = { controller.openRecentProject(it.directory, it.name) }, onDismiss = { controller.recentOpen = false })
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
                    terminals = workspace.layout.terminals().map { describe(it).copy(running = running(it.id)) },
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
                val summary = describe(location.terminal)
                val confirm = { store.closeTerminal(id); closingTerminal = null }
                val cancel = { closingTerminal = null }
                if (location.workspace.layout.terminals().size == 1) CloseLastTerminalDialog(location.workspace.name, summary, confirm, cancel)
                else CloseTerminalDialog(summary, confirm, cancel)
            }
        }
        restartingTerminal?.let { id ->
            val location = state.locate(id)
            if (location == null) {
                restartingTerminal = null
            } else {
                RestartTerminalDialog(describe(location.terminal), onConfirm = { registry.restart(id); restartingTerminal = null }, onCancel = { restartingTerminal = null })
            }
        }
        removing?.let { id ->
            val project = state.projects.firstOrNull { it.id == id }
            if (project == null) removing = null else RemoveProjectDialog(project, onConfirm = { store.removeProject(id); removing = null }, onCancel = { removing = null })
        }
        pasting?.let { request ->
            PasteDialog(request.text, onConfirm = { request.answer.complete(true) }, onCancel = { request.answer.complete(false) })
        }
        controller.quitting?.let { request ->
            val stopped = controller.stoppedByQuitting().map { location ->
                describe(location.terminal).copy(running = running(location.terminal.id), place = "${location.project.name} / ${location.workspace.name}")
            }
            QuitDialog(stopped, onConfirm = { request.answer.complete(true) }, onCancel = { request.answer.complete(false) })
        }
    }
}

/** How long a notice stays. */
private const val TOAST_MS = 6_000L

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

internal fun revealInFileManager(directory: String) {
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
