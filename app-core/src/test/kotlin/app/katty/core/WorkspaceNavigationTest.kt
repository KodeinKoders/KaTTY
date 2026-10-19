package app.katty.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WorkspaceNavigationTest {
    private var counter = 0
    private val store = KattyStore(newId = { "id${counter++}" })
    private val state get() = store.state.value

    // Two projects: `api` with workspaces a1 a2 a3, `web` with w1, and `empty`, which has none.
    private fun built(): Map<String, WorkspaceId> {
        val api = store.addProject("/dev/api")!!
        val a1 = state.selection.workspace!!
        val a2 = store.addWorkspace(api)!!
        val a3 = store.addWorkspace(api)!!
        val web = store.addProject("/dev/web")!!
        val w1 = state.selection.workspace!!
        store.renameWorkspace(a1, "a1")
        store.renameWorkspace(a2, "a2")
        store.renameWorkspace(a3, "a3")
        store.renameWorkspace(w1, "w1")
        return mapOf("a1" to a1, "a2" to a2, "a3" to a3, "w1" to w1)
    }

    private fun step(from: WorkspaceId, delta: Int): WorkspaceId? {
        store.selectWorkspace(from)
        return state.workspaceStep(delta)
    }

    @Test
    fun theNextWorkspaceCrossesFromAProjectToTheNext() {
        val w = built()

        assertEquals(w["a2"], step(w.getValue("a1"), 1))
        assertEquals(w["a3"], step(w.getValue("a2"), 1))
        assertEquals(w["w1"], step(w.getValue("a3"), 1), "The last one of a project goes to the first of the next")
    }

    @Test
    fun thePreviousWorkspaceCrossesBackToTheLastOfThePreviousProject() {
        val w = built()

        assertEquals(w["a3"], step(w.getValue("w1"), -1))
        assertEquals(w["a1"], step(w.getValue("a2"), -1))
    }

    @Test
    fun itWrapsAroundAtTheEnds() {
        val w = built()

        assertEquals(w["a1"], step(w.getValue("w1"), 1))
        assertEquals(w["w1"], step(w.getValue("a1"), -1))
    }

    @Test
    fun withOneWorkspaceInAllItStaysThere() {
        store.addProject("/dev/api")
        val only = state.selection.workspace!!

        assertEquals(only, state.workspaceStep(1))
        assertEquals(only, state.workspaceStep(-1))
    }

    @Test
    fun withNoWorkspaceThereIsNowhereToGo() {
        assertNull(KattyState().workspaceStep(1))
    }

    @Test
    fun aProjectWithNoWorkspaceIsPassedOverToTheNearestOne() {
        val w = built()
        val empty = store.addProject("/dev/empty")!!
        // A project without workspace: the last one is closed, which is the way to have one.
        store.closeWorkspace(state.selection.workspace!!)
        store.selectProject(empty)
        assertNull(state.selection.workspace)

        assertEquals(w["a1"], state.workspaceStep(1), "Next wraps to the first of all")
        assertEquals(w["w1"], state.workspaceStep(-1), "Previous is the last one of the project before it")
    }

    @Test
    fun theNumberGoesToTheWorkspaceOfTheSelectedProjectAndNineToTheLast() {
        val w = built()
        store.selectWorkspace(w.getValue("a1"))

        assertEquals(w["a1"], state.workspaceByNumber(1))
        assertEquals(w["a3"], state.workspaceByNumber(3))
        assertNull(state.workspaceByNumber(4))
        assertEquals(w["a3"], state.workspaceByNumber(9), "Nine is the last one, however many there are")
        store.selectWorkspace(w.getValue("w1"))
        assertEquals(w["w1"], state.workspaceByNumber(9))
        assertNull(state.workspaceByNumber(2))
    }

    @Test
    fun theNumberOfAPaneIsItsPlaceInTheOrderTheyAreDrawn() {
        store.addProject("/dev/api")
        val first = state.selection.terminal!!
        val second = store.split(first, SplitDirection.ROW)!!
        val third = store.split(second, SplitDirection.COLUMN)!!

        assertEquals(first, state.terminalByNumber(1))
        assertEquals(second, state.terminalByNumber(2))
        assertEquals(third, state.terminalByNumber(3))
        assertNull(state.terminalByNumber(4))
    }

    @Test
    fun aWorkspaceComesBackWithTheTerminalThatHadTheFocusThere() {
        val w = built()
        val a1 = w.getValue("a1")
        store.selectWorkspace(a1)
        val first = state.selection.terminal!!
        val second = store.split(first, SplitDirection.ROW)!!
        store.focus(first)
        store.focus(second)
        store.focus(first)

        store.selectWorkspace(w.getValue("w1"))
        store.selectWorkspace(a1)

        assertEquals(first, state.selection.terminal, "Not the first of the tree by chance: the last one used, which was the first")
        store.focus(second)
        store.selectWorkspace(w.getValue("a2"))
        store.selectWorkspace(a1)
        assertEquals(second, state.selection.terminal)
    }

    @Test
    fun aTerminalThatIsGoneIsNotFocusedAgain() {
        val w = built()
        val a1 = w.getValue("a1")
        store.selectWorkspace(a1)
        val first = state.selection.terminal!!
        val second = store.split(first, SplitDirection.ROW)!!
        store.focus(second)
        store.selectWorkspace(w.getValue("w1"))

        store.closeTerminal(second)
        store.selectWorkspace(a1)

        assertEquals(first, state.selection.terminal)
    }

    @Test
    fun choosingAnotherProjectFocusesWhereItsFirstWorkspaceHadTheFocus() {
        val w = built()
        val a1 = w.getValue("a1")
        store.selectWorkspace(a1)
        val first = state.selection.terminal!!
        val second = store.split(first, SplitDirection.ROW)!!
        store.focus(second)
        store.selectWorkspace(w.getValue("w1"))
        val api = state.projects.first { it.name == "api" }.id

        store.selectProject(api)

        assertEquals(second, state.selection.terminal)
    }

    @Test
    fun theNextProjectGoesInTheOrderTheyAreListedAndWrapsAround() {
        val api = store.addProject("/dev/api")!!
        val web = store.addProject("/dev/web")!!
        val docs = store.addProject("/dev/docs")!!
        store.selectProject(api)

        assertEquals(web, state.projectStep(1))
        store.selectProject(web)
        assertEquals(docs, state.projectStep(1))
        store.selectProject(docs)
        assertEquals(api, state.projectStep(1), "After the last one, the first")
        assertEquals(web, state.projectStep(-1))
        store.selectProject(api)
        assertEquals(docs, state.projectStep(-1), "Before the first one, the last")
    }

    @Test
    fun withOneProjectItStaysAndWithNoneThereIsNone() {
        val only = store.addProject("/dev/api")!!

        assertEquals(only, state.projectStep(1))
        assertNull(KattyState().projectStep(1))
    }

    @Test
    fun aProjectComesBackAtTheWorkspaceThatWasSelectedInItLast() {
        val api = store.addProject("/dev/api")!!
        val first = state.selection.workspace!!
        val second = store.addWorkspace(api, "second")!!
        store.selectWorkspace(second)
        val web = store.addProject("/dev/web")!!

        store.selectProject(api)

        assertEquals(second, state.selection.workspace, "Not the first one: the one it was left at")
        assertEquals(web, state.projectStep(1))
        store.selectWorkspace(first)
        store.selectProject(web)
        store.selectProject(api)
        assertEquals(first, state.selection.workspace)
    }

    @Test
    fun aWorkspaceThatIsGoneIsNotComeBackTo() {
        val api = store.addProject("/dev/api")!!
        val first = state.selection.workspace!!
        val second = store.addWorkspace(api, "second")!!
        store.selectWorkspace(second)
        val web = store.addProject("/dev/web")!!
        store.closeWorkspace(second)

        store.selectProject(api)

        assertEquals(first, state.selection.workspace)
        assertEquals(web, state.projectStep(1))
    }

    @Test
    fun focusingATerminalAlsoRemembersItsWorkspaceForItsProject() {
        val api = store.addProject("/dev/api")!!
        store.addWorkspace(api, "second")
        val inSecond = state.selection.terminal.let { store.state.value.projects.first().workspaces.last().layout.terminals().first().id }
        store.addProject("/dev/web")

        store.focus(inSecond)
        store.selectProject(state.projects.last().id)
        store.selectProject(api)

        assertEquals(state.projects.first().workspaces.last().id, state.selection.workspace)
    }
}
