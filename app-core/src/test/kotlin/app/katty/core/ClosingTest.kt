package app.katty.core

import kotlin.test.Test
import kotlin.test.assertEquals

class ClosingTest {
    private var counter = 0
    private val store = KattyStore(newId = { "id${counter++}" })
    private val state get() = store.state.value

    private val none: (TerminalId) -> Boolean = { false }

    private fun statesOf(vararg states: Pair<TerminalId, AgentState>): (TerminalId) -> AgentState = { id -> states.toMap()[id] ?: AgentState.IDLE }

    @Test
    fun onlyAgentsThatAreActiveAskForConfirmation() {
        assertEquals(
            listOf(false, true, true, true, false),
            listOf(AgentState.IDLE, AgentState.WORKING, AgentState.NEEDS_INPUT, AgentState.ERROR, AgentState.DONE).map { it.isActive() },
        )
    }

    @Test
    fun aWorkspaceOfShellsAtTheirPromptsClosesAtOnce() {
        store.addProject("/a")
        store.split(state.selection.terminal!!, SplitDirection.ROW)

        assertEquals(CloseDecision.NOW, state.closeWorkspaceDecision(state.selection.workspace!!, statesOf(), none))
    }

    @Test
    fun aWorkspaceWhereAProgramRunsAsks() {
        store.addProject("/a")
        val editor = store.split(state.selection.terminal!!, SplitDirection.ROW)!!

        // vim, a build, an ssh: a program that reports nothing is stopped all the same.
        assertEquals(CloseDecision.CONFIRM, state.closeWorkspaceDecision(state.selection.workspace!!, statesOf(), { it == editor }))
    }

    @Test
    fun aWorkspaceWithAnActiveAgentAsks() {
        store.addProject("/a")
        val agent = store.split(state.selection.terminal!!, SplitDirection.ROW)!!

        assertEquals(CloseDecision.CONFIRM, state.closeWorkspaceDecision(state.selection.workspace!!, statesOf(agent to AgentState.WORKING), none))
    }

    @Test
    fun anAgentThatIsDoneDoesNotMakeAWorkspaceAsk() {
        store.addProject("/a")
        val agent = state.selection.terminal!!

        assertEquals(CloseDecision.NOW, state.closeWorkspaceDecision(state.selection.workspace!!, statesOf(agent to AgentState.DONE), none))
    }

    @Test
    fun aWorkspaceThatIsNotThereClosesWithoutAsking() {
        assertEquals(CloseDecision.NOW, state.closeWorkspaceDecision(WorkspaceId("nothing"), statesOf(), none))
    }

    @Test
    fun theLastTerminalWithAnActiveAgentAsksBecauseItDeletesTheWorkspace() {
        store.addProject("/a")
        val only = state.selection.terminal!!

        assertEquals(CloseDecision.CONFIRM, state.closeTerminalDecision(only, statesOf(only to AgentState.NEEDS_INPUT), none))
        assertEquals(CloseDecision.NOW, state.closeTerminalDecision(only, statesOf(only to AgentState.IDLE), none))
    }

    @Test
    fun aTerminalThatHasCompanyAndRunsNoProgramClosesAtOnceWhateverItsAgentDoes() {
        store.addProject("/a")
        val first = state.selection.terminal!!
        store.split(first, SplitDirection.ROW)

        assertEquals(CloseDecision.NOW, state.closeTerminalDecision(first, statesOf(first to AgentState.WORKING), none))
    }

    @Test
    fun aTerminalThatRunsAProgramAsksEvenWithCompany() {
        store.addProject("/a")
        val first = state.selection.terminal!!
        val second = store.split(first, SplitDirection.ROW)!!

        assertEquals(CloseDecision.CONFIRM, state.closeTerminalDecision(first, statesOf(), { it == first }))
        assertEquals(CloseDecision.NOW, state.closeTerminalDecision(second, statesOf(), { it == first }))
    }

    @Test
    fun listsTheActiveTerminalsOfAProject() {
        val project = store.addProject("/a")
        val first = state.selection.terminal!!
        val second = store.split(first, SplitDirection.ROW)!!
        store.addWorkspace(project)
        val third = state.selection.terminal!!

        val active = state.activeTerminals(project, statesOf(first to AgentState.WORKING, second to AgentState.DONE, third to AgentState.NEEDS_INPUT))

        assertEquals(listOf(first, third), active.map { it.id })
    }

    @Test
    fun quittingAsksForTheTerminalsOfEveryWorkspaceWhereAProgramRunsOrAnAgentIsActive() {
        val project = store.addProject("/a")
        val first = state.selection.terminal!!
        val second = store.split(first, SplitDirection.ROW)!!
        store.addWorkspace(project)
        val third = state.selection.terminal!!
        store.addProject("/b")
        val fourth = state.selection.terminal!!
        val states = statesOf(first to AgentState.DONE, second to AgentState.ERROR, third to AgentState.IDLE)

        assertEquals(CloseDecision.NOW, state.quitDecision(statesOf(first to AgentState.DONE), none), "Shells at their prompts, and an agent that is done")
        // A failure that the user has not seen is active: quitting would lose what it said.
        assertEquals(listOf(second, fourth), state.stoppedByQuitting(states) { it == fourth }.map { it.terminal.id })
        assertEquals(CloseDecision.CONFIRM, state.quitDecision(states) { it == fourth })
        assertEquals(CloseDecision.CONFIRM, state.quitDecision(statesOf(third to AgentState.WORKING), none))
    }
}
