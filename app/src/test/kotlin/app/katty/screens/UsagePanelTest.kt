@file:OptIn(ExperimentalTestApi::class)

package app.katty.screens

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsEnabled
import app.katty.core.usageKey
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import app.katty.core.Agent
import app.katty.core.UsageReading
import app.katty.core.UsageWindow
import app.katty.ui.KattyTheme
import app.katty.ui.render
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UsagePanelTest {
    private val now = Instant.parse("2026-10-15T00:00:00Z")

    private val readings = mapOf(
        Agent.CLAUDE to UsageReading(Agent.CLAUDE, listOf(UsageWindow("Session", 82, now.plusSeconds(53 * 60)), UsageWindow("Week", 40, now.plusSeconds(3 * 86400)))),
        Agent.CODEX to UsageReading(Agent.CODEX, listOf(UsageWindow("Session", 41, now.plusSeconds((2 * 60 + 10) * 60)))),
    )

    private val agents = listOf(Agent.CLAUDE, Agent.CODEX, Agent.GEMINI)

    @Test
    fun theFullListHasThePercentAndWhenItStartsAgainOfTheMostUsedAllowance() = runComposeUiTest {
        setContent { KattyTheme(false) { UsageList(agents, readings, now, UsageVariant.FULL, Modifier.width(240.dp)) } }

        onNodeWithText("Claude Code").assertExists()
        onNodeWithText("82% used").assertExists()
        onNodeWithText("Resets in 53 minutes").assertExists()
        // Both allowances of Claude Code have their bar, not only the most used one.
        assertEquals(2, onAllNodesWithText("Session").fetchSemanticsNodes().size)
        onNodeWithText("Week").assertExists()
        onNodeWithText("40% used").assertExists()
        onNodeWithText("Resets in 3 days").assertExists()
        onNodeWithText("41% used").assertExists()
        onNodeWithText("Resets in 2 hours 10 minutes").assertExists()
    }

    @Test
    fun anAgentWithoutAMeterIsListedAsSuch() = runComposeUiTest {
        setContent { KattyTheme(false) { UsageList(agents, readings, now, UsageVariant.FULL, Modifier.width(240.dp)) } }

        onNodeWithText("Gemini CLI").assertExists()
        onNodeWithText("No meter").assertExists()
    }

    @Test
    fun theCompactListSaysTheSameInItsDescription() = runComposeUiTest {
        setContent { KattyTheme(false) { UsageList(agents, readings, now, UsageVariant.COMPACT, Modifier.width(240.dp)) } }

        onNodeWithText("82%").assertExists()
        onNodeWithText("40%").assertExists()
        onNodeWithContentDescription("Session: 82% used, resets in 53 minutes; Week: 40% used, resets in 3 days").assertExists()
        onNodeWithContentDescription("Gemini CLI: usage not available").assertExists()
    }

    @Test
    fun theRailHasThePercentUnderTheTile() = runComposeUiTest {
        setContent { KattyTheme(false) { UsageList(agents, readings, now, UsageVariant.RAIL) } }

        onNodeWithText("82%").assertExists()
        onNodeWithText("40%").assertExists()
        onNodeWithText("41%").assertExists()
        onNodeWithText("–").assertExists()
    }

    @Test
    fun theSectionSaysWhenNoAgentIsChosen() = runComposeUiTest {
        setContent {
            KattyTheme(false) { UsageSection(agents, readings, shown = emptySet(), compact = false, now = now, onCompact = {}, onShown = { _, _ -> }) }
        }

        onNodeWithText("AGENT USAGE").assertExists()
        onNodeWithText("No agents selected.").assertExists()
    }

    @Test
    fun theBarOfTheVerticalLayoutIsCompactOnlyWhenTheUserChoseCompactMode() = runComposeUiTest {
        var compact by androidx.compose.runtime.mutableStateOf(false)
        setContent {
            KattyTheme(false) { UsageBarRow(agents, readings, shown = setOf("claude"), compact = compact, now = now, onCompact = {}, onShown = { _, _ -> }) }
        }

        onNodeWithText("82% used").assertExists()
        onNodeWithText("Resets in 53 minutes").assertExists()
        compact = true
        waitForIdle()
        onNodeWithText("82%").assertExists()
        onNodeWithText("82% used").assertDoesNotExist()
    }

    @Test
    fun aLimitThatIsHiddenIsNotDrawnAndAnAgentWithNoneLeftSaysSo() = runComposeUiTest {
        setContent {
            KattyTheme(false) {
                Column(Modifier.width(240.dp)) {
                    UsageList(listOf(Agent.CLAUDE), readings, now, UsageVariant.FULL, hidden = setOf(usageKey(Agent.CLAUDE, "Week")))
                    UsageList(listOf(Agent.CODEX), readings, now, UsageVariant.FULL, hidden = setOf(usageKey(Agent.CODEX, "Session")))
                }
            }
        }

        onNodeWithText("82% used").assertExists()
        onNodeWithText("40% used").assertDoesNotExist()
        onNodeWithText("No limit shown").assertExists()
        onNodeWithText("41% used").assertDoesNotExist()
    }

    @Test
    fun theChooserListsTheLimitsOfTheAgentsThatAreChosenAndHidesOrShowsThem() = runComposeUiTest {
        val calls = mutableListOf<String>()
        setContent {
            KattyTheme(false) {
                UsageSection(
                    agents, readings, shown = setOf("claude"), compact = false, now = now, onCompact = {}, onShown = { _, _ -> },
                    hidden = setOf(usageKey(Agent.CLAUDE, "Session")), onHidden = { a, label, hide -> calls += "${a.id} $label $hide" },
                )
            }
        }

        onNodeWithContentDescription("Choose agents to show").performClick()
        // The popover is the last tree: its checkboxes for the two limits of Claude Code, and none under Codex, which is not chosen.
        onAllNodesWithText("Week").onLast().performClick()
        onAllNodesWithText("Session").onLast().performClick()

        assertEquals(listOf("claude Week true", "claude Session false"), calls)
    }

    @Test
    fun theRefreshButtonRefreshesAndIsDisabledWhileTheAgentsAreRead() = runComposeUiTest {
        var refreshes = 0
        var refreshing by androidx.compose.runtime.mutableStateOf(false)
        setContent {
            KattyTheme(false) {
                UsageSection(agents, readings, shown = setOf("claude"), compact = false, now = now, onCompact = {}, onShown = { _, _ -> }, refreshing = refreshing, onRefresh = { refreshes++ })
            }
        }

        onNodeWithContentDescription("Refresh usage").assertIsEnabled().performClick()
        assertEquals(1, refreshes)
        refreshing = true
        waitForIdle()
        onNodeWithContentDescription("Refresh usage").assertIsNotEnabled()
    }

    @Test
    fun withNoAgentChosenThereIsNothingToRefresh() = runComposeUiTest {
        setContent { KattyTheme(false) { UsageSection(agents, readings, shown = emptySet(), compact = false, now = now, onCompact = {}, onShown = { _, _ -> }) } }

        onNodeWithContentDescription("Refresh usage").assertIsNotEnabled()
    }

    @Test
    fun theBarOfTheVerticalLayoutHasItsLabelOnTwoLinesWhenFullAndNoneWhenCompact() = runComposeUiTest {
        var compact by androidx.compose.runtime.mutableStateOf(false)
        setContent {
            KattyTheme(false) { UsageBarRow(agents, readings, shown = setOf("claude"), compact = compact, now = now, onCompact = {}, onShown = { _, _ -> }) }
        }

        onNodeWithText("AGENT").assertExists()
        onNodeWithText("USAGE").assertExists()
        // The menu button, then the refresh one under it.
        val menu = onNodeWithContentDescription("Choose agents to show").fetchSemanticsNode().boundsInRoot
        val refresh = onNodeWithContentDescription("Refresh usage").fetchSemanticsNode().boundsInRoot
        assertTrue(refresh.top >= menu.bottom && refresh.left == menu.left, "$menu $refresh")

        compact = true
        waitForIdle()
        onNodeWithText("AGENT").assertDoesNotExist()
        onNodeWithText("USAGE").assertDoesNotExist()
        onNodeWithText("AGENT USAGE").assertDoesNotExist()
        // Side by side again.
        val menuCompact = onNodeWithContentDescription("Choose agents to show").fetchSemanticsNode().boundsInRoot
        val refreshCompact = onNodeWithContentDescription("Refresh usage").fetchSemanticsNode().boundsInRoot
        assertTrue(refreshCompact.top == menuCompact.top, "$menuCompact $refreshCompact")
    }

    @Test
    fun theChooserOffersTheAgentsThatAreInstalledAndCompactMode() = runComposeUiTest {
        val calls = mutableListOf<String>()
        setContent {
            KattyTheme(false) {
                UsageSection(agents, readings, shown = setOf("claude"), compact = false, now = now, onCompact = { calls += "compact $it" }, onShown = { a, on -> calls += "${a.id} $on" })
            }
        }

        onNodeWithContentDescription("Choose agents to show").performClick()
        onNodeWithText("Compact mode").performClick()
        onNodeWithText("Codex").performClick()
        // Claude Code is in the list too, and the chooser is above it in the tree: the last one is the chooser's.
        onAllNodesWithText("Claude Code").onLast().performClick()

        assertEquals(listOf("compact true", "codex true", "claude false"), calls)
        onNodeWithText("Agents are detected from the CLIs installed on this machine.").assertExists()
    }

    @Test
    fun theWelcomeListsTheAgentsThatWereFoundAndFoldsTheOthers() = runComposeUiTest {
        setContent { KattyTheme(false) { DetectedAgents(mapOf(Agent.CLAUDE to Path.of("/bin/claude"), Agent.GEMINI to Path.of("/bin/gemini"))) } }

        onNodeWithText("DETECTED ON THIS MACHINE").assertExists()
        // Two found, and three that were not: the numbers of the two sections.
        onNodeWithText("2").assertExists()
        onNodeWithText("3").assertExists()
        onNodeWithContentDescription("Claude Code: Detected").assertExists()
        onNodeWithContentDescription("Gemini CLI: Detected").assertExists()
        // The others are in a section that is closed: its title says how many, and none of them is listed.
        onNodeWithText("NOT FOUND").assertExists()
        onNodeWithContentDescription("Not found: 3").assertExists()
        assertEquals(0, onAllNodesWithContentDescription("Antigravity: Not found").fetchSemanticsNodes().size)
        assertEquals(0, onAllNodesWithContentDescription("Codex: Not found").fetchSemanticsNodes().size)
        onNodeWithText("Agents you install later appear here automatically.").assertExists()
    }

    @Test
    fun theSectionOfTheAgentsThatWereNotFoundOpensAndCloses() = runComposeUiTest {
        setContent { KattyTheme(false) { DetectedAgents(mapOf(Agent.CLAUDE to Path.of("/bin/claude"))) } }

        onNodeWithContentDescription("Not found: 4").performClick()
        waitForIdle()
        onNodeWithContentDescription("Codex: Not found").assertExists()
        onNodeWithContentDescription("Copilot CLI: Not found").assertExists()
        onNodeWithContentDescription("Not found: 4").performClick()
        waitForIdle()

        assertEquals(0, onAllNodesWithContentDescription("Codex: Not found").fetchSemanticsNodes().size)
    }

    @Test
    fun withEveryAgentFoundThereIsNoSectionOfTheOthers() = runComposeUiTest {
        setContent { KattyTheme(false) { DetectedAgents(Agent.entries.associateWith { Path.of("/bin/${it.executable}") }) } }

        assertEquals(0, onAllNodesWithText("NOT FOUND").fetchSemanticsNodes().size)
    }

    @Test
    fun withNoAgentFoundTheWelcomeSaysSo() = runComposeUiTest {
        setContent { KattyTheme(false) { DetectedAgents(emptyMap()) } }

        onNodeWithText("No agent was found on this machine.").assertExists()
        onNodeWithContentDescription("Not found: 5").assertExists()
    }

    @Test
    fun drawsTheUsageInBothThemes() {
        for (dark in listOf(false, true)) {
            val rendered = render("usage-${if (dark) "dark" else "light"}", 1100, 520, dark = dark) {
                Row {
                    UsageSection(agents, readings, setOf("claude", "codex", "gemini"), compact = false, now = now, onCompact = {}, onShown = { _, _ -> }, modifier = Modifier.width(272.dp))
                    UsageSection(agents, readings, setOf("claude", "codex", "gemini"), compact = true, now = now, onCompact = {}, onShown = { _, _ -> }, modifier = Modifier.width(272.dp))
                }
            }
            // The bar turns amber from 80 %.
            assertTrue(rendered.count(if (dark) 0xE5A93D else 0xA85F00) > 100)
        }
    }
}
