package app.katty.core

import kotlinx.serialization.Serializable

/** How the application is arranged. */
@Serializable
public enum class LayoutMode {
    /** Vertical when the window is taller than it is wide, horizontal otherwise. */
    AUTO,
    HORIZONTAL,
    VERTICAL,
}

@Serializable
public enum class ThemeMode {
    LIGHT,
    DARK,

    /** The one of the system. */
    SYSTEM,
}

/** What the window shows: the terminals of the workspace, or the board of the agents of the projects. */
@Serializable
public enum class MainView {
    TERMINALS,
    BOARD,
}

/**
 * The settings, all of them in the main menu.
 *
 * @param colorScheme The name of the default color scheme of the terminals (a terminal can have its own).
 * @param fontFamily The family of the terminals' font; `null` for the bundled JetBrains Mono.
 * @param fontSize In pixels.
 * @param shortcuts What the user changed of the shortcuts of the window, by the name of the shortcut and the keys written as the application writes
 * them (`meta+shift:68`, or `none` for a shortcut that has been unbound): only what is not the default, so that a default that changes is followed.
 * @param uiScale How big the text of the application is, as a multiple of its usual size (not the terminals': they have [fontSize]); one of
 * [UI_SCALES].
 * @param usageShown The agents whose usage the sidebar shows, by their ids.
 * @param usageHidden The limits of those agents that are not shown, each made by [usageKey]. It is the hidden ones that are kept, so that a
 * limit that an agent starts to report later is shown.
 */
@Serializable
public data class Settings(
    val layout: LayoutMode = LayoutMode.AUTO,
    val theme: ThemeMode = ThemeMode.LIGHT,
    val colorScheme: String = DEFAULT_COLOR_SCHEME,
    val fontFamily: String? = null,
    val fontSize: Float = DEFAULT_FONT_SIZE,
    val uiScale: Float = DEFAULT_UI_SCALE,
    val shortcuts: Map<String, String> = emptyMap(),
    val ligatures: Boolean = true,
    val usageShown: Set<String> = setOf("claude", "codex", "antigravity", "opencode", "kilocode"),
    val usageHidden: Set<String> = emptySet(),
    val compactUsage: Boolean = false,
    val sidebarCollapsed: Boolean = false,
    val view: MainView = MainView.TERMINALS,
) {
    public companion object {
        public const val DEFAULT_COLOR_SCHEME: String = "Alabaster"
        public const val DEFAULT_FONT_SIZE: Float = 14f
        public const val MIN_FONT_SIZE: Float = 8f
        public const val MAX_FONT_SIZE: Float = 32f
        public const val DEFAULT_UI_SCALE: Float = 1f

        /** The sizes that the text of the application can have, from the smallest: one that is kept in the file and is not one of them is the nearest. */
        public val UI_SCALES: List<Float> = listOf(0.8f, 0.9f, 1f, 1.1f, 1.25f, 1.5f, 1.75f)
    }

    /** [uiScale] as it is used: the one of [UI_SCALES] that is nearest to it, so that a file that was edited by hand cannot make the text unreadable. */
    val effectiveUiScale: Float get() = UI_SCALES.minByOrNull { kotlin.math.abs(it - uiScale) } ?: DEFAULT_UI_SCALE

    /** The scale that is [steps] sizes bigger than [effectiveUiScale] (smaller for a negative number), stopping at the ends of [UI_SCALES]. */
    public fun steppedUiScale(steps: Int): Float {
        val index = UI_SCALES.indexOf(effectiveUiScale)
        return UI_SCALES[(index + steps).coerceIn(UI_SCALES.indices)]
    }
}

/** What [Settings.usageHidden] keeps for the limit that [agent] calls [label] (`Week (Sonnet only)`, `Session`…). */
public fun usageKey(agent: Agent, label: String): String = "${agent.id}/$label"

/**
 * What is selected: one project, one workspace of it and one terminal of that has the focus. A terminal can be maximized over the
 * workspace's area; that is not kept for the next launch.
 */
@Serializable
public data class Selection(
    val project: ProjectId? = null,
    val workspace: WorkspaceId? = null,
    val terminal: TerminalId? = null,
    val maximized: TerminalId? = null,
)

/**
 * Everything that is kept of the application between launches.
 *
 * @param version The version of the format of the file, which grows when a later version of the application changes it.
 */
@Serializable
public data class KattyState(
    val version: Int = VERSION,
    val projects: List<Project> = emptyList(),
    val selection: Selection = Selection(),
    val settings: Settings = Settings(),
) {
    /** The selected project, `null` when there is none. */
    val selectedProject: Project? get() = projects.firstOrNull { it.id == selection.project }

    /** The selected workspace, `null` when there is none. */
    val selectedWorkspace: Workspace? get() = selectedProject?.workspaces?.firstOrNull { it.id == selection.workspace }

    /** Where a terminal is, with its workspace and its project. */
    public data class Location(val project: Project, val workspace: Workspace, val terminal: TerminalSpec)

    /** The place of the terminal [id], `null` if there is none. */
    public fun locate(id: TerminalId): Location? {
        for (project in projects) for (workspace in project.workspaces) {
            workspace.layout.terminals().firstOrNull { it.id == id }?.let { return Location(project, workspace, it) }
        }
        return null
    }

    /** Every terminal, with where it is. */
    public fun allTerminals(): List<Location> =
        projects.flatMap { project -> project.workspaces.flatMap { workspace -> workspace.layout.terminals().map { Location(project, workspace, it) } } }

    /**
     * The workspace that is [delta] places after the selected one (before it for a negative [delta]), in the order that the sidebar, the rail and
     * the tabs list them: the projects in their order, each with its workspaces. It goes from a project to the next, and wraps around at the ends.
     * When the selected project has no workspace, it is the first of the next project (the last of the previous one for a negative [delta]).
     * `null` when there is no workspace at all.
     */
    public fun workspaceStep(delta: Int): WorkspaceId? {
        val all = projects.flatMap { it.workspaces }
        if (all.isEmpty()) return null
        val current = all.indexOfFirst { it.id == selection.workspace }
        if (current >= 0) return all[Math.floorMod(current + delta, all.size)].id
        val project = projects.indexOfFirst { it.id == selection.project }
        return when {
            delta >= 0 -> projects.drop(project + 1).flatMap { it.workspaces }.firstOrNull() ?: all.first()
            project < 0 -> all.last()
            else -> projects.take(project).flatMap { it.workspaces }.lastOrNull() ?: all.last()
        }.id
    }

    /**
     * The project that is [delta] places after the selected one (before it for a negative [delta]), in the order they are listed, wrapping around at the
     * ends. With none selected, the first (the last for a negative [delta]). `null` when there is no project.
     */
    public fun projectStep(delta: Int): ProjectId? {
        if (projects.isEmpty()) return null
        val current = projects.indexOfFirst { it.id == selection.project }
        return if (current < 0) (if (delta >= 0) projects.first() else projects.last()).id else projects[Math.floorMod(current + delta, projects.size)].id
    }

    /** The workspace that the shortcut of [number] (from 1) goes to, in the selected project: the one at that place, and for 9 the last one, as tabs do. */
    public fun workspaceByNumber(number: Int): WorkspaceId? {
        val workspaces = selectedProject?.workspaces.orEmpty()
        return (if (number == LAST_NUMBER) workspaces.lastOrNull() else workspaces.getOrNull(number - 1))?.id
    }

    /** The terminal that has the place [number] (from 1) in the selected workspace, in the order they are drawn. */
    public fun terminalByNumber(number: Int): TerminalId? = selectedWorkspace?.layout?.terminals()?.getOrNull(number - 1)?.id

    /**
     * The state with a selection that points at something: the project, workspace and terminal that are not there are replaced by the
     * first one that is, and a maximized terminal that is not in the selected workspace is put back, and the project keeps its selected workspace
     * ([Project.lastWorkspace]). What was read from a file may not be consistent.
     */
    public fun normalized(): KattyState {
        val project = selectedProject ?: projects.firstOrNull()
        val workspace = project?.workspaces?.firstOrNull { it.id == selection.workspace } ?: project?.workspaces?.firstOrNull()
        val terminals = workspace?.layout?.terminals().orEmpty()
        val terminal = terminals.firstOrNull { it.id == selection.terminal } ?: terminals.firstOrNull()
        val maximized = selection.maximized?.takeIf { id -> terminals.any { it.id == id } }
        val normalized = Selection(project?.id, workspace?.id, terminal?.id, maximized)
        val result = if (normalized == selection) this else copy(selection = normalized)
        // Every change comes through here, so a project always keeps the workspace that is selected in it, however it got selected (chosen, added,
        // found again after the one that was selected was closed): the one that it is selected at again.
        return if (project != null && workspace != null && project.lastWorkspace != workspace.id) {
            result.copy(projects = result.projects.map { if (it.id == project.id) it.copy(lastWorkspace = workspace.id) else it })
        } else {
            result
        }
    }

    public companion object {
        /** The version of the file that this application writes. */
        public const val VERSION: Int = 1

        /** The number of the shortcut that goes to the last workspace, whatever how many there are. */
        public const val LAST_NUMBER: Int = 9
    }
}
