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
 * @param usageShown The agents whose usage the sidebar shows, by their ids.
 */
@Serializable
public data class Settings(
    val layout: LayoutMode = LayoutMode.AUTO,
    val theme: ThemeMode = ThemeMode.LIGHT,
    val colorScheme: String = DEFAULT_COLOR_SCHEME,
    val fontFamily: String? = null,
    val fontSize: Float = DEFAULT_FONT_SIZE,
    val ligatures: Boolean = true,
    val usageShown: Set<String> = setOf("claude", "codex", "antigravity"),
    val compactUsage: Boolean = false,
    val sidebarCollapsed: Boolean = false,
    val view: MainView = MainView.TERMINALS,
) {
    public companion object {
        public const val DEFAULT_COLOR_SCHEME: String = "Dracula"
        public const val DEFAULT_FONT_SIZE: Float = 12.5f
        public const val MIN_FONT_SIZE: Float = 8f
        public const val MAX_FONT_SIZE: Float = 32f
    }
}

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
     * The state with a selection that points at something: the project, workspace and terminal that are not there are replaced by the
     * first one that is, and a maximized terminal that is not in the selected workspace is put back. What was read from a file may not be
     * consistent.
     */
    public fun normalized(): KattyState {
        val project = selectedProject ?: projects.firstOrNull()
        val workspace = project?.workspaces?.firstOrNull { it.id == selection.workspace } ?: project?.workspaces?.firstOrNull()
        val terminals = workspace?.layout?.terminals().orEmpty()
        val terminal = terminals.firstOrNull { it.id == selection.terminal } ?: terminals.firstOrNull()
        val maximized = selection.maximized?.takeIf { id -> terminals.any { it.id == id } }
        val normalized = Selection(project?.id, workspace?.id, terminal?.id, maximized)
        return if (normalized == selection) this else copy(selection = normalized)
    }

    public companion object {
        /** The version of the file that this application writes. */
        public const val VERSION: Int = 1
    }
}
