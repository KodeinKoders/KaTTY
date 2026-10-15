@file:OptIn(ExperimentalTestApi::class)

package app.katty.screens

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import app.katty.core.AgentState
import app.katty.ui.AgentGlyph
import app.katty.ui.KattyTheme
import app.katty.ui.render
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EndedStripTest {
    @Test
    fun saysHowTheShellEndedAndOffersToReopenOrCloseIt() = runComposeUiTest {
        val calls = mutableListOf<String>()
        setContent { KattyTheme(false) { EndedStrip(0, onReopen = { calls += "reopen" }, onClose = { calls += "close" }) } }

        onNodeWithText("Shell exited with code 0").assertExists()
        onNodeWithText("Reopen shell").performClick()
        onNodeWithText("Close").performClick()

        assertEquals(listOf("reopen", "close"), calls)
    }

    @Test
    fun theCodeIsTheOneOfTheShell() = runComposeUiTest {
        setContent { KattyTheme(false) { EndedStrip(130, onReopen = {}, onClose = {}) } }

        onNodeWithText("Shell exited with code 130").assertExists()
    }

    @Test
    fun theHeaderOfATerminalThatEndedSaysExited() = runComposeUiTest {
        setContent { KattyTheme(false) { PaneHeader("zsh", AgentGlyph.SHELL, AgentState.IDLE, null, stateLabel = "Exited") } }

        onNodeWithText("Exited").assertExists()
        assertEquals(0, onAllNodesWithText("Idle").fetchSemanticsNodes().size)
    }

    @Test
    fun drawsTheStripInBothThemes() {
        for (dark in listOf(false, true)) {
            val rendered = render("ended-strip-${if (dark) "dark" else "light"}", 1000, 120, dark = dark) { EndedStrip(0, onReopen = {}, onClose = {}) }
            assertTrue(rendered.count(if (dark) 0x27282C else 0xF7F8FA) > 20_000)
        }
    }
}
