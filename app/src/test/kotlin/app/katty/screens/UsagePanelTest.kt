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
import app.katty.core.OPEN_SESSIONS_KEY
import app.katty.core.OpenSessions
import app.katty.core.UsageReading
import app.katty.core.UsageSpend
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

    private val agents = listOf(Agent.CLAUDE, Agent.CODEX, Agent.COPILOT)

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

        onNodeWithText("Copilot CLI").assertExists()
        onNodeWithText("No meter").assertExists()
    }

    @Test
    fun theCompactListSaysTheSameInItsDescription() = runComposeUiTest {
        setContent { KattyTheme(false) { UsageList(agents, readings, now, UsageVariant.COMPACT, Modifier.width(240.dp)) } }

        onNodeWithText("82%").assertExists()
        onNodeWithText("40%").assertExists()
        onNodeWithContentDescription("Session: 82% used, resets in 53 minutes; Week: 40% used, resets in 3 days").assertExists()
        onNodeWithContentDescription("Copilot CLI: usage not available").assertExists()
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
        setContent { KattyTheme(false) { DetectedAgents(mapOf(Agent.CLAUDE to Path.of("/bin/claude"), Agent.CODEX to Path.of("/bin/codex"), Agent.COPILOT to Path.of("/bin/copilot"))) } }

        onNodeWithText("DETECTED ON THIS MACHINE").assertExists()
        // Three found, and three that were not: the numbers of the two sections.
        onNodeWithText("3").assertExists()
        onNodeWithContentDescription("Claude Code: Detected").assertExists()
        onNodeWithContentDescription("Copilot CLI: Detected").assertExists()
        // The others are in a section that is closed: its title says how many, and none of them is listed.
        onNodeWithText("NOT FOUND").assertExists()
        onNodeWithContentDescription("Not found: 3").assertExists()
        assertEquals(0, onAllNodesWithContentDescription("Antigravity: Not found").fetchSemanticsNodes().size)
        onNodeWithText("Agents you install later appear here automatically.").assertExists()
    }

    @Test
    fun theSectionOfTheAgentsThatWereNotFoundOpensAndCloses() = runComposeUiTest {
        setContent { KattyTheme(false) { DetectedAgents(mapOf(Agent.CLAUDE to Path.of("/bin/claude"))) } }

        onNodeWithContentDescription("Not found: 5").performClick()
        waitForIdle()
        onNodeWithContentDescription("Codex: Not found").assertExists()
        onNodeWithContentDescription("Copilot CLI: Not found").assertExists()
        onNodeWithContentDescription("Not found: 5").performClick()
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
        onNodeWithContentDescription("Not found: 6").assertExists()
    }

    @Test
    fun drawsTheUsageInBothThemes() {
        for (dark in listOf(false, true)) {
            val rendered = render("usage-${if (dark) "dark" else "light"}", 1100, 520, dark = dark) {
                Row {
                    UsageSection(agents, readings, setOf("claude", "codex", "copilot"), compact = false, now = now, onCompact = {}, onShown = { _, _ -> }, modifier = Modifier.width(272.dp))
                    UsageSection(agents, readings, setOf("claude", "codex", "copilot"), compact = true, now = now, onCompact = {}, onShown = { _, _ -> }, modifier = Modifier.width(272.dp))
                }
            }
            // The bar turns amber from 80 %.
            assertTrue(rendered.count(if (dark) 0xE5A93D else 0xA85F00) > 100)
        }
    }

    private val spendReadings = mapOf(
        Agent.OPENCODE to UsageReading(Agent.OPENCODE, emptyList(), listOf(UsageSpend("Today", 1.5), UsageSpend("7 days", 12.0), UsageSpend("This month", 40.256))),
    )

    @Test
    fun anAgentThatSpendsAndHasNoAllowanceShowsWhatItSpent() = runComposeUiTest {
        setContent { KattyTheme(false) { UsageList(listOf(Agent.OPENCODE), spendReadings, now, UsageVariant.FULL, Modifier.width(240.dp)) } }

        onNodeWithText("Today").assertExists()
        onNodeWithText("~$1.50").assertExists()
        onNodeWithText("7 days").assertExists()
        onNodeWithText("~$12.00").assertExists()
        onNodeWithText("This month").assertExists()
        onNodeWithText("~$40.26").assertExists()
        onNodeWithText("No meter").assertDoesNotExist()
    }

    @Test
    fun theCompactAndRailListsSaySoToo() = runComposeUiTest {
        setContent {
            KattyTheme(false) {
                Column {
                    UsageList(listOf(Agent.OPENCODE), spendReadings, now, UsageVariant.COMPACT, Modifier.width(240.dp))
                    UsageList(listOf(Agent.OPENCODE), spendReadings, now, UsageVariant.RAIL)
                }
            }
        }

        // Both say the same in their description.
        assertEquals(2, onAllNodesWithContentDescription("Today: ~$1.50 spent; 7 days: ~$12.00 spent; This month: ~$40.26 spent").fetchSemanticsNodes().size)
        // Both draw today's amount; the compact one has the other two as well.
        assertEquals(2, onAllNodesWithText("~$1.50").fetchSemanticsNodes().size)
        onNodeWithText("~$12.00").assertExists()
    }

    @Test
    fun aPeriodThatIsHiddenIsNotDrawn() = runComposeUiTest {
        setContent {
            KattyTheme(false) {
                UsageList(listOf(Agent.OPENCODE), spendReadings, now, UsageVariant.FULL, Modifier.width(240.dp), hidden = setOf(usageKey(Agent.OPENCODE, "Today")))
            }
        }

        onNodeWithText("~$1.50").assertDoesNotExist()
        onNodeWithText("~$12.00").assertExists()
    }

    @Test
    fun withEveryPeriodHiddenTheAgentSaysSo() = runComposeUiTest {
        val hidden = spendReadings.getValue(Agent.OPENCODE).spend.map { usageKey(Agent.OPENCODE, it.label) }.toSet()
        setContent { KattyTheme(false) { UsageList(listOf(Agent.OPENCODE), spendReadings, now, UsageVariant.FULL, Modifier.width(240.dp), hidden = hidden) } }

        onNodeWithText("No limit shown").assertExists()
    }

    @Test
    fun theChooserListsThePeriodsToo() = runComposeUiTest {
        val calls = mutableListOf<String>()
        setContent {
            KattyTheme(false) {
                UsageSection(
                    listOf(Agent.OPENCODE), spendReadings, shown = setOf("opencode"), compact = false, now = now, onCompact = {}, onShown = { _, _ -> },
                    onHidden = { a, label, hide -> calls += "${a.id} $label $hide" },
                )
            }
        }

        onNodeWithContentDescription("Choose agents to show").performClick()
        onAllNodesWithText("This month").onLast().performClick()

        assertEquals(listOf("opencode This month true"), calls)
    }

    @Test
    fun theOpenSessionsLineIsAtTheTopAndSaysHowManyAndWhatTheyCost() = runComposeUiTest {
        setContent {
            KattyTheme(false) {
                UsageSection(agents, readings, shown = setOf("claude"), compact = false, now = now, onCompact = {}, onShown = { _, _ -> }, openSessions = OpenSessions(3, 7.5))
            }
        }

        onNodeWithText("Open sessions (3)").assertExists()
        onNodeWithText("~$7.50").assertExists()
        val line = onNodeWithContentDescription("Open sessions (3): ~$7.50 spent").fetchSemanticsNode().boundsInRoot
        val claude = onNodeWithContentDescription("Claude Code usage").fetchSemanticsNode().boundsInRoot
        assertTrue(line.bottom <= claude.top, "$line $claude")
    }

    @Test
    fun theOpenSessionsLineIsDrawnOnceInTheBarWhateverTheAgents() = runComposeUiTest {
        setContent {
            KattyTheme(false) {
                UsageBarRow(agents, readings, shown = setOf("claude", "codex"), compact = false, now = now, onCompact = {}, onShown = { _, _ -> }, openSessions = OpenSessions(2, null))
            }
        }

        assertEquals(1, onAllNodesWithText("Open sessions (2)").fetchSemanticsNodes().size)
        onNodeWithContentDescription("Open sessions (2)").assertExists()
    }

    @Test
    fun theOpenSessionsLineCanBeHiddenFromTheChooser() = runComposeUiTest {
        val calls = mutableListOf<Boolean>()
        var hidden by androidx.compose.runtime.mutableStateOf(emptySet<String>())
        setContent {
            KattyTheme(false) {
                UsageSection(
                    agents, readings, shown = setOf("claude"), compact = false, now = now, onCompact = {}, onShown = { _, _ -> },
                    hidden = hidden, openSessions = OpenSessions(1, 1.0), onOpenSessions = { calls += it },
                )
            }
        }
        onNodeWithText("Open sessions (1)").assertExists()

        onNodeWithContentDescription("Choose agents to show").performClick()
        onNodeWithText("Open sessions total").performClick()
        assertEquals(listOf(false), calls)
        hidden = setOf(OPEN_SESSIONS_KEY)
        waitForIdle()

        onNodeWithText("Open sessions (1)").assertDoesNotExist()
    }

    @Test
    fun noSessionMeansNoLine() = runComposeUiTest {
        setContent { KattyTheme(false) { UsageSection(agents, readings, shown = setOf("claude"), compact = false, now = now, onCompact = {}, onShown = { _, _ -> }) } }

        assertEquals(0, onAllNodesWithContentDescription("Open sessions", substring = true).fetchSemanticsNodes().size)
    }

    @Test
    fun drawsTheSpendAndTheTotalAtBothSizes() {
        for (dark in listOf(false, true)) for ((suffix, scale) in listOf("100" to 1f, "175" to 1.75f)) {
            val rendered = render("usage-spend-${if (dark) "dark" else "light"}-$suffix", 1500, 700, dark = dark) {
                app.katty.ui.UiScale(scale) {
                    Row {
                        UsageSection(listOf(Agent.CLAUDE, Agent.OPENCODE), readings + spendReadings, setOf("claude", "opencode"), compact = false, now = now, onCompact = {}, onShown = { _, _ -> }, modifier = Modifier.width(272.dp * scale), openSessions = OpenSessions(3, 7.5))
                        UsageSection(listOf(Agent.CLAUDE, Agent.OPENCODE), readings + spendReadings, setOf("claude", "opencode"), compact = true, now = now, onCompact = {}, onShown = { _, _ -> }, modifier = Modifier.width(272.dp * scale), openSessions = OpenSessions(3, 7.5))
                    }
                }
            }
            assertTrue(rendered.count(if (dark) 0xE5A93D else 0xA85F00) > 100, "The bar of Claude Code is drawn ($dark, $suffix)")
        }
    }
}
