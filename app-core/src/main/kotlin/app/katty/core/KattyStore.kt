package app.katty.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID

/** What closing a terminal did. */
public enum class CloseResult {
    /** There is no such terminal. */
    NOT_FOUND,

    /** The terminal is gone, and its sibling took its space. */
    CLOSED,

    /** It was the last one of its workspace, which is gone with it. */
    WORKSPACE_DELETED,
}

/**
 * The projects, their workspaces and terminals, the selection and the settings: one immutable [KattyState] that only this changes, whatever
 * the thread. A terminal's shell is not here: a session is a runtime object that the application keeps next to it, by [TerminalId].
 *
 * It also keeps what was closed lately, which [reopenClosed] opens again: in memory only, not in the state, which is what is saved.
 *
 * @param newId Makes the id of what is created. Tests give a counter.
 * @param now The time, in milliseconds since the epoch: how long what was closed can be opened again is counted with it. Tests give their own.
 */
public class KattyStore(
    initial: KattyState = KattyState(),
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val flow = MutableStateFlow(initial.normalized())

    // What was closed lately, the newest last: at most [REOPEN_MAX_ITEMS], none older than [REOPEN_KEPT_MS]. Under its own monitor.
    private val closed = ArrayDeque<ClosedItem>()

    /** What the application is, now. */
    public val state: StateFlow<KattyState> get() = flow

    /**
     * Changes the state with [change], and makes the selection point at something again if what it pointed at is gone. [change] works from the
     * state it is given, never from one read before: another thread may have changed it meanwhile (the sessions say where their shells are), and
     * it may be run again when that happens, so it makes no ids.
     */
    private fun edit(change: (KattyState) -> KattyState) {
        flow.update { change(it).normalized() }
    }

    private fun newTerminal(directory: String) = TerminalSpec(TerminalId(newId()), directory = directory)

    // ----- Projects

    /**
     * Adds the folder [directory] as a project, with one workspace named like it and one shell, and selects it. A folder that already is a project
     * is selected instead.
     *
     * @param name The project's name: the folder's by default.
     * @return The project.
     */
    public fun addProject(directory: String, name: String? = null): ProjectId {
        val existing = flow.value.projects.firstOrNull { it.directory == directory }
        if (existing != null) {
            selectProject(existing.id)
            return existing.id
        }
        val id = ProjectId(newId())
        val projectName = name?.takeIf { it.isNotBlank() } ?: directory.trimEnd('/').substringAfterLast('/').ifEmpty { directory }
        // The first workspace is named after its project, which is what it is: the folder as it is.
        val workspace = Workspace(WorkspaceId(newId()), projectName, PaneNode.Leaf(newTerminal(directory)))
        val project = Project(id, projectName, directory, listOf(workspace))
        edit { it.copy(projects = it.projects + project, selection = Selection(id, workspace.id, workspace.layout.terminals().first().id)) }
        return id
    }

    public fun renameProject(id: ProjectId, name: String) {
        if (name.isBlank()) return
        edit { state -> state.copy(projects = state.projects.map { if (it.id == id) it.copy(name = name.trim()) else it }) }
    }

    /** Removes the project with its workspaces and terminals. Its folder stays where it is. */
    public fun removeProject(id: ProjectId) {
        edit { state ->
            val index = state.projects.indexOfFirst { it.id == id }
            if (index < 0) return@edit state
            val projects = state.projects.filter { it.id != id }
            // What was selected after it, else before it.
            val next = if (state.selection.project == id) projects.getOrNull(index) ?: projects.getOrNull(index - 1) else null
            state.copy(projects = projects, selection = if (state.selection.project == id) Selection(next?.id) else state.selection)
        }
    }

    /**
     * Moves the project to [index] in the list of the projects, the other ones keeping their order: the index is the one it has afterwards.
     * The selection does not change.
     */
    public fun moveProject(id: ProjectId, index: Int) {
        edit { state ->
            val from = state.projects.indexOfFirst { it.id == id }
            if (from < 0) return@edit state
            state.copy(projects = state.projects.moved(from, index))
        }
    }

    /** Opens or closes the project in the sidebar: closed, its workspaces are not listed. Nothing else changes. */
    public fun setProjectOpen(id: ProjectId, open: Boolean) {
        edit { state -> state.copy(projects = state.projects.map { if (it.id == id && it.open != open) it.copy(open = open) else it }) }
    }

    /** Opens or closes every project in the sidebar at once. */
    public fun setAllProjectsOpen(open: Boolean) {
        edit { state -> state.copy(projects = state.projects.map { if (it.open != open) it.copy(open = open) else it }) }
    }

    // ----- Workspaces

    /**
     * Adds a workspace to [project], with one shell in the project's folder, and selects it.
     *
     * @param name Its name: the project's when it has no workspace, else "workspace", "workspace 2"… by default.
     * @return The workspace, or `null` if there is no such project.
     */
    public fun addWorkspace(project: ProjectId, name: String? = null): WorkspaceId? {
        val target = flow.value.projects.firstOrNull { it.id == project } ?: return null
        val workspace = Workspace(WorkspaceId(newId()), name?.takeIf { it.isNotBlank() } ?: if (target.workspaces.isEmpty()) target.name else workspaceName(target.workspaces), PaneNode.Leaf(newTerminal(target.directory)))
        edit { state ->
            state.copy(
                // The project is open: the workspace that was just made has to be seen.
                projects = state.projects.map { if (it.id == project) it.copy(workspaces = it.workspaces + workspace, open = true) else it },
                selection = Selection(project, workspace.id, workspace.layout.terminals().first().id),
            )
        }
        return workspace.id
    }

    /** Moves the workspace to [index] among the ones of its project, which is the index it has afterwards. Never to another project. */
    public fun moveWorkspace(id: WorkspaceId, index: Int) {
        edit { state ->
            val project = state.projects.firstOrNull { p -> p.workspaces.any { it.id == id } } ?: return@edit state
            val moved = project.workspaces.moved(project.workspaces.indexOfFirst { it.id == id }, index)
            state.copy(projects = state.projects.map { if (it.id == project.id) it.copy(workspaces = moved) else it })
        }
    }

    public fun renameWorkspace(id: WorkspaceId, name: String) {
        if (name.isBlank()) return
        edit { state -> state.mapWorkspaces { if (it.id == id) it.copy(name = name.trim()) else it } }
    }

    /** Sets the IDE that opens the files whose paths are clicked in the terminals of a workspace, or takes it off with `null`. */
    public fun setWorkspaceIde(id: WorkspaceId, ide: Ide?) {
        edit { state -> state.mapWorkspaces { if (it.id == id) it.copy(ide = ide?.name) else it } }
    }

    /** Closes a workspace, with its terminals: [reopenClosed] opens it again for a while. */
    public fun closeWorkspace(id: WorkspaceId) {
        val at = now()
        var item: ClosedItem? = null
        edit { state ->
            item = state.closedWorkspace(id, at)
            state.closingWorkspace(id)
        }
        item?.let(::remember)
    }

    // What closing the workspace [id] leaves to open again: all of it, at its place in its project.
    private fun KattyState.closedWorkspace(id: WorkspaceId, at: Long): ClosedItem? {
        val project = projects.firstOrNull { p -> p.workspaces.any { it.id == id } } ?: return null
        val index = project.workspaces.indexOfFirst { it.id == id }
        return ClosedItem(project.id, index, project.workspaces[index], place = null, closedAt = at)
    }

    // [this] without the workspace [id], and the one after it, else before it, selected if it was.
    private fun KattyState.closingWorkspace(id: WorkspaceId): KattyState {
        val project = projects.firstOrNull { p -> p.workspaces.any { it.id == id } } ?: return this
        val index = project.workspaces.indexOfFirst { it.id == id }
        val remaining = project.workspaces.filter { it.id != id }
        val projects = projects.map { if (it.id == project.id) it.copy(workspaces = remaining) else it }
        if (selection.workspace != id) return copy(projects = projects)
        // What was selected after it, else before it, in the same project.
        val next = remaining.getOrNull(index) ?: remaining.getOrNull(index - 1)
        return copy(projects = projects, selection = Selection(project.id, next?.id, next?.terminalToFocus()))
    }

    // ----- Terminals

    /**
     * Splits the terminal [id]: its place becomes a split that holds it and a new shell, which starts where the terminal is, and has the
     * focus.
     *
     * @return The new terminal, or `null` if there is no such terminal.
     */
    public fun split(id: TerminalId, direction: SplitDirection): TerminalId? {
        if (flow.value.locate(id) == null) return null
        val createdId = TerminalId(newId())
        val splitId = SplitId(newId())
        var done = false
        edit { state ->
            val location = state.locate(id) ?: return@edit state.also { done = false }
            done = true
            val created = TerminalSpec(createdId, directory = location.terminal.directory)
            val split = PaneNode.Split(splitId, direction, 0.5f, PaneNode.Leaf(location.terminal), PaneNode.Leaf(created))
            state.mapWorkspaces { if (it.id == location.workspace.id) it.copy(layout = it.layout.replaceLeaf(id, split)) else it }
                .copy(selection = Selection(location.project.id, location.workspace.id, createdId))
        }
        return createdId.takeIf { done }
    }

    /**
     * Closes a terminal: the sibling takes its space and has the focus. The last terminal of a workspace takes the workspace with it. [reopenClosed]
     * opens it again for a while, at its place.
     */
    public fun closeTerminal(id: TerminalId): CloseResult {
        var result = CloseResult.NOT_FOUND
        val at = now()
        var item: ClosedItem? = null
        edit { state ->
            item = null
            val location = state.locate(id) ?: return@edit state.also { result = CloseResult.NOT_FOUND }
            val remaining = location.workspace.layout.withoutTerminal(id)
            if (remaining == null) {
                result = CloseResult.WORKSPACE_DELETED
                item = state.closedWorkspace(location.workspace.id, at)
                return@edit state.closingWorkspace(location.workspace.id)
            }
            result = CloseResult.CLOSED
            // Its workspace alone with it, for when the workspace is gone by the time it is opened again.
            item = ClosedItem(
                location.project.id, location.project.workspaces.indexOfFirst { it.id == location.workspace.id },
                location.workspace.copy(layout = PaneNode.Leaf(location.terminal), focused = id), location.workspace.layout.placeOf(id), at,
            )
            val neighbours = location.workspace.layout.terminals()
            val index = neighbours.indexOfFirst { it.id == id }
            val survivors = remaining.terminals()
            // The focus goes to the one that was next to it in the tree: the one before, else the one after.
            val focus = survivors.firstOrNull { it.id == neighbours.getOrNull(index - 1)?.id } ?: survivors.first()
            val selection = if (state.selection.workspace == location.workspace.id) state.selection.copy(terminal = focus.id, maximized = state.selection.maximized.takeIf { it != id }) else state.selection
            state.mapWorkspaces { if (it.id == location.workspace.id) it.copy(layout = remaining) else it }.copy(selection = selection)
        }
        item?.let(::remember)
        return result
    }

    // ----- What was closed

    private fun remember(item: ClosedItem) = synchronized(closed) {
        closed.addLast(item)
        forgetOldClosed()
    }

    // Under the monitor of [closed].
    private fun forgetOldClosed() {
        val oldest = now() - REOPEN_KEPT_MS
        closed.removeAll { it.closedAt < oldest }
        while (closed.size > REOPEN_MAX_ITEMS) closed.removeFirst()
    }

    /** What [reopenClosed] would open: the last thing closed that can come back, `null` when there is none. */
    public fun lastClosed(): ClosedItem? {
        val projects = flow.value.projects.map { it.id }.toSet()
        return synchronized(closed) {
            forgetOldClosed()
            closed.lastOrNull { it.project in projects }
        }
    }

    /** The terminals of what can be opened again: what they showed is worth keeping while they are in it. */
    public fun reopenableTerminals(): Set<TerminalId> {
        val projects = flow.value.projects.map { it.id }.toSet()
        return synchronized(closed) {
            forgetOldClosed()
            closed.filter { it.project in projects }.flatMap { item -> item.terminals.map { it.id } }.toSet()
        }
    }

    /**
     * Opens what was closed last again, and selects it: a terminal at its place in its workspace (see [withTerminalAt]), or in its workspace made again
     * when that is gone too; a workspace at its place in its project. What cannot come back (its project was removed) is forgotten, and what was
     * closed before it comes back instead.
     *
     * @return The terminal that has the focus, `null` when nothing came back.
     */
    public fun reopenClosed(): TerminalId? {
        while (true) {
            val item = synchronized(closed) {
                forgetOldClosed()
                closed.removeLastOrNull()
            } ?: return null
            var focus: TerminalId? = null
            edit { state ->
                val reopened = state.reopening(item)
                focus = reopened?.selection?.terminal
                reopened ?: state
            }
            if (focus != null) return focus
        }
    }

    // [this] with [item] back, and selected; `null` when it cannot come back.
    private fun KattyState.reopening(item: ClosedItem): KattyState? {
        val project = projects.firstOrNull { it.id == item.project } ?: return null
        val terminals = item.terminals
        // Its ids are its own: they cannot be in the state, unless it was put back already.
        val ids = terminals.map { it.id }.toSet()
        if (allTerminals().any { it.terminal.id in ids }) return null
        val existing = project.workspaces.firstOrNull { it.id == item.workspace.id }
        val place = item.place
        val (workspaces, focus) = when {
            existing == null -> {
                val focus = item.workspace.terminalToFocus() ?: return null
                project.workspaces.toMutableList().apply { add(item.index.coerceIn(0, size), item.workspace) } to focus
            }
            // A whole workspace whose id is there again: it cannot be.
            place == null -> return null
            else -> {
                val terminal = terminals.single()
                val back = existing.copy(layout = existing.layout.withTerminalAt(terminal, place), focused = terminal.id)
                project.workspaces.map { if (it.id == existing.id) back else it } to terminal.id
            }
        }
        // Its project is open: what is selected can be seen.
        return copy(
            projects = projects.map { if (it.id == project.id) it.copy(workspaces = workspaces, open = true) else it },
            selection = Selection(project.id, item.workspace.id, focus),
        )
    }

    /** Renames a terminal; a blank [name] goes back to the name of its agent or its shell. */
    public fun renameTerminal(id: TerminalId, name: String?) {
        editTerminal(id) { it.copy(name = name?.trim()?.takeIf(String::isNotEmpty)) }
    }

    /** Gives a terminal its own color scheme, or the default of the settings for `null`. */
    public fun setColorScheme(id: TerminalId, scheme: String?) {
        editTerminal(id) { it.copy(colorScheme = scheme) }
    }

    /** The shell of the terminal is in [directory]: a terminal that is started again starts there. */
    public fun setDirectory(id: TerminalId, directory: String) {
        if (flow.value.locate(id)?.terminal?.directory == directory) return
        editTerminal(id) { it.copy(directory = directory) }
    }

    /** [running] has the terminal over its shell (`null`: the shell does): a terminal that is started again types it in its new shell. */
    public fun setRunning(id: TerminalId, running: RunningCommand?) {
        if (flow.value.locate(id)?.terminal?.running == running) return
        editTerminal(id) { it.copy(running = running) }
    }

    /**
     * Moves the terminal to [workspace]: it is added there, to the right of everything that is in it, and has the focus. If it was the last
     * one of its workspace, that is gone.
     *
     * @return Whether it was moved.
     */
    public fun moveTerminal(id: TerminalId, workspace: WorkspaceId): Boolean {
        val splitId = SplitId(newId())
        var moved = false
        edit { current ->
            moved = false
            val from = current.locate(id) ?: return@edit current
            if (from.workspace.id == workspace) return@edit current
            val target = current.projects.firstNotNullOfOrNull { project -> project.workspaces.firstOrNull { it.id == workspace }?.let { project to it } }
                ?: return@edit current
            moved = true
            val source = from.workspace.layout.withoutTerminal(id)
            val joined = PaneNode.Split(splitId, SplitDirection.ROW, 0.5f, target.second.layout, PaneNode.Leaf(from.terminal))
            current.mapWorkspaces {
                when (it.id) {
                    target.second.id -> it.copy(layout = joined)
                    from.workspace.id -> if (source == null) null else it.copy(layout = source)
                    else -> it
                }
            }.copy(selection = Selection(target.first.id, workspace, id))
        }
        return moved
    }

    private fun editTerminal(id: TerminalId, change: (TerminalSpec) -> TerminalSpec) {
        edit { state -> state.mapWorkspaces { it.copy(layout = it.layout.mapTerminal(id, change)) } }
    }

    /** Sets the share of the first side of a split of [workspace], between 5 % and 95 %. */
    public fun setSplitRatio(workspace: WorkspaceId, split: SplitId, ratio: Float) {
        val clamped = ratio.coerceIn(MIN_RATIO, 1f - MIN_RATIO)
        edit { state -> state.mapWorkspaces { if (it.id == workspace) it.copy(layout = it.layout.withRatio(split, clamped)) else it } }
    }

    // ----- Selection

    public fun selectProject(id: ProjectId) {
        edit { state ->
            val project = state.projects.firstOrNull { it.id == id } ?: return@edit state
            // The project of the selection stays as it is, with its workspace: choosing it must not take the user to its first.
            if (state.selection.project == id && state.selection.workspace != null) return@edit state
            // Another project: the workspace that was selected in it last, or its first.
            val workspace = project.workspaces.firstOrNull { it.id == project.lastWorkspace } ?: project.workspaces.firstOrNull()
            state.copy(selection = Selection(id, workspace?.id, workspace?.terminalToFocus()))
        }
    }

    public fun selectWorkspace(id: WorkspaceId) {
        edit { state ->
            val project = state.projects.firstOrNull { p -> p.workspaces.any { it.id == id } } ?: return@edit state
            if (state.selection.workspace == id) return@edit state
            val workspace = project.workspaces.first { it.id == id }
            // Its project is open, since the workspace that is selected is one that can be seen.
            state.copy(
                projects = state.projects.map { if (it.id == project.id) it.copy(open = true) else it },
                selection = Selection(project.id, id, workspace.terminalToFocus()),
            )
        }
    }

    /** Gives the focus to a terminal, which selects its workspace and its project. */
    public fun focus(id: TerminalId) {
        edit { state ->
            val location = state.locate(id) ?: return@edit state
            val keepsMaximized = state.selection.maximized == id
            state.rememberingFocus(location, id).copy(selection = Selection(location.project.id, location.workspace.id, id, maximized = if (keepsMaximized) id else null))
        }
    }

    /** Maximizes the terminal over the area of its workspace, which focuses it; `null` puts it back. */
    public fun maximize(id: TerminalId?) {
        edit { state ->
            if (id == null) return@edit state.copy(selection = state.selection.copy(maximized = null))
            val location = state.locate(id) ?: return@edit state
            state.rememberingFocus(location, id).copy(selection = Selection(location.project.id, location.workspace.id, id, maximized = id))
        }
    }

    // ----- Settings

    public fun updateSettings(change: (Settings) -> Settings) {
        edit { it.copy(settings = change(it.settings).let { s -> s.copy(fontSize = s.fontSize.coerceIn(Settings.MIN_FONT_SIZE, Settings.MAX_FONT_SIZE)) }) }
    }

    // The workspace of [location] keeps [id] as the terminal that had the focus last.
    private fun KattyState.rememberingFocus(location: KattyState.Location, id: TerminalId): KattyState =
        if (location.workspace.focused == id) this else copy(
            projects = projects.map { project ->
                if (project.id != location.project.id) project
                else project.copy(workspaces = project.workspaces.map { if (it.id == location.workspace.id) it.copy(focused = id) else it })
            },
        )

    private companion object {
        const val MIN_RATIO = MIN_SPLIT_RATIO
    }
}

// The list with the element at [from] taken out and put back so that it is at [to] (kept between 0 and the last index).
private fun <T> List<T>.moved(from: Int, to: Int): List<T> {
    val target = to.coerceIn(0, lastIndex)
    if (from == target) return this
    return toMutableList().also { it.add(target, it.removeAt(from)) }
}

// The next free name of a workspace among [existing]: "workspace", "workspace 2", "workspace 3"…
private fun workspaceName(existing: List<Workspace>): String {
    val names = existing.map { it.name }.toSet()
    if ("workspace" !in names) return "workspace"
    return generateSequence(2) { it + 1 }.map { "workspace $it" }.first { it !in names }
}

// The state with [change] applied to every workspace; a workspace for which it gives `null` is removed.
private fun KattyState.mapWorkspaces(change: (Workspace) -> Workspace?): KattyState =
    copy(projects = projects.map { project -> project.copy(workspaces = project.workspaces.mapNotNull(change)) })
