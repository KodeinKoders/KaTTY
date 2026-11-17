@file:OptIn(ExperimentalTestApi::class)

package app.katty.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.katty.core.AgentState
import app.katty.core.GitChanges
import app.katty.core.GitChangesDisplay
import app.katty.core.GitInfo
import app.katty.core.GitKind
import app.katty.core.GitOperation
import app.katty.core.GitStatusReading
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

    // What `git status` says, and what git is in the middle of, after the branch: a dot or the counts, the upstream, the operation.
    @Test
    fun theLabelSaysTheChangesTheUpstreamAndTheOperation() = runComposeUiTest {
        val status = GitStatusReading(GitChanges(staged = 2, modified = 1, untracked = 3), ahead = 4, behind = 0)
        setContent {
            KattyTheme(dark = false) {
                Column {
                    PaneHeader("zsh", AgentGlyph.SHELL, AgentState.IDLE, GitInfo("main", GitKind.BRANCH, status = status), Modifier.width(600.dp))
                    CompositionLocalProvider(LocalGitChangesDisplay provides GitChangesDisplay.COUNTS) {
                        PaneHeader("zsh", AgentGlyph.SHELL, AgentState.IDLE, GitInfo("feature/x", GitKind.BRANCH, GitOperation.REBASE, status = status), Modifier.width(600.dp))
                    }
                    PaneHeader("zsh", AgentGlyph.SHELL, AgentState.IDLE, GitInfo("clean", GitKind.BRANCH, status = GitStatusReading(GitChanges())), Modifier.width(600.dp))
                }
            }
        }

        onNodeWithContentDescription("Branch main, uncommitted changes, 4 ahead").assertExists()
        onNodeWithContentDescription("Branch feature/x, 2 staged, 1 modified, 3 untracked, 4 ahead, rebasing").assertExists()
        onNodeWithText("+2").assertExists()
        onNodeWithText("~1").assertExists()
        onNodeWithText("?3").assertExists()
        onNodeWithText("rebasing").assertExists()
        // Nothing is said of a clean tree, nor of a branch that is not behind.
        onNodeWithContentDescription("Branch clean").assertExists()
        assertEquals(0, onAllNodesWithText("↓0").fetchSemanticsNodes().size)
    }

    // Drawn to build/renders/git-labels-*.png, in a header and in the narrow column of the sidebar.
    @Test
    fun drawsTheGitLabelsInBothDisplays() {
        val status = GitStatusReading(GitChanges(staged = 2, modified = 5, untracked = 1, conflicted = 1), ahead = 3, behind = 12)
        for (isDark in listOf(false, true)) {
            val image = render("git-labels-${if (isDark) "dark" else "light"}", 1300, 330, dark = isDark) {
                Column(Modifier.padding(8.dp)) {
                    for (display in GitChangesDisplay.entries) {
                        CompositionLocalProvider(LocalGitChangesDisplay provides display) {
                            PaneHeader("zsh", AgentGlyph.SHELL, AgentState.IDLE, GitInfo("main", GitKind.BRANCH, status = status), Modifier.fillMaxWidth())
                            PaneHeader("Claude Code", AgentGlyph.CLAUDE, AgentState.WORKING, GitInfo("feature/a-long-branch-name", GitKind.WORKTREE, GitOperation.MERGE, status = status), Modifier.fillMaxWidth())
                            // As narrow as the sidebar: the name is what is cut.
                            GitLabel(GitInfo("feature/a-long-branch-name-that-does-not-fit", GitKind.BRANCH, GitOperation.REBASE, status = status), Modifier.width(220.dp), fontSize = 11.sp)
                        }
                    }
                }
            }
            // The amber of the dot, the counts and the operation.
            assertTrue(image.count(if (isDark) 0xE5A93D else 0xA85F00) > 50, "dark: $isDark")
        }
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
