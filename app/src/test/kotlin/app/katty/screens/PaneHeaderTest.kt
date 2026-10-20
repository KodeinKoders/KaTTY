@file:OptIn(ExperimentalTestApi::class)

package app.katty.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import app.katty.core.AgentState
import app.katty.core.GitInfo
import app.katty.core.GitKind
import app.katty.core.SessionUsage
import app.katty.ui.AgentGlyph
import app.katty.ui.KattyTheme
import app.katty.ui.render
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PaneHeaderTest {
    @Test
    fun theHeaderSaysWhatRunsWhereItIsAndTheStateInWords() = runComposeUiTest {
        setContent {
            KattyTheme(dark = false) {
                PaneHeader("Codex", AgentGlyph.CODEX, AgentState.NEEDS_INPUT, GitInfo("rate-limit-key", GitKind.WORKTREE), Modifier.width(600.dp))
            }
        }

        onNodeWithContentDescription("Terminal Codex").assertExists()
        onNodeWithContentDescription("Worktree rate-limit-key").assertExists()
        onNodeWithText("Needs input").assertExists()
    }

    @Test
    fun aBranchAndACommitAreToldApart() = runComposeUiTest {
        setContent {
            KattyTheme(dark = false) {
                Column {
                    PaneHeader("zsh", AgentGlyph.SHELL, AgentState.IDLE, GitInfo("main", GitKind.BRANCH), Modifier.width(600.dp))
                    PaneHeader("zsh", AgentGlyph.SHELL, AgentState.IDLE, GitInfo("a1b2c3d", GitKind.DETACHED), Modifier.width(600.dp))
                }
            }
        }

        onNodeWithContentDescription("Branch main").assertExists()
        onNodeWithContentDescription("Commit a1b2c3d").assertExists()
    }

    @Test
    fun aTerminalOutsideARepositoryShowsNoBranch() = runComposeUiTest {
        setContent { KattyTheme(dark = false) { PaneHeader("zsh", AgentGlyph.SHELL, AgentState.IDLE, null, Modifier.width(600.dp)) } }

        onNodeWithContentDescription("Terminal zsh").assertExists()
        assertEquals(0, onAllNodesWithContentDescription("Branch main").fetchSemanticsNodes().size)
    }

    @Test
    fun drawsTheHeadersOfEveryState() {
        val dark = listOf(false, true).map { isDark ->
            render("pane-headers-${if (isDark) "dark" else "light"}", 1300, 240, dark = isDark) {
                Column(Modifier.padding(8.dp)) {
                    PaneHeader("Claude Code", AgentGlyph.CLAUDE, AgentState.WORKING, GitInfo("refactor-auth", GitKind.BRANCH), Modifier.fillMaxWidth())
                    PaneHeader("Codex", AgentGlyph.CODEX, AgentState.NEEDS_INPUT, GitInfo("rate-limit-key", GitKind.WORKTREE), Modifier.fillMaxWidth())
                    PaneHeader("zsh", AgentGlyph.SHELL, AgentState.IDLE, GitInfo("refactor-auth", GitKind.BRANCH), Modifier.fillMaxWidth())
                    PaneHeader("Copilot CLI", AgentGlyph.COPILOT, AgentState.DONE, null, Modifier.fillMaxWidth())
                }
            }
        }

        // The amber of Needs input is in both: the glyph, and the state in words.
        assertTrue(dark[0].count(0xA85F00) > 100)
        assertTrue(dark[1].count(0xE5A93D) > 100)
    }

    @Test
    fun theHeaderSaysWhatTheSessionHasUsed() = runComposeUiTest {
        setContent {
            KattyTheme(dark = false) {
                PaneHeader("OpenCode", AgentGlyph.OPENCODE, AgentState.IDLE, null, Modifier.width(600.dp), usage = SessionUsage(42, 1.234))
            }
        }

        onNodeWithContentDescription("42% of context used, ~$1.23 spent in this session").assertExists()
        onNodeWithText("~$1.23").assertExists()
    }

    @Test
    fun aHeaderWithNoUsageShowsNoBadge() = runComposeUiTest {
        setContent { KattyTheme(dark = false) { PaneHeader("zsh", AgentGlyph.SHELL, AgentState.IDLE, null, Modifier.width(600.dp), usage = null) } }

        assertEquals(0, onAllNodesWithContentDescription("% of context used", substring = true).fetchSemanticsNodes().size)
    }

    @Test
    fun drawsTheBadgeInBothThemesAndAtTheBiggestSize() {
        for (isDark in listOf(false, true)) for ((suffix, scale) in listOf("100" to 1f, "175" to 1.75f)) {
            val rendered = render("pane-header-usage-${if (isDark) "dark" else "light"}-$suffix", 900, 140, dark = isDark) {
                app.katty.ui.UiScale(scale) {
                    Column(Modifier.padding(8.dp)) {
                        PaneHeader("OpenCode", AgentGlyph.OPENCODE, AgentState.IDLE, GitInfo("refactor-auth", GitKind.BRANCH), Modifier.fillMaxWidth(), usage = SessionUsage(97, 12.5))
                    }
                }
            }
            // A context that is almost full is drawn in the error color.
            assertTrue(rendered.count(if (isDark) 0xF2767A else 0xC93234, tolerance = 4) > 5, "The ring is drawn ($isDark, $suffix)")
        }
    }
}
