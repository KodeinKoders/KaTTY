@file:OptIn(ExperimentalTestApi::class)

package app.katty

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import app.katty.core.PaneNode
import app.katty.core.Project
import app.katty.core.ProjectId
import app.katty.core.RecentProject
import app.katty.core.RunningCommand
import app.katty.core.SplitDirection
import app.katty.core.SplitId
import app.katty.core.TerminalId
import app.katty.core.TerminalSpec
import app.katty.core.Workspace
import app.katty.core.WorkspaceId
import app.katty.screens.recentContext
import app.katty.ui.KattyTheme
import app.katty.ui.render
import kotlin.test.Test
import kotlin.test.assertEquals

class RestoreProjectDialogTest {
    private fun terminal(id: String, running: RunningCommand? = null) = PaneNode.Leaf(TerminalSpec(TerminalId(id), directory = "/dev/api", running = running))

    private val saved = Project(
        ProjectId("p"), "api-gateway", "/dev/api",
        listOf(
            Workspace(
                WorkspaceId("w1"), "refactor-auth",
                PaneNode.Split(SplitId("s"), SplitDirection.ROW, 0.5f, terminal("a", RunningCommand(listOf("claude"), agent = "claude")), terminal("b", RunningCommand(listOf("/usr/bin/vim", "x")))),
            ),
            Workspace(WorkspaceId("w2"), "main", terminal("c")),
        ),
    )

    private fun androidx.compose.ui.test.ComposeUiTest.dialog(answers: MutableList<String>) {
        setContent { KattyTheme(false) { RestoreProjectDialog(saved, onRestore = { answers += "restore" }, onFresh = { answers += "fresh" }) } }
        waitForIdle()
    }

    @Test
    fun itListsTheWorkspacesAndWhatRunsInThem() = runComposeUiTest {
        dialog(mutableListOf())

        onNodeWithText("Restore “api-gateway”?").assertExists()
        onNodeWithText("refactor-auth  ·  2 terminals  ·  Claude Code  ·  vim").assertExists()
        onNodeWithText("main  ·  1 terminal").assertExists()
    }

    @Test
    fun enterRestores() = runComposeUiTest {
        val answers = mutableListOf<String>()
        dialog(answers)

        onNodeWithText("Restore").performKeyInput { pressKey(Key.Enter) }

        assertEquals(listOf("restore"), answers)
    }

    @Test
    fun escapeOpensItFresh() = runComposeUiTest {
        val answers = mutableListOf<String>()
        dialog(answers)

        onNodeWithText("Restore").performKeyInput { pressKey(Key.Escape) }
        onNodeWithText("Don't restore").performClick()

        assertEquals(listOf("fresh", "fresh"), answers)
    }

    @Test
    fun theRecentListSaysWhatComesBack() {
        assertEquals("~/dev/api  ·  2 workspaces, 3 terminals", recentContext(RecentProject("/home/me/dev/api", "api", 1, saved), "/home/me"))
        assertEquals("/dev/api", recentContext(RecentProject("/dev/api", "api", 1), "/home/me"))
    }

    @Test
    fun drawsTheDialogInBothThemes() {
        for (dark in listOf(false, true)) {
            render("restore-project-${if (dark) "dark" else "light"}", 1120, 720, dark = dark) {
                RestoreProjectDialog(saved, onRestore = {}, onFresh = {})
            }
        }
    }
}
