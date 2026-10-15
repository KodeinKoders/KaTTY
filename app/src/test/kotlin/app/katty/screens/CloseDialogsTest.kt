@file:OptIn(ExperimentalTestApi::class)

package app.katty.screens

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import app.katty.core.AgentState
import app.katty.core.TerminalId
import app.katty.ui.AgentGlyph
import app.katty.ui.KattyTheme
import app.katty.ui.render
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CloseDialogsTest {
    private fun summary(id: String, title: String, glyph: AgentGlyph, state: AgentState) = TerminalSummary(TerminalId(id), title, glyph, state)

    private val terminals = listOf(
        summary("a", "Claude Code", AgentGlyph.CLAUDE, AgentState.WORKING),
        summary("b", "Codex", AgentGlyph.CODEX, AgentState.NEEDS_INPUT),
        summary("c", "zsh", AgentGlyph.SHELL, AgentState.IDLE),
    )

    @Test
    fun closingAWorkspaceListsItsTerminalsAndCountsTheActiveAgents() = runComposeUiTest {
        setContent { KattyTheme(false) { CloseWorkspaceDialog("refactor-auth", terminals, onConfirm = {}, onCancel = {}) } }

        onNodeWithText("Close “refactor-auth”?").assertExists()
        onNodeWithText("Closing this workspace ends all of its terminals. 2 agents are still active and will be stopped.").assertExists()
        onNodeWithText("Claude Code").assertExists()
        onNodeWithText("Working").assertExists()
        onNodeWithText("Needs input").assertExists()
        onNodeWithText("zsh").assertExists()
    }

    @Test
    fun oneActiveAgentIsSaidInTheSingular() = runComposeUiTest {
        setContent { KattyTheme(false) { CloseWorkspaceDialog("ws", terminals.take(1), onConfirm = {}, onCancel = {}) } }

        onNodeWithText("Closing this workspace ends all of its terminals. 1 agent is still active and will be stopped.").assertExists()
    }

    @Test
    fun theButtonsConfirmAndCancel() = runComposeUiTest {
        val answers = mutableListOf<String>()
        setContent { KattyTheme(false) { CloseWorkspaceDialog("ws", terminals, onConfirm = { answers += "confirm" }, onCancel = { answers += "cancel" }) } }

        onNodeWithText("Cancel").performClick()
        onNodeWithText("Close workspace").performClick()

        assertEquals(listOf("cancel", "confirm"), answers)
    }

    @Test
    fun theLastTerminalDialogSaysThatTheWorkspaceIsDeleted() = runComposeUiTest {
        val answers = mutableListOf<String>()
        setContent { KattyTheme(false) { CloseLastTerminalDialog("refactor-auth", terminals[0], onConfirm = { answers += "confirm" }, onCancel = {}) } }

        onNodeWithText("Close the last terminal?").assertExists()
        onNodeWithText("This is the only terminal in “refactor-auth”. Closing it deletes the workspace and stops the agent that is still working.").assertExists()
        onNodeWithText("Delete workspace").performClick()

        assertEquals(listOf("confirm"), answers)
    }

    @Test
    fun drawsTheDialogsInBothThemes() {
        for (dark in listOf(false, true)) {
            val rendered = render("close-workspace-${if (dark) "dark" else "light"}", 1120, 720, dark = dark) {
                CloseWorkspaceDialog("refactor-auth", terminals, onConfirm = {}, onCancel = {})
            }
            // The red of what is destroyed is the confirming button.
            assertTrue(rendered.count(if (dark) 0xF2767A else 0xC93234) > 500)
        }
    }
}
