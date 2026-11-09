@file:OptIn(ExperimentalTestApi::class)

package app.katty.screens

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import app.katty.core.LineMatch
import app.katty.core.TerminalId
import app.katty.terminals.AllTerminalsResults
import app.katty.terminals.TerminalMatches
import app.katty.ui.KattyTheme
import app.katty.ui.render
import kotlinx.coroutines.flow.flowOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SearchAllPopupTest {
    private val a = TerminalId("a")
    private val b = TerminalId("b")
    private val gone = TerminalId("gone")

    // What a search of "needle" finds: two rows of a, one of b, and one of a terminal that is gone meanwhile.
    private fun found(query: String) = AllTerminalsResults(
        query,
        listOf(
            TerminalMatches(a, listOf(LineMatch(3, "NEEDLE again", listOf(0..5)), LineMatch(1, "needle here", listOf(0..5))), more = false),
            TerminalMatches(gone, listOf(LineMatch(0, "needle of a closed terminal", listOf(0..5))), more = false),
            TerminalMatches(b, listOf(LineMatch(7, "the needle of b", listOf(4..9))), more = true),
        ),
    )

    private val searched = mutableListOf<String>()
    private val search = { query: String -> searched += query; flowOf(if (query.isEmpty()) AllTerminalsResults() else found(query)) }
    private val terminalOf = { id: TerminalId ->
        when (id) {
            a -> SearchedTerminal("zsh", "api / main")
            b -> SearchedTerminal("Claude Code", "web / feature")
            else -> null
        }
    }

    @Test
    fun whatIsTypedIsSearchedAndEnterGoesToTheSelectedRow() = runComposeUiTest {
        val opened = mutableListOf<Triple<TerminalId, Long, String>>()
        setContent { KattyTheme(false) { SearchAllPopup(search, terminalOf, onOpen = { t, r, q -> opened += Triple(t, r, q) }, onDismiss = {}) } }

        onNodeWithText("Type a text to find it in the history of every terminal.").assertExists()
        onNode(hasSetTextAction()).performTextInput("needle")
        waitUntil(timeoutMillis = 10_000) { onAllNodes(androidx.compose.ui.test.hasContentDescription("NEEDLE again")).fetchSemanticsNodes().isNotEmpty() }

        onNodeWithText("3+ results in 2 terminals").assertExists("The terminal that is gone is not counted")
        onNodeWithText("api / main").assertExists()
        onNodeWithText("1+").assertExists()
        assertTrue(onAllNodes(androidx.compose.ui.test.hasContentDescription("needle of a closed terminal")).fetchSemanticsNodes().isEmpty(), "A terminal that is gone is not listed")
        onNode(hasSetTextAction()).performKeyInput { pressKey(Key.DirectionDown); pressKey(Key.Enter) }

        assertEquals(listOf(Triple(a, 1L, "needle")), opened, "The second row, which the arrow selected")
        assertEquals("needle", searched.last())
    }

    @Test
    fun aClickGoesToTheRowAndEscapeCloses() = runComposeUiTest {
        val opened = mutableListOf<TerminalId>()
        var dismissed = false
        setContent { KattyTheme(false) { SearchAllPopup(search, terminalOf, onOpen = { t, _, _ -> opened += t }, onDismiss = { dismissed = true }) } }
        onNode(hasSetTextAction()).performTextInput("needle")
        waitUntil(timeoutMillis = 10_000) { onAllNodes(androidx.compose.ui.test.hasContentDescription("the needle of b")).fetchSemanticsNodes().isNotEmpty() }

        onNodeWithContentDescription("the needle of b").performClick()
        assertEquals(listOf(b), opened)

        onNode(hasSetTextAction()).performKeyInput { pressKey(Key.Escape) }
        assertTrue(dismissed)
    }

    @Test
    fun saysHowManyRowsInHowManyTerminals() {
        assertEquals("", AllTerminalsResults().summary())
        assertEquals("Searching…", AllTerminalsResults("x", searching = true).summary())
        assertEquals("No results", AllTerminalsResults("x").summary())
        val one = TerminalMatches(a, listOf(LineMatch(0, "x", listOf(0..0))), more = false)
        assertEquals("1 result in 1 terminal", AllTerminalsResults("x", listOf(one)).summary())
        assertEquals("2 results in 2 terminals · Searching…", AllTerminalsResults("x", listOf(one, one.copy(terminal = b)), searching = true).summary())
        assertEquals("1+ results in 1 terminal", AllTerminalsResults("x", listOf(one), capped = true).summary())
    }

    @Test
    fun aLongRowStartsALittleBeforeWhatWasFound() {
        val line = "a".repeat(50) + "needle" + " end"
        val shown = excerpt(LineMatch(0, line, listOf(50..55)), Color.Red)

        assertEquals("…" + "a".repeat(24) + "needle end", shown.text)
        assertEquals(listOf(25..30), shown.spanStyles.map { it.start until it.end })
        assertEquals("short needle", excerpt(LineMatch(0, "short needle", listOf(6..11)), Color.Red).text, "A row whose match is near its start is as it is")
    }

    @Test
    fun drawsTheRowsUnderTheirTerminalInBothThemes() {
        for (dark in listOf(false, true)) {
            val rendered = render("search-all-${if (dark) "dark" else "light"}", 1440, 900, dark = dark) {
                SearchAllPopup(search, terminalOf, onOpen = { _, _, _ -> }, onDismiss = {}, initialQuery = "needle", initialResults = found("needle"))
            }
            // The first row is selected.
            assertTrue(rendered.count(if (dark) 0x2E436E else 0xD4E2FF) > 1000)
        }
    }
}
