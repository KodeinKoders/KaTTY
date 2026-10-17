package app.katty.core

/** The columns of the agent board, in the order they are drawn. */
public enum class BoardColumn(public val title: String) {
    NOTHING("Nothing"),
    WORKING("Working"),
    NEEDS_INPUT("Needs input"),
    DONE("Done"),
}

/** The column that a state is in: a failure is with what needs the user. */
public fun AgentState.column(): BoardColumn = when (this) {
    AgentState.IDLE -> BoardColumn.NOTHING
    AgentState.WORKING -> BoardColumn.WORKING
    AgentState.NEEDS_INPUT, AgentState.ERROR -> BoardColumn.NEEDS_INPUT
    AgentState.DONE -> BoardColumn.DONE
}

/** A terminal on the board, with where it is and what it says. */
public data class BoardCard(val terminal: TerminalSpec, val project: Project, val workspace: Workspace, val info: AgentInfo)

/**
 * The cards of the board: one for each terminal of the project [filter] (every project for `null`), in the column of its state, the one
 * that arrived in its column first at the top (the oldest first; one that never reported has no time and comes first). Every column is there,
 * empty or not.
 *
 * Only the terminals that run one of the known agents are on it: a shell, and a program that is not an agent, have no card. A terminal runs
 * an agent when its report names one (OSC 7501, which the tracker keeps naming the agent at rest after a `done` was seen, until the shell
 * has the terminal again) **or**, for an agent that never reports, when the program that has the terminal is one of them ([processes]).
 * That name is only a fallback: native Claude Code is a file named after its version, `2.1.302`. The "Nothing" column is for an agent at
 * rest, or one that has not reported.
 *
 * @param processes The program that has each terminal (`claude`, or the shell's own name at its prompt).
 */
public fun KattyState.board(filter: ProjectId?, infos: Map<TerminalId, AgentInfo>, processes: Map<TerminalId, String?> = emptyMap()): Map<BoardColumn, List<BoardCard>> {
    val cards = allTerminals()
        .filter { filter == null || it.project.id == filter }
        .mapNotNull { located ->
            val info = infos[located.terminal.id]
            val runsAgent = info?.report?.agent != null || Agent.fromProcess(processes[located.terminal.id]) != null
            // An agent that has never reported has no time: `since` is 0.
            if (runsAgent) BoardCard(located.terminal, located.project, located.workspace, info ?: AgentInfo(AgentReport(AgentState.IDLE), 0)) else null
        }
    val byColumn = cards.groupBy { it.info.state.column() }
    return BoardColumn.entries.associateWith { column -> byColumn[column].orEmpty().sortedBy { it.info.since } }
}
