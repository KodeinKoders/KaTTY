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
 * that has been there for the shortest time first. Every column is there, empty or not.
 *
 * A terminal that has said nothing is a shell at rest, since the board shows every terminal.
 */
public fun KattyState.board(filter: ProjectId?, infos: Map<TerminalId, AgentInfo>): Map<BoardColumn, List<BoardCard>> {
    val cards = allTerminals()
        .filter { filter == null || it.project.id == filter }
        .map { BoardCard(it.terminal, it.project, it.workspace, infos[it.terminal.id] ?: AgentInfo(AgentReport(AgentState.IDLE), 0)) }
    val byColumn = cards.groupBy { it.info.state.column() }
    return BoardColumn.entries.associateWith { column -> byColumn[column].orEmpty().sortedByDescending { it.info.since } }
}
