package app.katty.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KattyStoreTest {
    private var counter = 0
    private val store = KattyStore(newId = { "id${counter++}" })

    private val state get() = store.state.value

    private fun addProject(directory: String = "/dev/api-gateway") = store.addProject(directory)

    private val workspace get() = state.selectedWorkspace!!

    private fun names(workspace: Workspace) = workspace.layout.terminals().map { it.id.value }

    @Test
    fun aProjectTakesTheNameOfItsFolderAndStartsWithAWorkspaceAndAShell() {
        val id = addProject("/dev/api-gateway/")

        val project = state.projects.single()
        assertEquals(id, project.id)
        assertEquals("api-gateway", project.name)
        assertEquals("/dev/api-gateway/", project.directory)
        assertEquals("api-gateway", project.workspaces.single().name, "The first workspace is named after its project")
        val terminal = project.workspaces.single().layout.terminals().single()
        assertEquals("/dev/api-gateway/", terminal.directory)
        assertEquals(Selection(id, project.workspaces.single().id, terminal.id), state.selection)
    }

    @Test
    fun aFolderThatIsAlreadyAProjectIsSelectedInsteadOfAddedAgain() {
        val first = addProject("/a")
        addProject("/b")

        val again = addProject("/a")

        assertEquals(first, again)
        assertEquals(2, state.projects.size)
        assertEquals(first, state.selection.project)
    }

    @Test
    fun aNewProjectIsOpen() {
        addProject("/a")
        addProject("/b")

        assertEquals(listOf(true, true), state.projects.map { it.open })
    }

    @Test
    fun anyNumberOfProjectsAreOpenAtOnce() {
        val a = addProject("/a")
        val b = addProject("/b")
        val c = addProject("/c")

        store.setProjectOpen(b, false)

        assertEquals(mapOf(a to true, b to false, c to true), state.projects.associate { it.id to it.open })
        store.setProjectOpen(b, true)
        assertEquals(listOf(true, true, true), state.projects.map { it.open })
    }

    @Test
    fun closingAProjectDoesNotMoveTheSelection() {
        val a = addProject("/a")
        val selection = state.selection

        store.setProjectOpen(a, false)

        assertEquals(selection, state.selection)
    }

    @Test
    fun choosingTheProjectThatIsSelectedKeepsItsWorkspaceAndAnotherOneGoesToItsFirst() {
        val a = addProject("/a")
        val first = state.selection.workspace!!
        val second = store.addWorkspace(a, "second")!!
        val b = addProject("/b")
        val other = state.selection.workspace!!

        store.selectProject(a)
        assertEquals(first, state.selection.workspace)
        store.selectWorkspace(second)
        store.selectProject(a)
        assertEquals(second, state.selection.workspace)
        store.selectProject(b)
        assertEquals(other, state.selection.workspace)
    }

    @Test
    fun aProjectCanBeMovedWithoutChangingTheSelection() {
        val a = addProject("/a")
        val b = addProject("/b")
        val c = addProject("/c")
        val selection = state.selection

        store.moveProject(c, 0)
        assertEquals(listOf(c, a, b), state.projects.map { it.id })
        store.moveProject(c, 99)
        assertEquals(listOf(a, b, c), state.projects.map { it.id })
        store.moveProject(a, 1)
        assertEquals(listOf(b, a, c), state.projects.map { it.id })
        assertEquals(selection, state.selection)
    }

    @Test
    fun aWorkspaceIsMovedAmongTheOnesOfItsProjectOnly() {
        val a = addProject("/a")
        addProject("/b")
        val first = state.projects.first { it.id == a }.workspaces.single().id
        val second = store.addWorkspace(a)!!
        val third = store.addWorkspace(a)!!

        store.moveWorkspace(third, 0)
        assertEquals(listOf(third, first, second), state.projects.first { it.id == a }.workspaces.map { it.id })
        store.moveWorkspace(third, -5)
        assertEquals(third, state.projects.first { it.id == a }.workspaces.first().id)
        assertEquals(1, state.projects.last().workspaces.size)
    }

    @Test
    fun theFirstWorkspaceOfAProjectWithNoneIsNamedLikeTheProject() {
        val a = addProject("/code/alpha")
        store.closeWorkspace(state.selection.workspace!!)

        store.addWorkspace(a)
        store.addWorkspace(a)

        assertEquals(listOf("alpha", "workspace"), state.projects.single().workspaces.map { it.name })
    }

    @Test
    fun aWorkspaceThatIsAddedOrSelectedOpensItsProject() {
        val a = addProject("/a")
        val first = state.selection.workspace!!
        store.setProjectOpen(a, false)

        val second = store.addWorkspace(a)!!
        assertTrue(state.projects.single().open)

        store.setProjectOpen(a, false)
        store.selectWorkspace(first)
        assertTrue(state.projects.single().open)
        assertEquals(second, state.projects.single().workspaces.last().id)
    }

    @Test
    fun focusingATerminalDoesNotOpenAProjectThatTheUserClosed() {
        val a = addProject("/a")
        val terminal = state.selection.terminal!!
        store.setProjectOpen(a, false)

        store.focus(terminal)

        assertFalse(state.projects.single().open)
    }

    @Test
    fun renamingAProjectNeedsAName() {
        val id = addProject()

        store.renameProject(id, "  gateway ")
        store.renameProject(id, "   ")

        assertEquals("gateway", state.projects.single().name)
    }

    @Test
    fun removingTheSelectedProjectSelectsTheNextOneElseThePreviousOne() {
        val a = addProject("/a")
        val b = addProject("/b")
        val c = addProject("/c")

        store.removeProject(b)
        assertEquals(c, state.selection.project)
        store.removeProject(c)
        assertEquals(a, state.selection.project)
        store.removeProject(a)
        assertEquals(Selection(), state.selection)
        assertEquals(emptyList(), state.projects)
    }

    @Test
    fun removingAnotherProjectKeepsTheSelection() {
        val a = addProject("/a")
        val b = addProject("/b")
        val selection = state.selection

        store.removeProject(a)

        assertEquals(selection, state.selection)
        assertEquals(b, state.projects.single().id)
    }

    @Test
    fun newWorkspacesAreNamedWithTheLowestFreeNumber() {
        val project = addProject()

        val second = store.addWorkspace(project)
        val third = store.addWorkspace(project)
        store.renameWorkspace(second!!, "refactor-auth")
        val fourth = store.addWorkspace(project)
        store.closeWorkspace(third!!)
        val fifth = store.addWorkspace(project)

        val names = state.projects.single().workspaces.associate { it.id to it.name }
        // The first is named after the project; the ones after it are "workspace", "workspace 2"…
        assertEquals("api-gateway", names.values.first())
        assertEquals("refactor-auth", names[second])
        assertEquals("workspace", names[fourth])
        assertEquals("workspace 2", names[fifth])
    }

    @Test
    fun aNewWorkspaceIsSelectedWithOneShellInTheProjectsFolder() {
        val project = addProject("/dev/x")

        val workspace = store.addWorkspace(project)

        assertEquals(workspace, state.selection.workspace)
        assertEquals("/dev/x", this.workspace.layout.terminals().single().directory)
        assertEquals(this.workspace.layout.terminals().single().id, state.selection.terminal)
    }

    @Test
    fun noWorkspaceCanBeAddedToAProjectThatIsNotThere() {
        assertNull(store.addWorkspace(ProjectId("nothing")))
    }

    @Test
    fun closingTheSelectedWorkspaceSelectsTheNextOneElseThePreviousOne() {
        val project = addProject()
        val first = state.selection.workspace!!
        val second = store.addWorkspace(project)!!
        val third = store.addWorkspace(project)!!

        store.closeWorkspace(second)
        assertEquals(third, state.selection.workspace)
        store.closeWorkspace(third)
        assertEquals(first, state.selection.workspace)
        store.closeWorkspace(first)
        assertNull(state.selection.workspace)
        assertEquals(project, state.selection.project)
        assertEquals(emptyList(), state.projects.single().workspaces)
    }

    @Test
    fun splittingReplacesTheTerminalBySplitOfItAndANewShellThatHasTheFocus() {
        addProject("/dev/x")
        val old = state.selection.terminal!!

        val new = store.split(old, SplitDirection.ROW)!!

        val layout = workspace.layout as PaneNode.Split
        assertEquals(SplitDirection.ROW, layout.direction)
        assertEquals(0.5f, layout.ratio)
        assertEquals(listOf(old, new), workspace.layout.terminals().map { it.id })
        assertEquals(new, state.selection.terminal)
        assertEquals("/dev/x", workspace.layout.terminals().last().directory)
    }

    @Test
    fun theNewShellOfASplitStartsWhereTheSplitTerminalIs() {
        addProject("/dev/x")
        val old = state.selection.terminal!!
        store.setDirectory(old, "/dev/x/src")

        store.split(old, SplitDirection.COLUMN)

        assertEquals("/dev/x/src", workspace.layout.terminals().last().directory)
    }

    @Test
    fun splittingATerminalInTheMiddleOfATreeKeepsTheRest() {
        addProject()
        val a = state.selection.terminal!!
        val b = store.split(a, SplitDirection.ROW)!!
        val c = store.split(b, SplitDirection.COLUMN)!!

        val root = workspace.layout as PaneNode.Split
        assertEquals(a, (root.first as PaneNode.Leaf).terminal.id)
        val right = root.second as PaneNode.Split
        assertEquals(SplitDirection.COLUMN, right.direction)
        assertEquals(listOf(b, c), right.terminals().map { it.id })
    }

    private val PaneNode.terminals get() = terminals()

    @Test
    fun closingATerminalGivesItsSpaceToItsSibling() {
        addProject()
        val a = state.selection.terminal!!
        val b = store.split(a, SplitDirection.ROW)!!
        val c = store.split(b, SplitDirection.COLUMN)!!

        val result = store.closeTerminal(b)

        assertEquals(CloseResult.CLOSED, result)
        val root = workspace.layout as PaneNode.Split
        // The split that held b and c is gone: c is where it was.
        assertEquals(a, (root.first as PaneNode.Leaf).terminal.id)
        assertEquals(c, (root.second as PaneNode.Leaf).terminal.id)
    }

    @Test
    fun closingTheFocusedTerminalMovesTheFocusToTheOneBeforeItElseAfter() {
        addProject()
        val a = state.selection.terminal!!
        val b = store.split(a, SplitDirection.ROW)!!
        val c = store.split(b, SplitDirection.ROW)!!

        store.closeTerminal(c)
        assertEquals(b, state.selection.terminal)
        store.closeTerminal(a)
        assertEquals(b, state.selection.terminal)
    }

    @Test
    fun closingAnUnfocusedTerminalKeepsTheFocus() {
        addProject()
        val a = state.selection.terminal!!
        val b = store.split(a, SplitDirection.ROW)!!

        store.closeTerminal(a)

        assertEquals(b, state.selection.terminal)
    }

    @Test
    fun closingTheLastTerminalOfAWorkspaceDeletesIt() {
        val project = addProject()
        val first = state.selection.workspace!!
        val second = store.addWorkspace(project)!!
        val terminal = state.selection.terminal!!

        val result = store.closeTerminal(terminal)

        assertEquals(CloseResult.WORKSPACE_DELETED, result)
        assertEquals(listOf(first), state.projects.single().workspaces.map { it.id })
        assertEquals(first, state.selection.workspace)
        assertFalse(second in state.projects.single().workspaces.map { it.id })
    }

    @Test
    fun closingATerminalThatIsNotThereDoesNothing() {
        addProject()
        val before = state

        assertEquals(CloseResult.NOT_FOUND, store.closeTerminal(TerminalId("nothing")))
        assertEquals(before, state)
    }

    @Test
    fun aTerminalCanBeRenamedAndGoBackToItsDefaultName() {
        addProject()
        val id = state.selection.terminal!!

        store.renameTerminal(id, " build ")
        assertEquals("build", workspace.layout.terminals().single().name)
        store.renameTerminal(id, "  ")
        assertNull(workspace.layout.terminals().single().name)
    }

    @Test
    fun aTerminalCanHaveItsOwnColorScheme() {
        addProject()
        val id = state.selection.terminal!!

        store.setColorScheme(id, "Nord")
        assertEquals("Nord", workspace.layout.terminals().single().colorScheme)
        store.setColorScheme(id, null)
        assertNull(workspace.layout.terminals().single().colorScheme)
    }

    @Test
    fun movingATerminalAddsItToTheRightOfTheOtherWorkspaceAndFocusesIt() {
        val project = addProject()
        val first = state.selection.workspace!!
        val a = state.selection.terminal!!
        val b = store.split(a, SplitDirection.ROW)!!
        val second = store.addWorkspace(project)!!
        val c = state.selection.terminal!!

        assertTrue(store.moveTerminal(b, second))

        val target = state.projects.single().workspaces.first { it.id == second }
        assertEquals(listOf(c, b), target.layout.terminals().map { it.id })
        assertEquals(Selection(project, second, b), state.selection)
        // What it left is a single terminal now.
        assertEquals(a, (state.projects.single().workspaces.first { it.id == first }.layout as PaneNode.Leaf).terminal.id)
    }

    @Test
    fun movingTheLastTerminalOfAWorkspaceDeletesTheWorkspace() {
        val project = addProject()
        val first = state.selection.workspace!!
        val a = state.selection.terminal!!
        val second = store.addWorkspace(project)!!

        assertTrue(store.moveTerminal(a, second))

        assertEquals(listOf(second), state.projects.single().workspaces.map { it.id })
        assertFalse(first in state.projects.single().workspaces.map { it.id })
    }

    @Test
    fun aTerminalIsNotMovedToItsOwnWorkspaceNorToOneThatIsNotThere() {
        addProject()
        val terminal = state.selection.terminal!!

        assertFalse(store.moveTerminal(terminal, state.selection.workspace!!))
        assertFalse(store.moveTerminal(terminal, WorkspaceId("nothing")))
        assertFalse(store.moveTerminal(TerminalId("nothing"), state.selection.workspace!!))
    }

    @Test
    fun theRatioOfASplitIsKeptAwayFromTheEdges() {
        addProject()
        store.split(state.selection.terminal!!, SplitDirection.ROW)
        val split = workspace.layout as PaneNode.Split

        store.setSplitRatio(workspace.id, split.id, 0.7f)
        assertEquals(0.7f, (workspace.layout as PaneNode.Split).ratio)
        store.setSplitRatio(workspace.id, split.id, 0f)
        assertEquals(0.05f, (workspace.layout as PaneNode.Split).ratio)
        store.setSplitRatio(workspace.id, split.id, 3f)
        assertEquals(0.95f, (workspace.layout as PaneNode.Split).ratio)
    }

    @Test
    fun focusingATerminalSelectsItsWorkspaceAndProject() {
        val a = addProject("/a")
        val firstTerminal = state.selection.terminal!!
        val firstWorkspace = state.selection.workspace!!
        addProject("/b")

        store.focus(firstTerminal)

        assertEquals(Selection(a, firstWorkspace, firstTerminal), state.selection)
    }

    @Test
    fun maximizingFocusesTheTerminalAndFocusingAnotherPutsItBack() {
        addProject()
        val a = state.selection.terminal!!
        val b = store.split(a, SplitDirection.ROW)!!

        store.maximize(a)
        assertEquals(a, state.selection.maximized)
        assertEquals(a, state.selection.terminal)
        store.focus(b)
        assertNull(state.selection.maximized)
        store.maximize(b)
        store.maximize(null)
        assertNull(state.selection.maximized)
        assertEquals(b, state.selection.terminal)
    }

    @Test
    fun closingTheMaximizedTerminalPutsTheOthersBack() {
        addProject()
        val a = state.selection.terminal!!
        val b = store.split(a, SplitDirection.ROW)!!
        store.maximize(b)

        store.closeTerminal(b)

        assertNull(state.selection.maximized)
    }

    @Test
    fun selectingAWorkspaceSelectsItsFirstTerminal() {
        val project = addProject()
        val first = state.selection.workspace!!
        val firstTerminal = state.selection.terminal!!
        store.addWorkspace(project)

        store.selectWorkspace(first)

        assertEquals(Selection(project, first, firstTerminal), state.selection)
    }

    @Test
    fun theFontSizeStaysInTheRangeThatCanBeUsed() {
        store.updateSettings { it.copy(fontSize = 2f) }
        assertEquals(Settings.MIN_FONT_SIZE, state.settings.fontSize)
        store.updateSettings { it.copy(fontSize = 99f) }
        assertEquals(Settings.MAX_FONT_SIZE, state.settings.fontSize)
        store.updateSettings { it.copy(fontSize = 14f) }
        assertEquals(14f, state.settings.fontSize)
    }

    @Test
    fun theSelectionIsMadeToPointAtSomethingWhenTheStateIsBuiltFromAnInconsistentOne() {
        val project = Project(ProjectId("p"), "p", "/p", listOf(Workspace(WorkspaceId("w"), "w", PaneNode.Leaf(TerminalSpec(TerminalId("t"), directory = "/p")))))
        val built = KattyStore(KattyState(projects = listOf(project), selection = Selection(ProjectId("gone"), WorkspaceId("gone"), TerminalId("gone"), TerminalId("gone"))))

        assertEquals(Selection(ProjectId("p"), WorkspaceId("w"), TerminalId("t")), built.state.value.selection)
    }

    @Test
    fun theIdsOfWhatIsCreatedAreDistinct() {
        val project = addProject()
        store.addWorkspace(project)
        store.split(state.selection.terminal!!, SplitDirection.ROW)

        val ids = state.allTerminals().map { it.terminal.id.value } + state.projects.flatMap { it.workspaces }.map { it.id.value } + project.value
        assertEquals(ids.size, ids.toSet().size)
        assertNotEquals(0, ids.size)
        assertNotNull(state.locate(state.selection.terminal!!))
    }
}
