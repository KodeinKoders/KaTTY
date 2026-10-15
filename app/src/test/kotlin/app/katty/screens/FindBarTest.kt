@file:OptIn(ExperimentalTestApi::class)

package app.katty.screens

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import app.katty.terminal.TerminalSearchState
import app.katty.terminal.TerminalSession
import app.katty.ui.KattyTheme
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class FindBarTest {
    private val sessions = mutableListOf<TerminalSession>()

    @AfterTest
    fun close() = sessions.forEach { it.close() }

    private fun session(output: String): TerminalSession =
        TerminalSession.start(40, 6, command = listOf("/bin/sh", "-c", "printf '$output'; sleep 60")).also { sessions += it }

    @Test
    fun saysHowManyMatchesThereAreAndWhichOneIsSelected() {
        assertEquals("", TerminalSearchState().summary())
        assertEquals("No results", TerminalSearchState(query = "x").summary())
        assertEquals("Searching…", TerminalSearchState(query = "x", searching = true).summary())
        assertEquals("1 result", TerminalSearchState(query = "x", total = 1).summary())
        assertEquals("7 results", TerminalSearchState(query = "x", total = 7).summary())
        assertEquals("2 of 7", TerminalSearchState(query = "x", total = 7, selected = 2).summary())
    }

    @Test
    fun searchesWhatIsTypedAndMovesFromMatchToMatch() = runComposeUiTest {
        val session = session("foo bar foo\\r\\nfoo")
        setContent { KattyTheme(false) { FindBar(session, onClose = {}) } }

        onNodeWithText("Find").assertExists()
        onNodeWithContentDescription("Find in terminal").assertExists()
        // Typed in the field: the search follows.
        onNode(hasSetTextAction()).performTextInput("foo")
        waitUntil(timeoutMillis = 10_000) { session.search.state.value.total == 3 }
        waitForIdle()
        onNodeWithText("3 results").assertExists()

        onNodeWithContentDescription("Next match").performClick()
        waitUntil(timeoutMillis = 10_000) { session.search.state.value.selected != null }
        waitForIdle()
        onNodeWithText("1 of 3").assertExists()
        onNodeWithContentDescription("Next match").performClick()
        waitUntil(timeoutMillis = 10_000) { session.search.state.value.selected == 2 }
        waitForIdle()
        onNodeWithText("2 of 3").assertExists()
        onNodeWithContentDescription("Previous match").performClick()
        waitUntil(timeoutMillis = 10_000) { session.search.state.value.selected == 1 }
    }

    @Test
    fun closingTakesTheMatchesOffTheScreen() = runComposeUiTest {
        val session = session("foo")
        var showing = androidx.compose.runtime.mutableStateOf(true)
        setContent { KattyTheme(false) { if (showing.value) FindBar(session, onClose = { showing.value = false }) } }
        onNode(hasSetTextAction()).performTextInput("foo")
        waitUntil(timeoutMillis = 10_000) { session.search.state.value.total == 1 }

        onNodeWithContentDescription("Close find").performClick()
        waitForIdle()

        waitUntil(timeoutMillis = 10_000) { session.search.state.value.query.isEmpty() }
    }
}
