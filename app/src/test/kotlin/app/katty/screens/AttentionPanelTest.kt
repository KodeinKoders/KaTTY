@file:OptIn(ExperimentalTestApi::class)

package app.katty.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import app.katty.core.AgentInfo
import app.katty.core.AgentReport
import app.katty.core.AgentState
import app.katty.core.Attention
import app.katty.core.AttentionItem
import app.katty.core.KattyStore
import app.katty.core.attention
import app.katty.core.TerminalId
import app.katty.ui.KattyTheme
import app.katty.ui.render
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AttentionPanelTest {
    private val now = 10_000_000_000L
    private var counter = 0
    private val store = KattyStore(newId = { "id${counter++}" })

    private fun item(project: String, workspace: String, state: AgentState, app: String, agoMinutes: Long, note: String?): AttentionItem {
        val directory = "/dev/$project"
        val projectId = store.state.value.projects.firstOrNull { it.directory == directory }?.id ?: store.addProject(directory, project)
        val workspaceId = store.addWorkspace(projectId, workspace)!!
        val terminal = store.state.value.selection.terminal!!
        val info = AgentInfo(AgentReport(state, app, note), now - agoMinutes * 60_000)
        return store.state.value.attention(mapOf(terminal to info)).let { (it.needsYou + it.finished).first { item -> item.workspace.id == workspaceId } }
    }

    private fun attention(): Attention {
        val codex = item("api-gateway", "refactor-auth", AgentState.NEEDS_INPUT, "codex", 2, "Apply this patch? 1. Yes  2. No, tell me what to change")
        val claude = item("web-client", "checkout-form", AgentState.ERROR, "claude-code", 14, "Failed: build error in CartSummary.tsx.")
        val done = item("api-gateway", "release-notes", AgentState.DONE, "claude-code", 8, "Changelog for v2.4 drafted and committed.")
        val gemini = item("docs-site", "getting-started", AgentState.DONE, "gemini-cli", 21, "Rewrote the install guide, 3 files changed.")
        return Attention(needsYou = listOf(codex, claude), finished = listOf(done, gemini))
    }

    @Test
    fun theBellShowsHowManyAgentsNeedTheUser() = runComposeUiTest {
        setContent { KattyTheme(false) { AttentionBell(2, onClick = {}) } }

        onNodeWithContentDescription("Agents needing attention: 2").assertExists()
        onNodeWithText("2").assertExists()
    }

    @Test
    fun theBellShowsNoNumberWhenNobodyNeedsTheUser() = runComposeUiTest {
        setContent { KattyTheme(false) { AttentionBell(0, onClick = {}) } }

        onNodeWithContentDescription("Agents needing attention").assertExists()
        assertEquals(0, onAllNodesWithText("0").fetchSemanticsNodes().size)
    }

    @Test
    fun theListHasWhoNeedsTheUserAndWhoFinishedWithTheirAges() = runComposeUiTest {
        setContent { KattyTheme(false) { AttentionPopover(attention(), now, onOpen = {}, onMarkAllSeen = {}, onJumpToNext = {}, onDismiss = {}) } }

        onNodeWithText("NEEDS YOU").assertExists()
        onNodeWithText("FINISHED").assertExists()
        onNodeWithText("2 min").assertExists()
        onNodeWithText("14 min").assertExists()
        onNodeWithText("8 min").assertExists()
        onNodeWithText("21 min").assertExists()
        onNodeWithText("api-gateway / refactor-auth").assertExists()
        onNodeWithText("Failed: build error in CartSummary.tsx.").assertExists()
        onNodeWithText("Jump to next waiting agent").assertExists()
    }

    @Test
    fun aClickOnAnItemOpensItsTerminal() = runComposeUiTest {
        val list = attention()
        val opened = mutableListOf<TerminalId>()
        setContent { KattyTheme(false) { AttentionPopover(list, now, onOpen = { opened += it }, onMarkAllSeen = {}, onJumpToNext = {}, onDismiss = {}) } }

        onNodeWithContentDescription("Codex in api-gateway / refactor-auth").performClick()
        onNodeWithContentDescription("Gemini CLI in docs-site / getting-started").performClick()

        assertEquals(listOf(list.needsYou[0].terminal.id, list.finished[1].terminal.id), opened)
    }

    @Test
    fun markAllSeenAndJumpAreButtons() = runComposeUiTest {
        val calls = mutableListOf<String>()
        setContent { KattyTheme(false) { AttentionPopover(attention(), now, onOpen = {}, onMarkAllSeen = { calls += "seen" }, onJumpToNext = { calls += "jump" }, onDismiss = {}) } }

        onNodeWithText("Mark all seen").performClick()
        onNodeWithText("Jump to next waiting agent").performClick()

        assertEquals(listOf("seen", "jump"), calls)
    }

    @Test
    fun saysWhenNothingNeedsTheUser() = runComposeUiTest {
        setContent { KattyTheme(false) { AttentionPopover(Attention(emptyList(), emptyList()), now, onOpen = {}, onMarkAllSeen = {}, onJumpToNext = {}, onDismiss = {}) } }

        onNodeWithText("Nothing needs you.").assertExists()
        assertEquals(0, onAllNodesWithText("FINISHED").fetchSemanticsNodes().size)
    }

    @Test
    fun theNoticeSaysWhoNeedsInputWhereAndWhat() = runComposeUiTest {
        val clicks = mutableListOf<String>()
        setContent { KattyTheme(false) { AgentToast(attention().needsYou[0], now, onClick = { clicks += "open" }) } }

        onNodeWithText("Codex needs input").assertExists()
        onNodeWithText("api-gateway / refactor-auth").assertExists()
        onNodeWithText("2 min").assertExists()
        onNodeWithContentDescription("Codex needs input").performClick()

        assertEquals(listOf("open"), clicks)
    }

    @Test
    fun theNoticeOfAFailureAndOfAFinishedAgent() = runComposeUiTest {
        val list = attention()
        setContent { KattyTheme(false) { androidx.compose.foundation.layout.Column { AgentToast(list.needsYou[1], now, {}); AgentToast(list.finished[0], now, {}) } } }

        onNodeWithText("Claude Code failed").assertExists()
        onNodeWithText("Claude Code finished").assertExists()
    }

    @Test
    fun drawsTheListAndTheNoticeInBothThemes() {
        for (dark in listOf(false, true)) {
            val list = attention()
            val rendered = render("attention-${if (dark) "dark" else "light"}", 1000, 1100, dark = dark) {
                Box(Modifier.fillMaxSize().padding(8.dp)) {
                    AttentionPopover(list, now, onOpen = {}, onMarkAllSeen = {}, onJumpToNext = {}, onDismiss = {})
                    AgentToast(list.needsYou[0], now, onClick = {}, modifier = Modifier.padding(top = 900.dp))
                }
            }
            // The amber of what needs the user is the bell's count, the glyph and the pill.
            assertTrue(rendered.count(if (dark) 0xE5A93D else 0xA85F00) > 100)
        }
    }
}
