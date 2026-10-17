package app.katty.core

import kotlin.test.Test
import kotlin.test.assertEquals

class BoardTest {
    private var counter = 0
    private val store = KattyStore(newId = { "id${counter++}" })
    private val state get() = store.state.value

    private fun info(state: AgentState, since: Long) = AgentInfo(AgentReport(state, "claude-code"), since)

    @Test
    fun everyColumnIsThereAndTheStatesAreInTheirOwn() {
        store.addProject("/a")
        val a = state.selection.terminal!!
        val b = store.split(a, SplitDirection.ROW)!!
        val c = store.split(b, SplitDirection.ROW)!!
        val d = store.split(c, SplitDirection.ROW)!!
        val e = store.split(d, SplitDirection.ROW)!!

        val board = state.board(
            null,
            mapOf(a to info(AgentState.WORKING, 1), b to info(AgentState.NEEDS_INPUT, 2), c to info(AgentState.ERROR, 3), d to info(AgentState.DONE, 4), e to info(AgentState.IDLE, 5)),
        )

        assertEquals(BoardColumn.entries.toList(), board.keys.toList())
        assertEquals(listOf(e), board.getValue(BoardColumn.NOTHING).map { it.terminal.id })
        assertEquals(listOf(a), board.getValue(BoardColumn.WORKING).map { it.terminal.id })
        assertEquals(listOf(b, c), board.getValue(BoardColumn.NEEDS_INPUT).map { it.terminal.id })
        assertEquals(listOf(d), board.getValue(BoardColumn.DONE).map { it.terminal.id })
    }

    @Test
    fun onlyTheTerminalsThatRunAKnownAgentAreOnTheBoard() {
        store.addProject("/a")
        val shell = state.selection.terminal!!
        val silent = store.split(shell, SplitDirection.ROW)!!
        val editor = store.split(silent, SplitDirection.ROW)!!
        val agent = store.split(editor, SplitDirection.ROW)!!

        val board = state.board(
            null,
            mapOf(
                // A shell that reports nothing, a program that reports but is not an agent, and an agent that reports: only the last has a card.
                shell to AgentInfo(AgentReport(AgentState.IDLE), 0),
                editor to AgentInfo(AgentReport(AgentState.WORKING, "vim"), 0),
                agent to info(AgentState.IDLE, 1),
            ),
        )

        assertEquals(listOf(agent), board.values.flatten().map { it.terminal.id })
        assertEquals(listOf(agent), board.getValue(BoardColumn.NOTHING).map { it.terminal.id })
        assertEquals(emptyList(), state.board(null, emptyMap()).values.flatten())
    }

    @Test
    fun anAgentThatWasSeenIsStillOnTheBoardBecauseItIsStillRunning() {
        store.addProject("/a")
        val claude = state.selection.terminal!!
        val shell = store.split(claude, SplitDirection.ROW)!!
        // What is left of a terminal when its `done` was seen and taken off: at rest, and the record that named the program is gone.
        val afterSeen = AgentInfo(AgentReport(AgentState.IDLE), 5)
        val infos = mapOf(claude to afterSeen, shell to AgentInfo(AgentReport(AgentState.IDLE), 6))

        val board = state.board(null, infos, mapOf(claude to "claude", shell to "zsh"))

        assertEquals(listOf(claude), board.values.flatten().map { it.terminal.id })
        assertEquals(listOf(claude), board.getValue(BoardColumn.NOTHING).map { it.terminal.id })
        // Without the program, nothing says that it is an agent: the cause of the card that went.
        assertEquals(emptyList(), state.board(null, infos).values.flatten())
    }

    @Test
    fun anAgentThatNeverReportedIsOnTheBoardByItsProgramAndOtherProgramsAreNot() {
        store.addProject("/a")
        val codex = state.selection.terminal!!
        val editor = store.split(codex, SplitDirection.ROW)!!
        val named = store.split(editor, SplitDirection.ROW)!!

        val board = state.board(null, mapOf(named to info(AgentState.WORKING, 9)), mapOf(codex to "codex", editor to "vim", named to "zsh"))

        assertEquals(listOf(codex), board.getValue(BoardColumn.NOTHING).map { it.terminal.id })
        // It has no time: it never said anything.
        assertEquals(0L, board.getValue(BoardColumn.NOTHING).single().info.since)
        // What an agent reported counts whatever the program is now.
        assertEquals(listOf(named), board.getValue(BoardColumn.WORKING).map { it.terminal.id })
        assertEquals(setOf(codex, named), board.values.flatten().map { it.terminal.id }.toSet())
    }

    @Test
    fun theOneThatArrivedInAColumnFirstComesFirst() {
        store.addProject("/a")
        val a = state.selection.terminal!!
        val b = store.split(a, SplitDirection.ROW)!!
        val c = store.split(b, SplitDirection.ROW)!!

        val board = state.board(null, mapOf(a to info(AgentState.WORKING, 100), b to info(AgentState.WORKING, 300), c to info(AgentState.WORKING, 200)))

        assertEquals(listOf(a, c, b), board.getValue(BoardColumn.WORKING).map { it.terminal.id })
    }

    @Test
    fun equalTimesKeepTheOrderOfTheTreeAndAnAgentThatNeverReportedComesFirst() {
        store.addProject("/a")
        val a = state.selection.terminal!!
        val b = store.split(a, SplitDirection.ROW)!!
        val c = store.split(b, SplitDirection.ROW)!!

        val board = state.board(null, mapOf(a to info(AgentState.IDLE, 50), b to info(AgentState.IDLE, 50)), mapOf(c to "claude"))

        // `c` has no report and so no time: it is the oldest. `a` and `b` arrived together and stay as the tree has them.
        assertEquals(listOf(c, a, b), board.getValue(BoardColumn.NOTHING).map { it.terminal.id })
    }

    @Test
    fun theFilterKeepsTheTerminalsOfOneProject() {
        val first = store.addProject("/a")
        val inFirst = state.selection.terminal!!
        store.addProject("/b")
        val inSecond = state.selection.terminal!!

        val infos = mapOf(inFirst to info(AgentState.IDLE, 1), inSecond to info(AgentState.IDLE, 2))

        assertEquals(listOf(inFirst), state.board(first, infos).getValue(BoardColumn.NOTHING).map { it.terminal.id })
        assertEquals(setOf(inFirst, inSecond), state.board(null, infos).getValue(BoardColumn.NOTHING).map { it.terminal.id }.toSet())
    }

    @Test
    fun aCardSaysWhereItsTerminalIs() {
        store.addProject("/dev/api-gateway")
        store.renameWorkspace(state.selection.workspace!!, "refactor-auth")

        val card = state.board(null, mapOf(state.selection.terminal!! to info(AgentState.IDLE, 1))).getValue(BoardColumn.NOTHING).single()

        assertEquals("api-gateway", card.project.name)
        assertEquals("refactor-auth", card.workspace.name)
    }

    @Test
    fun theColumnOfEachState() {
        assertEquals(
            listOf(BoardColumn.NOTHING, BoardColumn.WORKING, BoardColumn.NEEDS_INPUT, BoardColumn.DONE, BoardColumn.NEEDS_INPUT),
            listOf(AgentState.IDLE, AgentState.WORKING, AgentState.NEEDS_INPUT, AgentState.DONE, AgentState.ERROR).map { it.column() },
        )
    }

    @Test
    fun theColumnsHaveTheTitlesOfTheDesign() {
        assertEquals(listOf("Nothing", "Working", "Needs input", "Done"), BoardColumn.entries.map { it.title })
    }
}
