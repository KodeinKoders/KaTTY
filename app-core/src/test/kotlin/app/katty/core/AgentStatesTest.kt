package app.katty.core

import kotlin.test.Test
import kotlin.test.assertEquals

class AgentStatesTest {
    private var counter = 0
    private val store = KattyStore(newId = { "id${counter++}" })
    private val state get() = store.state.value

    private fun states(vararg pairs: Pair<TerminalId, AgentState>) = object : AgentStates {
        override fun stateOf(id: TerminalId) = pairs.toMap()[id] ?: AgentState.IDLE
    }

    @Test
    fun nothingIsKnownOfTerminalsUntilTheyAreTold() {
        store.addProject("/a")

        assertEquals(AgentState.IDLE, NoAgentStates.stateOf(state.selection.terminal!!))
        assertEquals(AgentState.IDLE, NoAgentStates.urgent(state.projects.single()))
    }

    @Test
    fun theCountsOfAProjectStartWithTheMostUrgentState() {
        val project = store.addProject("/a")
        val a = state.selection.terminal!!
        val b = store.split(a, SplitDirection.ROW)!!
        val c = store.split(b, SplitDirection.ROW)!!
        store.addWorkspace(project)
        val d = state.selection.terminal!!
        val states = states(a to AgentState.WORKING, b to AgentState.NEEDS_INPUT, c to AgentState.WORKING, d to AgentState.WORKING)

        assertEquals(
            listOf(AgentState.NEEDS_INPUT to 1, AgentState.WORKING to 3),
            states.counts(state.projects.single()),
        )
    }

    @Test
    fun theCountsLeaveOutTheStatesThatNoTerminalIsIn() {
        store.addProject("/a")

        assertEquals(listOf(AgentState.IDLE to 1), NoAgentStates.counts(state.projects.single()))
    }

    @Test
    fun aWorkspaceIsShownWithTheStateOfEachOfItsTerminalsInTheOrderTheyAreDrawn() {
        store.addProject("/a")
        val a = state.selection.terminal!!
        val b = store.split(a, SplitDirection.ROW)!!
        val c = store.split(b, SplitDirection.COLUMN)!!

        val strip = states(a to AgentState.WORKING, c to AgentState.DONE).strip(state.selectedWorkspace!!)

        assertEquals(listOf(AgentState.WORKING, AgentState.IDLE, AgentState.DONE), strip)
    }

    @Test
    fun aProjectIsAsUrgentAsItsMostUrgentTerminal() {
        store.addProject("/a")
        val a = state.selection.terminal!!
        val b = store.split(a, SplitDirection.ROW)!!
        val project = state.projects.single()

        assertEquals(AgentState.NEEDS_INPUT, states(a to AgentState.DONE, b to AgentState.NEEDS_INPUT).urgent(project))
        assertEquals(AgentState.WORKING, states(a to AgentState.DONE, b to AgentState.WORKING).urgent(project))
        assertEquals(AgentState.DONE, states(a to AgentState.DONE).urgent(project))
        assertEquals(AgentState.ERROR, states(a to AgentState.ERROR, b to AgentState.WORKING).urgent(project))
    }

    @Test
    fun aProjectWithoutTerminalsIsAtRest() {
        val project = Project(ProjectId("p"), "p", "/p")

        assertEquals(AgentState.IDLE, NoAgentStates.urgent(project))
        assertEquals(emptyList(), NoAgentStates.counts(project))
    }
}
