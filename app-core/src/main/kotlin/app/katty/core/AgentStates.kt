package app.katty.core

/** What each terminal is doing, as the application shows it. */
public interface AgentStates {
    /** The state of the terminal [id]; [AgentState.IDLE] for one that is not known. */
    public fun stateOf(id: TerminalId): AgentState
}

/** Every terminal is at rest: what is shown until there is something to tell. */
public object NoAgentStates : AgentStates {
    override fun stateOf(id: TerminalId): AgentState = AgentState.IDLE
}

/**
 * How many terminals of [project] are in each state, the most urgent state first, without the states that none is in: the counts that
 * a project is shown with.
 */
public fun AgentStates.counts(project: Project): List<Pair<AgentState, Int>> =
    project.workspaces.flatMap { it.layout.terminals() }
        .groupingBy { stateOf(it.id) }.eachCount()
        .toList()
        .sortedByDescending { (state, _) -> state.urgency }

/** The state of each terminal of [workspace], in the order they are drawn: what a workspace is shown with. */
public fun AgentStates.strip(workspace: Workspace): List<AgentState> = workspace.layout.terminals().map { stateOf(it.id) }

/** The state of the project that matters most: the most urgent of its terminals, [AgentState.IDLE] for a project that has none. */
public fun AgentStates.urgent(project: Project): AgentState =
    project.workspaces.flatMap { it.layout.terminals() }.map { stateOf(it.id) }.maxByOrNull { it.urgency } ?: AgentState.IDLE
