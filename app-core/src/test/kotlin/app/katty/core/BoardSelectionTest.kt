package app.katty.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BoardSelectionTest {
    private var counter = 0
    private val store = KattyStore(newId = { "id${counter++}" })
    private val state get() = store.state.value

    private fun info(state: AgentState, since: Long) = AgentInfo(AgentReport(state, "claude-code"), since)

    // Working: w. Needs input: n1 (older) and n2 and n3. Done: d. Nothing is empty.
    private data class Cards(val w: TerminalId, val n1: TerminalId, val n2: TerminalId, val n3: TerminalId, val d: TerminalId)

    private fun built(): Pair<Cards, Map<BoardColumn, List<BoardCard>>> {
        store.addProject("/a")
        val w = state.selection.terminal!!
        val n1 = store.split(w, SplitDirection.ROW)!!
        val n2 = store.split(n1, SplitDirection.ROW)!!
        val n3 = store.split(n2, SplitDirection.ROW)!!
        val d = store.split(n3, SplitDirection.ROW)!!
        val board = state.board(
            null,
            mapOf(
                w to info(AgentState.WORKING, 1), n1 to info(AgentState.NEEDS_INPUT, 1), n2 to info(AgentState.NEEDS_INPUT, 2),
                n3 to info(AgentState.NEEDS_INPUT, 3), d to info(AgentState.DONE, 1),
            ),
        )
        return Cards(w, n1, n2, n3, d) to board
    }

    @Test
    fun anArrowFromNoSelectionIsTheFirstCardOfTheBoard() {
        val (cards, board) = built()

        for (direction in Direction.entries) {
            assertEquals(cards.w, board.stepSelection(null, direction, stacked = false), "$direction")
            assertEquals(cards.w, board.stepSelection(TerminalId("gone"), direction, stacked = false), "$direction")
        }
    }

    @Test
    fun aBoardWithNoCardHasNothingToSelect() {
        assertNull(state.board(null, emptyMap()).stepSelection(null, Direction.DOWN, stacked = false))
    }

    @Test
    fun downAndUpGoAlongAColumnAndStopAtItsEnds() {
        val (cards, board) = built()

        assertEquals(cards.n2, board.stepSelection(cards.n1, Direction.DOWN, stacked = false))
        assertEquals(cards.n3, board.stepSelection(cards.n2, Direction.DOWN, stacked = false))
        assertEquals(cards.n3, board.stepSelection(cards.n3, Direction.DOWN, stacked = false), "The last one stays")
        assertEquals(cards.n1, board.stepSelection(cards.n1, Direction.UP, stacked = false), "The first one stays")
        assertEquals(cards.n1, board.stepSelection(cards.n2, Direction.UP, stacked = false))
    }

    @Test
    fun leftAndRightGoToTheNearestColumnThatHasACardKeepingTheRow() {
        val (cards, board) = built()

        // Working and Done are not next to the column of Needs input: the empty one in between is passed over.
        assertEquals(cards.n1, board.stepSelection(cards.w, Direction.RIGHT, stacked = false))
        assertEquals(cards.d, board.stepSelection(cards.n3, Direction.RIGHT, stacked = false), "Done has one card: the row is its last")
        assertEquals(cards.n1, board.stepSelection(cards.d, Direction.LEFT, stacked = false), "Back to the first row of what has three")
        assertEquals(cards.w, board.stepSelection(cards.n2, Direction.LEFT, stacked = false), "Working has one card")
        assertEquals(cards.w, board.stepSelection(cards.w, Direction.LEFT, stacked = false), "There is nothing on the left of the first column")
        assertEquals(cards.d, board.stepSelection(cards.d, Direction.RIGHT, stacked = false))
    }

    @Test
    fun stackedTheUpAndDownGoThroughEveryCardInTheOrderTheyAreDrawn() {
        val (cards, board) = built()

        assertEquals(cards.n1, board.stepSelection(cards.w, Direction.DOWN, stacked = true))
        assertEquals(cards.d, board.stepSelection(cards.n3, Direction.DOWN, stacked = true))
        assertEquals(cards.n3, board.stepSelection(cards.d, Direction.UP, stacked = true))
        assertEquals(cards.w, board.stepSelection(cards.w, Direction.UP, stacked = true))
        assertEquals(cards.d, board.stepSelection(cards.d, Direction.DOWN, stacked = true))
        assertEquals(cards.n2, board.stepSelection(cards.n2, Direction.LEFT, stacked = true), "Sideways means nothing when they are stacked")
        assertEquals(cards.n2, board.stepSelection(cards.n2, Direction.RIGHT, stacked = true))
    }
}
