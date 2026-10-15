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
            mapOf(a to info(AgentState.WORKING, 1), b to info(AgentState.NEEDS_INPUT, 2), c to info(AgentState.ERROR, 3), d to info(AgentState.DONE, 4)),
        )

        assertEquals(BoardColumn.entries.toList(), board.keys.toList())
        assertEquals(listOf(e), board.getValue(BoardColumn.NOTHING).map { it.terminal.id })
        assertEquals(listOf(a), board.getValue(BoardColumn.WORKING).map { it.terminal.id })
        assertEquals(listOf(c, b), board.getValue(BoardColumn.NEEDS_INPUT).map { it.terminal.id })
        assertEquals(listOf(d), board.getValue(BoardColumn.DONE).map { it.terminal.id })
    }

    @Test
    fun aTerminalThatSaidNothingIsInNothing() {
        store.addProject("/a")

        val board = state.board(null, emptyMap())

        assertEquals(1, board.getValue(BoardColumn.NOTHING).size)
        assertEquals(0, board.values.sumOf { it.size } - 1)
    }

    @Test
    fun theMostRecentOfAColumnComesFirst() {
        store.addProject("/a")
        val a = state.selection.terminal!!
        val b = store.split(a, SplitDirection.ROW)!!
        val c = store.split(b, SplitDirection.ROW)!!

        val board = state.board(null, mapOf(a to info(AgentState.WORKING, 100), b to info(AgentState.WORKING, 300), c to info(AgentState.WORKING, 200)))

        assertEquals(listOf(b, c, a), board.getValue(BoardColumn.WORKING).map { it.terminal.id })
    }

    @Test
    fun theFilterKeepsTheTerminalsOfOneProject() {
        val first = store.addProject("/a")
        val inFirst = state.selection.terminal!!
        store.addProject("/b")
        val inSecond = state.selection.terminal!!

        assertEquals(listOf(inFirst), state.board(first, emptyMap()).getValue(BoardColumn.NOTHING).map { it.terminal.id })
        assertEquals(setOf(inFirst, inSecond), state.board(null, emptyMap()).getValue(BoardColumn.NOTHING).map { it.terminal.id }.toSet())
    }

    @Test
    fun aCardSaysWhereItsTerminalIs() {
        store.addProject("/dev/api-gateway")
        store.renameWorkspace(state.selection.workspace!!, "refactor-auth")

        val card = state.board(null, emptyMap()).getValue(BoardColumn.NOTHING).single()

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
