@file:OptIn(ExperimentalTestApi::class)

package app.katty

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import app.katty.core.AgentInfo
import app.katty.core.AgentReport
import app.katty.core.AgentState
import app.katty.core.KattyStore
import app.katty.core.TerminalId
import app.katty.ui.KattyTheme
import kotlin.test.Test
import kotlin.test.assertEquals

class BoardWindowContentTest {
    private var counter = 0
    private val store = KattyStore(newId = { "id${counter++}" })
    private val state get() = store.state.value
    private val now = 10_000_000_000L

    private fun agent(app: String, state: AgentState = AgentState.WORKING) = AgentInfo(AgentReport(state, app, "On it."), now - 60_000)

    @Test
    fun itShowsOnlyTheBoardWithItsOwnFilterAndAClickedCardIsPassedOn() = runComposeUiTest {
        store.addProject("/dev/api-gateway")
        val first = state.selection.terminal!!
        store.addProject("/dev/web-client")
        val second = state.selection.terminal!!
        val infos = mapOf(first to agent("claude-code"), second to agent("codex"))
        val opened = mutableListOf<TerminalId>()
        setContent { KattyTheme(false) { BoardWindowContent(state, infos, emptyMap(), now, false, onOpen = { opened += it }) } }

        // Both projects, and no terminal anywhere: no handle was needed.
        onNodeWithContentDescription("All projects").assertExists()
        onNodeWithContentDescription("Claude Code in api-gateway / api-gateway").assertExists()
        onNodeWithContentDescription("Codex in web-client / web-client").assertExists()
        onNodeWithContentDescription("Open the board in a window").assertDoesNotExist()

        // The filter is its own.
        onNodeWithContentDescription("web-client").performClick()
        waitForIdle()
        onNodeWithContentDescription("Claude Code in api-gateway / api-gateway").assertDoesNotExist()

        onNodeWithContentDescription("Codex in web-client / web-client").performClick()
        assertEquals(listOf(second), opened)
    }

    @Test
    fun aProjectThatIsRemovedWhileItIsTheFilterGoesBackToAll() = runComposeUiTest {
        val a = store.addProject("/dev/a")
        val inA = state.selection.terminal!!
        store.addProject("/dev/b")
        val inB = state.selection.terminal!!
        val infos = mapOf(inA to agent("claude-code"), inB to agent("codex"))
        setContent {
            KattyTheme(false) {
                val current by store.state.collectAsState()
                BoardWindowContent(current, infos, emptyMap(), now, false, onOpen = {})
            }
        }
        onNodeWithContentDescription("a").performClick()
        waitForIdle()
        onNodeWithContentDescription("Codex in b / b").assertDoesNotExist()

        store.removeProject(a)
        waitForIdle()

        onNodeWithContentDescription("Codex in b / b").assertExists()
    }
}
