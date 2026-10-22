@file:OptIn(ExperimentalTestApi::class)

package app.katty.screens

import androidx.compose.ui.input.key.Key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.test.pressKey
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
    fun aProgramThatRunsIsSaidAsWellAsTheAgents() {
        val vim = TerminalSummary(TerminalId("v"), "vim", AgentGlyph.SHELL, AgentState.IDLE, running = true)

        assertEquals("1 program is still running and will be stopped.", stoppedByClosing(listOf(vim, terminals[2])))
        assertEquals("2 agents are still active and 1 program is still running: they will be stopped.", stoppedByClosing(terminals + vim))
        // An agent runs too: it is said once, as an agent.
        assertEquals("1 agent is still active and will be stopped.", stoppedByClosing(listOf(terminals[0].copy(running = true))))
    }

    @Test
    fun theButtonsConfirmAndCancel() = runComposeUiTest {
        val answers = mutableListOf<String>()
        setContent { KattyTheme(false) { CloseWorkspaceDialog("ws", terminals, onConfirm = { answers += "confirm" }, onCancel = { answers += "cancel" }) } }

        onNodeWithText("Cancel").performClick()
        onNodeWithText("Close workspace").performClick()

        assertEquals(listOf("cancel", "confirm"), answers)
    }

    private fun androidx.compose.ui.test.ComposeUiTest.dialog(answers: MutableList<String>) =
        setContent { KattyTheme(false) { CloseWorkspaceDialog("ws", terminals, onConfirm = { answers += "confirm" }, onCancel = { answers += "cancel" }) } }

    @Test
    fun theKeyboardIsOnTheRedButtonSoEnterConfirms() = runComposeUiTest {
        val answers = mutableListOf<String>()
        dialog(answers)
        waitForIdle()

        onNodeWithText("Close workspace").performKeyInput { pressKey(Key.Enter) }

        assertEquals(listOf("confirm"), answers)
    }

    @Test
    fun shiftTabGoesToCancelAndEnterPressesIt() = runComposeUiTest {
        val answers = mutableListOf<String>()
        dialog(answers)
        waitForIdle()

        onNodeWithText("Close workspace").performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.Tab) } }
        onNodeWithText("Cancel").performKeyInput { pressKey(Key.Enter) }

        assertEquals(listOf("cancel"), answers)
    }

    @Test
    fun closingAndRestartingATerminalSayWhatIsStopped() = runComposeUiTest {
        val answers = mutableListOf<String>()
        var restart by androidx.compose.runtime.mutableStateOf(false)
        setContent {
            KattyTheme(false) {
                if (restart) RestartTerminalDialog(terminals[0], onConfirm = { answers += "restart" }, onCancel = {})
                else CloseTerminalDialog(terminals[0], onConfirm = { answers += "close" }, onCancel = {})
            }
        }
        onNodeWithText("Close the terminal?").assertExists()
        onNodeWithText("“Claude Code” is still running in it and will be stopped.").assertExists()
        onNodeWithText("Close terminal").performClick()

        restart = true
        waitForIdle()
        onNodeWithText("Restart the terminal?").assertExists()
        onNodeWithText("Restart terminal").performClick()

        assertEquals(listOf("close", "restart"), answers)
    }

    @Test
    fun commandAndBackspaceConfirmAtOnceAndEscapeCancels() = runComposeUiTest {
        val answers = mutableListOf<String>()
        dialog(answers)
        waitForIdle()

        onNodeWithText("Close workspace").performKeyInput { withKeyDown(Key.MetaLeft) { pressKey(Key.Backspace) } }
        onNodeWithText("Close workspace").performKeyInput { pressKey(Key.Escape) }

        assertEquals(listOf("confirm", "cancel"), answers)
    }

    @Test
    fun theLastTerminalDialogSaysThatTheWorkspaceIsDeleted() = runComposeUiTest {
        val answers = mutableListOf<String>()
        setContent { KattyTheme(false) { CloseLastTerminalDialog("refactor-auth", terminals[0], onConfirm = { answers += "confirm" }, onCancel = {}) } }

        onNodeWithText("Close the last terminal?").assertExists()
        onNodeWithText("This is the only terminal in “refactor-auth”. Closing it deletes the workspace and stops “Claude Code”, which is still running.").assertExists()
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
