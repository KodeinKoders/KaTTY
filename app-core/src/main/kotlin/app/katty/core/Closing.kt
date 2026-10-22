package app.katty.core

/** What closing something needs before it is done. */
public enum class CloseDecision {
    /** Nothing is lost that the user would not expect: close it. */
    NOW,

    /** An agent in it is active: ask first. */
    CONFIRM,
}

/** Whether the agent of a terminal is active: working, or waiting for the user. A shell at its prompt, or an agent that is done, is not. */
public fun AgentState.isActive(): Boolean = this == AgentState.WORKING || this == AgentState.NEEDS_INPUT || this == AgentState.ERROR

/**
 * What closing the workspace [id] needs: asking when a program runs in one of its terminals (it would be stopped, as closing that terminal
 * would) or an agent in it is active, and nothing for shells at their prompts, since closing them loses nothing that the user has not seen.
 *
 * @param stateOf The state of a terminal.
 * @param running Whether a program other than the shell has the terminal.
 */
public fun KattyState.closeWorkspaceDecision(id: WorkspaceId, stateOf: (TerminalId) -> AgentState, running: (TerminalId) -> Boolean): CloseDecision {
    val workspace = projects.firstNotNullOfOrNull { project -> project.workspaces.firstOrNull { it.id == id } } ?: return CloseDecision.NOW
    return if (workspace.layout.terminals().any { running(it.id) || stateOf(it.id).isActive() }) CloseDecision.CONFIRM else CloseDecision.NOW
}

/**
 * What closing the terminal [id] needs: asking when a program runs in it (it would be stopped), and for the last terminal of a workspace
 * whose agent is active, since closing it deletes the workspace. A shell at its prompt closes at once.
 *
 * @param running Whether a program other than the shell has the terminal.
 */
public fun KattyState.closeTerminalDecision(id: TerminalId, stateOf: (TerminalId) -> AgentState, running: (TerminalId) -> Boolean): CloseDecision {
    val location = locate(id) ?: return CloseDecision.NOW
    val alone = location.workspace.layout.terminals().size == 1
    return if (running(id) || (alone && stateOf(id).isActive())) CloseDecision.CONFIRM else CloseDecision.NOW
}

/** Whether closing the project [id] ends an agent that is active: its removal asks in any case, and says it. */
public fun KattyState.activeTerminals(project: ProjectId, stateOf: (TerminalId) -> AgentState): List<TerminalSpec> =
    projects.firstOrNull { it.id == project }?.workspaces.orEmpty().flatMap { it.layout.terminals() }.filter { stateOf(it.id).isActive() }
