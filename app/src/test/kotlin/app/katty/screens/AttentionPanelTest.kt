@file:OptIn(ExperimentalTestApi::class)

package app.katty.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.moveTo
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
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
import app.katty.core.FinishedCommand
import app.katty.core.KattyStore
import app.katty.core.attention
import app.katty.core.TerminalId
import app.katty.terminal.TerminalCommandOutcome
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

    // A command that ended in a terminal of its own workspace, [agoMinutes] ago, after [durationMs].
    private fun command(project: String, workspace: String, line: String?, exitCode: Int, agoMinutes: Long, durationMs: Long): AttentionItem {
        val directory = "/dev/$project"
        val projectId = store.state.value.projects.firstOrNull { it.directory == directory }?.id ?: store.addProject(directory, project)
        store.addWorkspace(projectId, workspace)!!
        val terminal = store.state.value.selection.terminal!!
        val outcome = if (exitCode == 0) TerminalCommandOutcome.SUCCEEDED else TerminalCommandOutcome.FAILED
        val command = FinishedCommand(line, outcome, exitCode, durationMs, now - agoMinutes * 60_000)
        return store.state.value.attention(emptyMap(), mapOf(terminal to command)).finished.single()
    }

    private fun commands(): Attention {
        val failed = command("api-gateway", "migrations", "./gradlew test --tests '*Migration*'", 1, 1, 192_000)
        val succeeded = command("web-client", "checkout-form", "npm run build", 0, 3, 42_000)
        val unknown = command("docs-site", "getting-started", null, 0, 9, 400)
        return Attention(needsYou = emptyList(), finished = listOf(failed, succeeded, unknown))
    }

    private fun attention(): Attention {
        val codex = item("api-gateway", "refactor-auth", AgentState.NEEDS_INPUT, "codex", 2, "Apply this patch? 1. Yes  2. No, tell me what to change")
        val claude = item("web-client", "checkout-form", AgentState.ERROR, "claude-code", 14, "Failed: build error in CartSummary.tsx.")
        val done = item("api-gateway", "release-notes", AgentState.DONE, "claude-code", 8, "Changelog for v2.4 drafted and committed.")
        val copilot = item("docs-site", "getting-started", AgentState.DONE, "copilot-cli", 21, "Rewrote the install guide, 3 files changed.")
        return Attention(needsYou = listOf(codex, claude), finished = listOf(done, copilot))
    }

    private fun ComposeUiTest.press(key: Key) {
        onNodeWithContentDescription("Terminals needing attention").performKeyInput { pressKey(key) }
    }

    @Test
    fun theFirstRowIsSelectedAndEnterGoesToItsTerminal() = runComposeUiTest {
        val attention = attention()
        val opened = mutableListOf<TerminalId>()
        setContent { KattyTheme(false) { AttentionPopover(attention, now, onOpen = { opened += it }, onMarkAllSeen = {}, onJumpToNext = {}, onDismiss = {}) } }
        waitForIdle()

        press(Key.Enter)

        assertEquals(listOf(attention.needsYou[0].terminal.id), opened)
    }

    @Test
    fun theArrowsMoveThroughTheAgentsThatNeedTheUserAndThroughTheFinishedOnes() = runComposeUiTest {
        val attention = attention()
        val opened = mutableListOf<TerminalId>()
        setContent { KattyTheme(false) { AttentionPopover(attention, now, onOpen = { opened += it }, onMarkAllSeen = {}, onJumpToNext = {}, onDismiss = {}) } }
        waitForIdle()

        // The selection stays where it was between one Enter and the next: row 1, then 3, then back to 2.
        press(Key.DirectionDown); press(Key.Enter)
        press(Key.DirectionDown); press(Key.DirectionDown); press(Key.Enter)
        press(Key.DirectionUp); press(Key.Enter)

        assertEquals(listOf(attention.needsYou[1], attention.finished[1], attention.finished[0]).map { it.terminal.id }, opened)
    }

    @Test
    fun theSelectionGoesRoundTheList() = runComposeUiTest {
        val attention = attention()
        val opened = mutableListOf<TerminalId>()
        setContent { KattyTheme(false) { AttentionPopover(attention, now, onOpen = { opened += it }, onMarkAllSeen = {}, onJumpToNext = {}, onDismiss = {}) } }
        waitForIdle()

        press(Key.DirectionUp); press(Key.Enter)

        assertEquals(listOf(attention.finished.last().terminal.id), opened)
    }

    @Test
    fun theSelectedRowIsMarked() = runComposeUiTest {
        val attention = attention()
        setContent { KattyTheme(false) { AttentionPopover(attention, now, onOpen = {}, onMarkAllSeen = {}, onJumpToNext = {}, onDismiss = {}) } }
        waitForIdle()

        onNodeWithContentDescription("Codex in api-gateway / refactor-auth").assertIsSelected()
        press(Key.DirectionDown)
        waitForIdle()

        onNodeWithContentDescription("Codex in api-gateway / refactor-auth").assertIsNotSelected()
        onNodeWithContentDescription("Claude Code in web-client / checkout-form").assertIsSelected()
    }

    @Test
    fun escapeClosesTheList() = runComposeUiTest {
        var dismissed = false
        setContent { KattyTheme(false) { AttentionPopover(attention(), now, onOpen = {}, onMarkAllSeen = {}, onJumpToNext = {}, onDismiss = { dismissed = true }) } }
        waitForIdle()

        press(Key.Escape)

        assertTrue(dismissed)
    }

    @Test
    fun withNothingToOpenEnterDoesNothing() = runComposeUiTest {
        val opened = mutableListOf<TerminalId>()
        setContent { KattyTheme(false) { AttentionPopover(Attention(emptyList(), emptyList()), now, onOpen = { opened += it }, onMarkAllSeen = {}, onJumpToNext = {}, onDismiss = {}) } }
        waitForIdle()

        press(Key.DirectionDown); press(Key.Enter)

        assertEquals(emptyList(), opened)
    }

    @Test
    fun theBellShowsHowManyAgentsNeedTheUser() = runComposeUiTest {
        setContent { KattyTheme(false) { AttentionBell(2, onClick = {}) } }

        onNodeWithContentDescription("Terminals needing attention: 2").assertExists()
        onNodeWithText("2").assertExists()
    }

    @Test
    fun theBellIsAmberWhileOneWaitsAndGreenWhenTheyOnlyFinished() {
        for (dark in listOf(false, true)) {
            val theme = if (dark) "dark" else "light"
            val (amber, green) = if (dark) 0xE5A93D to 0x6CC17D else 0xA85F00 to 0x1F7A3A
            val waiting = render("bell-waiting-$theme", 120, 80, dark = dark) { AttentionBell(3, onClick = {}, waiting = true) }
            val finished = render("bell-finished-$theme", 120, 80, dark = dark) { AttentionBell(2, onClick = {}, waiting = false) }

            assertTrue(waiting.count(amber) > 20 && waiting.count(green) == 0, "Amber while one waits ($theme)")
            assertTrue(finished.count(green) > 20 && finished.count(amber) == 0, "Green when they only finished ($theme)")
        }
    }

    @Test
    fun theSectionOfWhoNeedsTheUserCountsOnlyTheOnesThatWait() = runComposeUiTest {
        val list = attention()
        setContent { KattyTheme(false) { AttentionPopover(list, now, onOpen = {}, onMarkAllSeen = {}, onJumpToNext = {}, onDismiss = {}) } }

        assertEquals(4, list.count, "The bell counts the ones that finished too")
        onNodeWithText("${list.needsYou.size}").assertExists()
        assertEquals(0, onAllNodesWithText("4").fetchSemanticsNodes().size)
    }

    @Test
    fun theTooltipOfTheBellSaysItsShortcut() = runComposeUiTest {
        setContent { KattyTheme(false) { AttentionBell(2, onClick = {}) } }

        onNodeWithContentDescription("Terminals needing attention: 2").performMouseInput { moveTo(center) }

        waitUntilAtLeastOneExists(hasText("Terminals needing attention: 2 (⌘J)"), timeoutMillis = 5_000)
    }

    @Test
    fun theBellShowsNoNumberWhenNobodyNeedsTheUser() = runComposeUiTest {
        setContent { KattyTheme(false) { AttentionBell(0, onClick = {}) } }

        onNodeWithContentDescription("Terminals needing attention").assertExists()
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
        onNodeWithText("Jump to next terminal that needs you").assertExists()
    }

    @Test
    fun aClickOnAnItemOpensItsTerminal() = runComposeUiTest {
        val list = attention()
        val opened = mutableListOf<TerminalId>()
        setContent { KattyTheme(false) { AttentionPopover(list, now, onOpen = { opened += it }, onMarkAllSeen = {}, onJumpToNext = {}, onDismiss = {}) } }

        onNodeWithContentDescription("Codex in api-gateway / refactor-auth").performClick()
        onNodeWithContentDescription("Copilot CLI in docs-site / getting-started").performClick()

        assertEquals(listOf(list.needsYou[0].terminal.id, list.finished[1].terminal.id), opened)
    }

    @Test
    fun markAllSeenAndJumpAreButtons() = runComposeUiTest {
        val calls = mutableListOf<String>()
        setContent { KattyTheme(false) { AttentionPopover(attention(), now, onOpen = {}, onMarkAllSeen = { calls += "seen" }, onJumpToNext = { calls += "jump" }, onDismiss = {}) } }

        onNodeWithText("Mark all seen").performClick()
        onNodeWithText("Jump to next terminal that needs you").performClick()

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
    fun aCommandThatEndedSaysItsLineHowItWentAndHowLongItRan() = runComposeUiTest {
        val list = commands()
        val opened = mutableListOf<TerminalId>()
        setContent { KattyTheme(false) { AttentionPopover(list, now, onOpen = { opened += it }, onMarkAllSeen = {}, onJumpToNext = {}, onDismiss = {}) } }

        onNodeWithText("Nothing needs you.").assertExists()
        onNodeWithText("FINISHED").assertExists()
        onNodeWithText("./gradlew test --tests '*Migration*'").assertExists()
        onNodeWithText("Exit 1 · 3 min 12 s").assertExists()
        onNodeWithText("npm run build").assertExists()
        onNodeWithText("Succeeded · 42 s").assertExists()
        onNodeWithText("3 min").assertExists()
        // A shell that does not mark where the command line starts (fish) gives no line.
        onNodeWithText("Command").assertExists()
        onNodeWithText("Succeeded · 0.4 s").assertExists()
        onNodeWithContentDescription("npm run build in web-client / checkout-form").performClick()

        assertEquals(listOf(list.finished[1].terminal.id), opened)
    }

    @Test
    fun theNoticeOfACommandThatEnded() = runComposeUiTest {
        val list = commands()
        setContent { KattyTheme(false) { androidx.compose.foundation.layout.Column { AgentToast(list.finished[0], now, {}); AgentToast(list.finished[1], now, {}) } } }

        onNodeWithText("Failed: ./gradlew test --tests '*Migration*'").assertExists()
        onNodeWithText("Exit 1 · 3 min 12 s").assertExists()
        onNodeWithText("Finished: npm run build").assertExists()
        onNodeWithText("web-client / checkout-form").assertExists()
    }

    @Test
    fun drawsTheCommandsThatEndedInBothThemes() {
        for (dark in listOf(false, true)) {
            val list = commands()
            val theme = if (dark) "dark" else "light"
            val rendered = render("attention-commands-$theme", 1000, 700, dark = dark) {
                Box(Modifier.fillMaxSize().padding(8.dp)) {
                    AttentionPopover(list, now, onOpen = {}, onMarkAllSeen = {}, onJumpToNext = {}, onDismiss = {})
                }
            }
            // The red of the command that failed, and the green of the ones that succeeded.
            assertTrue(rendered.count(if (dark) 0xF2767A else 0xC93234) > 50)
            assertTrue(rendered.count(if (dark) 0x6CC17D else 0x1F7A3A) > 50)
            // The notice on its own: the list, a popup, is drawn over what is under it.
            render("attention-command-notice-$theme", 760, 280, dark = dark) {
                Box(Modifier.fillMaxSize().padding(16.dp)) { AgentToast(list.finished[0], now, onClick = {}) }
            }
        }
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
