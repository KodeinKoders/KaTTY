package app.katty.core

import kotlinx.serialization.Serializable

/*
 * The filter of the environments: what the lists of projects, the board and what goes to a workspace (the next one, the palette, the bell, the search)
 * leave out, by the environment of the workspaces. The shells go on running. The selected workspace is never one that is hidden: the selection goes to
 * the nearest one that is shown (see [KattyState.normalized]), and stays where it is when none is.
 */

/**
 * Which environments are shown. It keeps the ones that are **hidden**, so that an environment that appears later is shown.
 *
 * @param hidden The names of the environments that are hidden. One that is no longer found, or that nothing uses, stays in it: it counts when it
 * comes back.
 * @param standardHidden Whether the workspaces that have no environment ("Standard") are hidden.
 */
@Serializable
public data class EnvironmentFilter(val hidden: Set<String> = emptySet(), val standardHidden: Boolean = false) {
    /** Whether something with [environment] (`null` for none) is shown. */
    public fun shows(environment: String?): Boolean = if (environment == null) !standardHidden else environment !in hidden

    /** Whether the workspace is shown: its own environment counts (a workspace that has none has none, whatever its project has). */
    public fun shows(workspace: Workspace): Boolean = shows(workspace.environment)

    /** This filter with [environment] (`null` for Standard) hidden if it was shown, and shown if it was hidden. */
    public fun toggled(environment: String?): EnvironmentFilter = when {
        environment == null -> copy(standardHidden = !standardHidden)
        environment in hidden -> copy(hidden = hidden - environment)
        else -> copy(hidden = hidden + environment)
    }

    /**
     * Whether it hides something of the [listed] environments (the names that the user can check, see [KattyState.environmentNames]): what the
     * button says with its dot. A hidden name that is not listed hides nothing.
     */
    public fun isActive(listed: Set<String>): Boolean = standardHidden || listed.any { it in hidden }

    /** Whether it hides every one of the [listed] environments, and Standard: nothing is shown. */
    public fun hidesAll(listed: Set<String>): Boolean = standardHidden && listed.all { it in hidden }

    /** This filter with [environment] (`null` for Standard) shown, whether it was or not. */
    public fun showing(environment: String?): EnvironmentFilter = if (environment == null) copy(standardHidden = false) else copy(hidden = hidden - environment)

    /** This filter with Standard and every one of the [listed] environments hidden (the ones that were hidden already stay so). */
    public fun hidingAll(listed: Set<String>): EnvironmentFilter = copy(hidden = hidden + listed, standardHidden = true)

    public companion object {
        /** Everything is shown. */
        public val NONE: EnvironmentFilter = EnvironmentFilter()
    }
}

/**
 * The environments that the user can check in the filter: the ones that exist ([available]), and the ones that a project or a workspace has
 * without a file, which can be hidden all the same. Not Standard, which is always there.
 */
public fun KattyState.environmentNames(available: Set<String>): Set<String> =
    available + projects.flatMap { project -> listOfNotNull(project.environment) + project.workspaces.mapNotNull { it.environment } }

/** The workspaces of the project that the [filter] shows, in their order. */
public fun Project.shownWorkspaces(filter: EnvironmentFilter): List<Workspace> = workspaces.filter { filter.shows(it) }

/**
 * The projects that have a workspace that the [filter] shows, in their order: the ones that the lists of projects and the board list. A project
 * that has none is shown by its own environment.
 */
public fun KattyState.shownProjects(filter: EnvironmentFilter): List<Project> =
    projects.filter { project -> if (project.workspaces.isEmpty()) filter.shows(project.environment) else project.workspaces.any { filter.shows(it) } }

/**
 * The workspace that the [filter] shows that is nearest to [from] (the selected one by default) in the order that the sidebar lists them, the projects
 * in their order with their workspaces: the one that is after it when two are as near. `from` itself when it is shown, `null` when none is.
 */
public fun KattyState.nearestShownWorkspace(filter: EnvironmentFilter, from: WorkspaceId? = selection.workspace): WorkspaceId? {
    val all = projects.flatMap { it.workspaces }
    val origin = all.indexOfFirst { it.id == from }
    val shown = all.indices.filter { filter.shows(all[it]) }
    if (shown.isEmpty()) return null
    if (origin < 0) return all[shown.first()].id
    return all[shown.minWith(compareBy<Int> { kotlin.math.abs(it - origin) }.thenByDescending { it })].id
}
