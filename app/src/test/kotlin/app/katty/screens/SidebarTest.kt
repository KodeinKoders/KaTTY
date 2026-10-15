@file:OptIn(ExperimentalTestApi::class)

package app.katty.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.moveTo
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.graphics.toPixelMap
import kotlin.test.assertTrue
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.onAllNodesWithContentDescription
import app.katty.core.KattyStore
import app.katty.core.WorkspaceId
import app.katty.ui.KattyTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SidebarTest {
    private var counter = 0
    private val store = KattyStore(newId = { "id${counter++}" })

    // A project whose first workspace is called "workspace", which is not what a new project's is: that has its project's name.
    private fun plainProject(directory: String): app.katty.core.ProjectId {
        val id = store.addProject(directory)
        store.renameWorkspace(store.state.value.selection.workspace!!, "workspace")
        return id
    }

    @Composable
    private fun Content(onAddProject: () -> Unit = {}, removed: MutableList<String> = mutableListOf()) {
        KattyTheme(dark = false) {
            val state by store.state.collectAsState()
            var renaming by remember { mutableStateOf<WorkspaceId?>(null) }
            var renamingProject by remember { mutableStateOf<app.katty.core.ProjectId?>(null) }
            Sidebar(
                state, store,
                onAddProject = onAddProject,
                onRemoveProject = { removed += it.value },
                onCloseWorkspace = store::closeWorkspace,
                onReveal = {},
                renamingProject = renamingProject,
                onRenamingProject = { renamingProject = it },
                renamingWorkspace = renaming,
                onRenamingWorkspace = { renaming = it },
            )
        }
    }

    @Test
    fun listsTheWorkspacesOfEveryProjectThatIsOpen() = runComposeUiTest {
        plainProject("/dev/api-gateway")
        plainProject("/dev/web-client")
        store.addWorkspace(store.state.value.projects.last().id, "checkout-form")
        setContent { Content() }

        onNodeWithContentDescription("web-client").assertIsSelected()
        onNodeWithContentDescription("checkout-form").assertIsSelected()
        onNodeWithContentDescription("api-gateway").assertExists()
        // The workspace of the other project is listed too: both are open.
        assertEquals(2, onAllNodesWithContentDescription("workspace").fetchSemanticsNodes().size)
    }

    @Test
    fun aClosedProjectDoesNotListItsWorkspaces() = runComposeUiTest {
        plainProject("/dev/api-gateway")
        val web = plainProject("/dev/web-client")
        store.setProjectOpen(web, false)
        setContent { Content() }

        assertEquals(1, onAllNodesWithContentDescription("workspace").fetchSemanticsNodes().size)
        onNodeWithContentDescription("Open web-client").assertExists()
        onNodeWithContentDescription("Close api-gateway").assertExists()
    }

    @Test
    fun clickingAClosedProjectOpensItAndSelectsIt() = runComposeUiTest {
        val first = plainProject("/dev/api-gateway")
        store.setProjectOpen(first, false)
        plainProject("/dev/web-client")
        setContent { Content() }

        onNodeWithContentDescription("api-gateway").performClick()
        waitForIdle()

        assertTrue(store.state.value.projects.first().open)
        assertEquals(first, store.state.value.selection.project)
        onNodeWithContentDescription("Close api-gateway").assertExists()
    }

    @Test
    fun clickingAnOpenProjectClosesIt() = runComposeUiTest {
        val first = plainProject("/dev/api-gateway")
        val web = plainProject("/dev/web-client")
        setContent { Content() }

        onNodeWithContentDescription("api-gateway").performClick()
        waitForIdle()

        assertEquals(listOf(false, true), store.state.value.projects.map { it.open })
        // Closing it does not select it, nor move the selection.
        assertEquals(web, store.state.value.selection.project)
        assertTrue(first != store.state.value.selection.project)
        onNodeWithContentDescription("Open api-gateway").assertExists()
    }

    @Test
    fun clickingTheSelectedProjectClosesItAndTheSelectionStays() = runComposeUiTest {
        plainProject("/dev/api-gateway")
        val web = plainProject("/dev/web-client")
        val workspace = store.state.value.selection.workspace
        setContent { Content() }

        onNodeWithContentDescription("web-client").performClick()
        waitForIdle()

        assertEquals(listOf(true, false), store.state.value.projects.map { it.open })
        assertEquals(web, store.state.value.selection.project)
        assertEquals(workspace, store.state.value.selection.workspace)
    }

    @Test
    fun theArrowOfAnOpenProjectClosesItWithoutSelectingIt() = runComposeUiTest {
        plainProject("/dev/api-gateway")
        val web = plainProject("/dev/web-client")
        setContent { Content() }

        onNodeWithContentDescription("Close api-gateway").performClick()
        waitForIdle()

        assertEquals(listOf(false, true), store.state.value.projects.map { it.open })
        assertEquals(web, store.state.value.selection.project)
        onNodeWithContentDescription("Open api-gateway").assertExists()
    }

    @Test
    fun theArrowOfAClosedProjectOpensIt() = runComposeUiTest {
        val first = plainProject("/dev/api-gateway")
        plainProject("/dev/web-client")
        store.setProjectOpen(first, false)
        setContent { Content() }

        onNodeWithContentDescription("Open api-gateway").performClick()
        waitForIdle()

        assertEquals(listOf(true, true), store.state.value.projects.map { it.open })
        // Opening is not selecting: the arrow only shows or hides.
        assertTrue(first != store.state.value.selection.project)
    }

    @Test
    fun clickingAWorkspaceSelectsIt() = runComposeUiTest {
        val project = plainProject("/dev/api-gateway")
        val second = store.addWorkspace(project, "release-notes")!!
        val first = store.state.value.projects.single().workspaces.first().id
        setContent { Content() }

        onNodeWithContentDescription("workspace").performClick()
        assertEquals(first, store.state.value.selection.workspace)
        onNodeWithContentDescription("release-notes").performClick()
        assertEquals(second, store.state.value.selection.workspace)
    }

    @Test
    fun theNewWorkspaceRowAddsAWorkspaceAndOpensItsNameForEditing() = runComposeUiTest {
        plainProject("/dev/api-gateway")
        setContent { Content() }

        onNodeWithText("New workspace").performClick()

        val workspaces = store.state.value.projects.single().workspaces
        assertEquals(listOf("workspace", "workspace 2"), workspaces.map { it.name })
        // Its name is a field, with its text selected: typing replaces it.
        waitForIdle()
        onNodeWithText("workspace 2").assertExists()
    }

    @Test
    fun f2RenamesTheWorkspaceThatHasTheFocus() = runComposeUiTest {
        plainProject("/dev/api-gateway")
        setContent { Content() }

        onNodeWithContentDescription("workspace").performClick()
        onNodeWithContentDescription("workspace").performKeyInput { pressKey(Key.F2) }
        waitForIdle()
        // The field has the text, selected; typing replaces it and Enter keeps it.
        onNodeWithText("workspace").performTextInput("refactor-auth")
        onNodeWithText("refactor-auth").performKeyInput { pressKey(Key.Enter) }
        waitForIdle()

        assertEquals("refactor-auth", store.state.value.projects.single().workspaces.single().name)
    }

    @Test
    fun f2RenamesTheProjectThatHasTheFocus() = runComposeUiTest {
        plainProject("/dev/api-gateway")
        setContent { Content() }

        onNodeWithContentDescription("api-gateway").performClick()
        onNodeWithContentDescription("api-gateway").performKeyInput { pressKey(Key.F2) }
        waitForIdle()
        onNodeWithText("api-gateway").performTextInput("gateway")
        onNodeWithText("gateway").performKeyInput { pressKey(Key.Enter) }
        waitForIdle()

        assertEquals("gateway", store.state.value.projects.single().name)
    }

    @Test
    fun theRowsThatCanBeClickedChangeWhenThePointerIsOverThem() = runComposeUiTest {
        plainProject("/dev/api-gateway")
        setContent { Content() }

        // The row around the text: its fill is what changes, so the whole row is looked at.
        val before = onNodeWithText("New workspace").fetchSemanticsNode().boundsInRoot
        val rowBefore = onRoot().captureToImage().toPixelMap()
        onNodeWithText("New workspace").performMouseInput { moveTo(center) }
        waitForIdle()
        val rowAfter = onRoot().captureToImage().toPixelMap()

        var differs = false
        for (x in before.left.toInt() until before.right.toInt() step 2) for (y in before.top.toInt() until before.bottom.toInt() step 2) {
            if (rowBefore[x, y] != rowAfter[x, y]) differs = true
        }
        assertTrue(differs, "The row did not change under the pointer")
    }

    @Test
    fun theSelectedProjectChangesUnderThePointerToo() = runComposeUiTest {
        plainProject("/dev/api-gateway")
        setContent { Content() }
        // Its row has no fill of its own when it is selected (the workspace is what is highlighted), so the hover is what shows.
        val bounds = onNodeWithContentDescription("api-gateway").fetchSemanticsNode().boundsInRoot
        val before = onRoot().captureToImage().toPixelMap()

        onNodeWithContentDescription("api-gateway").performMouseInput { moveTo(center) }
        waitForIdle()
        val after = onRoot().captureToImage().toPixelMap()

        var differs = false
        for (x in bounds.left.toInt() until bounds.right.toInt() step 2) for (y in bounds.top.toInt() until bounds.bottom.toInt() step 2) {
            if (before[x, y] != after[x, y]) differs = true
        }
        assertTrue(differs, "The selected project did not change under the pointer")
    }

    @Test
    fun doubleClickingAProjectNameRenamesIt() = runComposeUiTest {
        plainProject("/dev/api-gateway")
        setContent { Content() }

        onNodeWithContentDescription("api-gateway").performMouseInput { doubleClick() }
        waitForIdle()
        onNodeWithText("api-gateway").performTextInput("gateway")
        onNodeWithText("gateway").performKeyInput { pressKey(Key.Enter) }
        waitForIdle()

        assertEquals("gateway", store.state.value.projects.single().name)
    }

    @Test
    fun doubleClickingAWorkspaceNameRenamesIt() = runComposeUiTest {
        plainProject("/dev/api-gateway")
        setContent { Content() }

        onNodeWithContentDescription("workspace").performMouseInput { doubleClick() }
        waitForIdle()
        onNodeWithText("workspace").performTextInput("refactor-auth")
        onNodeWithText("refactor-auth").performKeyInput { pressKey(Key.Enter) }
        waitForIdle()

        assertEquals("refactor-auth", store.state.value.projects.single().workspaces.single().name)
    }

    @Test
    fun aSingleClickOnlySelectsAndDoesNotRename() = runComposeUiTest {
        val project = plainProject("/dev/api-gateway")
        store.addWorkspace(project, "release-notes")
        setContent { Content() }

        onNodeWithContentDescription("workspace").performClick()
        waitForIdle()
        onNodeWithContentDescription("release-notes").performClick()
        waitForIdle()

        assertEquals("release-notes", store.state.value.selectedWorkspace!!.name)
        // No field is open: the names are still text, and typing finds no field.
        assertEquals(0, onAllNodes(androidx.compose.ui.test.hasSetTextAction()).fetchSemanticsNodes().size)
    }

    @Test
    fun twoClicksFarApartAreNotADoubleClick() = runComposeUiTest {
        val project = plainProject("/dev/api-gateway")
        store.addWorkspace(project, "release-notes")
        setContent { Content() }

        onNodeWithContentDescription("workspace").performClick()
        onNodeWithContentDescription("release-notes").performClick()
        waitForIdle()

        assertEquals(0, onAllNodes(androidx.compose.ui.test.hasSetTextAction()).fetchSemanticsNodes().size)
    }

    @Test
    fun escapeKeepsTheOldName() = runComposeUiTest {
        plainProject("/dev/api-gateway")
        setContent { Content() }

        onNodeWithContentDescription("api-gateway").performClick()
        onNodeWithContentDescription("api-gateway").performKeyInput { pressKey(Key.F2) }
        waitForIdle()
        onNodeWithText("api-gateway").performTextInput("gateway")
        onNodeWithText("gateway").performKeyInput { pressKey(Key.Escape) }
        waitForIdle()

        assertEquals("api-gateway", store.state.value.projects.single().name)
    }

    @Test
    fun aBlankNameKeepsTheOldOne() = runComposeUiTest {
        plainProject("/dev/api-gateway")
        setContent { Content() }

        onNodeWithContentDescription("api-gateway").performClick()
        onNodeWithContentDescription("api-gateway").performKeyInput { pressKey(Key.F2) }
        waitForIdle()
        onNodeWithText("api-gateway").performTextInput("   ")
        onNodeWithText("   ").performKeyInput { pressKey(Key.Enter) }
        waitForIdle()

        assertEquals("api-gateway", store.state.value.projects.single().name)
    }

    @Test
    fun theAddButtonAsksForAFolder() = runComposeUiTest {
        plainProject("/dev/api-gateway")
        var asked = 0
        setContent { Content(onAddProject = { asked++ }) }

        onNodeWithContentDescription("Add project").performClick()

        assertEquals(1, asked)
    }

    @Test
    fun theHomeFolderIsWrittenAsATilde() {
        assertEquals("~", "/Users/x".abbreviatedHome("/Users/x"))
        assertEquals("~/dev/api-gateway", "/Users/x/dev/api-gateway".abbreviatedHome("/Users/x"))
        assertEquals("/Users/xylophone/dev", "/Users/xylophone/dev".abbreviatedHome("/Users/x"))
        assertEquals("/opt/api", "/opt/api".abbreviatedHome("/Users/x"))
    }
}
