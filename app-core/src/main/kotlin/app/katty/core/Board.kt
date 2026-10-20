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
 * The cards of the board: one for each terminal of the project [filter] (every project for `null`) whose program reports (OSC 7501), in the
 * column of its state, the one that arrived in its column first at the top (the oldest first). Every column is there, empty or not.
 *
 * Whatever the program is: it is not matched against the agents that are known, which only give a logo and a name. A shell, and a program that
 * says nothing, have no card. The tracker keeps a terminal reporting, at rest, after a `done` was seen and taken off, until the shell has the
 * terminal again.
 */
public fun KattyState.board(filter: ProjectId?, infos: Map<TerminalId, AgentInfo>): Map<BoardColumn, List<BoardCard>> {
    val cards = allTerminals()
        .filter { filter == null || it.project.id == filter }
        .mapNotNull { located ->
            val info = infos[located.terminal.id]?.takeIf { it.report.reporting } ?: return@mapNotNull null
            BoardCard(located.terminal, located.project, located.workspace, info)
        }
    val byColumn = cards.groupBy { it.info.state.column() }
    return BoardColumn.entries.associateWith { column -> byColumn[column].orEmpty().sortedBy { it.info.since } }
}
