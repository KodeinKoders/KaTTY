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
 * @param newId Makes the id of what is created. Tests give a counter.
 */
public class KattyStore(
    initial: KattyState = KattyState(),
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private val flow = MutableStateFlow(initial.normalized())

    /** What the application is, now. */
    public val state: StateFlow<KattyState> get() = flow

    /** Changes the state with [change], and makes the selection point at something again if what it pointed at is gone. */
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

    /** Closes a workspace, with its terminals. */
    public fun closeWorkspace(id: WorkspaceId) {
        edit { state ->
            val project = state.projects.firstOrNull { p -> p.workspaces.any { it.id == id } } ?: return@edit state
            val index = project.workspaces.indexOfFirst { it.id == id }
            val remaining = project.workspaces.filter { it.id != id }
            val projects = state.projects.map { if (it.id == project.id) it.copy(workspaces = remaining) else it }
            if (state.selection.workspace != id) return@edit state.copy(projects = projects)
            // What was selected after it, else before it, in the same project.
            val next = remaining.getOrNull(index) ?: remaining.getOrNull(index - 1)
            state.copy(projects = projects, selection = Selection(project.id, next?.id, next?.layout?.terminals()?.firstOrNull()?.id))
        }
    }

    // ----- Terminals

    /**
     * Splits the terminal [id]: its place becomes a split that holds it and a new shell, which starts where the terminal is, and has the
     * focus.
     *
     * @return The new terminal, or `null` if there is no such terminal.
     */
    public fun split(id: TerminalId, direction: SplitDirection): TerminalId? {
        val location = flow.value.locate(id) ?: return null
        val created = newTerminal(location.terminal.directory)
        val split = PaneNode.Split(SplitId(newId()), direction, 0.5f, PaneNode.Leaf(location.terminal), PaneNode.Leaf(created))
        edit { state ->
            state.mapWorkspaces { if (it.id == location.workspace.id) it.copy(layout = it.layout.replaceLeaf(id, split)) else it }
                .copy(selection = Selection(location.project.id, location.workspace.id, created.id))
        }
        return created.id
    }

    /**
     * Closes a terminal: the sibling takes its space and has the focus. The last terminal of a workspace takes the workspace with it.
     */
    public fun closeTerminal(id: TerminalId): CloseResult {
        val location = flow.value.locate(id) ?: return CloseResult.NOT_FOUND
        val remaining = location.workspace.layout.withoutTerminal(id)
        if (remaining == null) {
            closeWorkspace(location.workspace.id)
            return CloseResult.WORKSPACE_DELETED
        }
        edit { state ->
            val neighbours = location.workspace.layout.terminals()
            val index = neighbours.indexOfFirst { it.id == id }
            val survivors = remaining.terminals()
            // The focus goes to the one that was next to it in the tree: the one before, else the one after.
            val focus = survivors.firstOrNull { it.id == neighbours.getOrNull(index - 1)?.id } ?: survivors.first()
            val selection = if (state.selection.workspace == location.workspace.id) state.selection.copy(terminal = focus.id, maximized = state.selection.maximized.takeIf { it != id }) else state.selection
            state.mapWorkspaces { if (it.id == location.workspace.id) it.copy(layout = remaining) else it }.copy(selection = selection)
        }
        return CloseResult.CLOSED
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
        val state = flow.value
        val from = state.locate(id) ?: return false
        if (from.workspace.id == workspace) return false
        val target = state.projects.firstNotNullOfOrNull { project -> project.workspaces.firstOrNull { it.id == workspace }?.let { project to it } } ?: return false
        edit { current ->
            val source = from.workspace.layout.withoutTerminal(id)
            val joined = PaneNode.Split(SplitId(newId()), SplitDirection.ROW, 0.5f, target.second.layout, PaneNode.Leaf(from.terminal))
            current.mapWorkspaces {
                when (it.id) {
                    target.second.id -> it.copy(layout = joined)
                    from.workspace.id -> if (source == null) null else it.copy(layout = source)
                    else -> it
                }
            }.copy(selection = Selection(target.first.id, workspace, id))
        }
        return true
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
            // Another project: its first workspace.
            val workspace = project.workspaces.firstOrNull()
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
