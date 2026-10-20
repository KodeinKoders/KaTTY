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
    fun onlyTheTerminalsWhoseProgramReportsAreOnTheBoard() {
        store.addProject("/a")
        val shell = state.selection.terminal!!
        val silent = store.split(shell, SplitDirection.ROW)!!
        val editor = store.split(silent, SplitDirection.ROW)!!
        val agent = store.split(editor, SplitDirection.ROW)!!

        val board = state.board(
            null,
            mapOf(
                // A shell that reports nothing has no card; a program that is not a known agent has one, as has an agent.
                shell to AgentInfo(AgentReport(AgentState.IDLE), 0),
                editor to AgentInfo(AgentReport(AgentState.WORKING, "terraform"), 0),
                agent to info(AgentState.IDLE, 1),
                // A program that only says it is at rest, with no name, reports too.
                silent to AgentInfo(AgentReport(AgentState.IDLE, reporting = true), 2),
            ),
        )

        assertEquals(setOf(silent, editor, agent), board.values.flatten().map { it.terminal.id }.toSet())
        assertEquals(listOf(editor), board.getValue(BoardColumn.WORKING).map { it.terminal.id })
        assertEquals(listOf(agent, silent), board.getValue(BoardColumn.NOTHING).map { it.terminal.id })
        assertEquals(emptyList(), state.board(null, emptyMap()).values.flatten())
    }

    @Test
    fun aProgramAtRestAfterItsReportWasSeenIsStillOnTheBoard() {
        store.addProject("/a")
        val tool = state.selection.terminal!!
        // What the tracker makes of a terminal whose `done` was seen and taken off: no record, and still reporting, whatever it names.
        val named = AgentInfo(AgentReport(AgentState.IDLE, "my-tool", reporting = true), 5)
        val unnamed = AgentInfo(AgentReport(AgentState.IDLE, reporting = true), 5)

        assertEquals(listOf(tool), state.board(null, mapOf(tool to named)).getValue(BoardColumn.NOTHING).map { it.terminal.id })
        assertEquals(listOf(tool), state.board(null, mapOf(tool to unnamed)).getValue(BoardColumn.NOTHING).map { it.terminal.id })
    }

    @Test
    fun aProgramThatNeverReportedHasNoCardWhateverItIsCalled() {
        store.addProject("/a")
        val codex = state.selection.terminal!!

        // `codex` is a known agent, but a card comes from what a program says, not from its name.
        assertEquals(emptyList(), state.board(null, mapOf(codex to AgentInfo(AgentReport(AgentState.IDLE), 0))).values.flatten())
        assertEquals(emptyList(), state.board(null, emptyMap()).values.flatten())
    }

    @Test
    fun openCodeIsOnTheBoardByItsProgramAtRest() {
        store.addProject("/a")
        val opencode = state.selection.terminal!!

        val board = state.board(null, emptyMap(), mapOf(opencode to "opencode"))

        assertEquals(listOf(opencode), board.getValue(BoardColumn.NOTHING).map { it.terminal.id })
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
    fun equalTimesKeepTheOrderOfTheTree() {
        store.addProject("/a")
        val a = state.selection.terminal!!
        val b = store.split(a, SplitDirection.ROW)!!

        val board = state.board(null, mapOf(b to info(AgentState.IDLE, 50), a to info(AgentState.IDLE, 50)))

        // They arrived together and stay as the tree has them.
        assertEquals(listOf(a, b), board.getValue(BoardColumn.NOTHING).map { it.terminal.id })
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
