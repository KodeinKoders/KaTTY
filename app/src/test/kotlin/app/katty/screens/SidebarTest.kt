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
import androidx.compose.ui.test.performKeyInput
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

    @Composable
    private fun Content(onAddProject: () -> Unit = {}, removed: MutableList<String> = mutableListOf()) {
        KattyTheme(dark = false) {
            val state by store.state.collectAsState()
            var renaming by remember { mutableStateOf<WorkspaceId?>(null) }
            Sidebar(
                state, store,
                onAddProject = onAddProject,
                onRemoveProject = { removed += it.value },
                onCloseWorkspace = store::closeWorkspace,
                onReveal = {},
                renamingWorkspace = renaming,
                onRenamingWorkspace = { renaming = it },
            )
        }
    }

    @Test
    fun listsTheProjectsAndTheWorkspacesOfTheSelectedOne() = runComposeUiTest {
        store.addProject("/dev/api-gateway")
        store.addProject("/dev/web-client")
        store.addWorkspace(store.state.value.projects.last().id, "checkout-form")
        setContent { Content() }

        onNodeWithContentDescription("web-client").assertIsSelected()
        onNodeWithContentDescription("checkout-form").assertIsSelected()
        onNodeWithContentDescription("workspace").assertExists()
        // The other project is folded: its workspace is not listed.
        onNodeWithContentDescription("api-gateway").assertExists()
        assertEquals(1, onAllNodesWithContentDescription("workspace").fetchSemanticsNodes().size)
    }

    @Test
    fun clickingAProjectSelectsItAndOpensIt() = runComposeUiTest {
        val first = store.addProject("/dev/api-gateway")
        store.addProject("/dev/web-client")
        setContent { Content() }

        onNodeWithContentDescription("api-gateway").performClick()

        assertEquals(first, store.state.value.selection.project)
        onNodeWithContentDescription("api-gateway").assertIsSelected()
    }

    @Test
    fun clickingAWorkspaceSelectsIt() = runComposeUiTest {
        val project = store.addProject("/dev/api-gateway")
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
        store.addProject("/dev/api-gateway")
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
        store.addProject("/dev/api-gateway")
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
        store.addProject("/dev/api-gateway")
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
    fun escapeKeepsTheOldName() = runComposeUiTest {
        store.addProject("/dev/api-gateway")
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
        store.addProject("/dev/api-gateway")
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
        store.addProject("/dev/api-gateway")
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
