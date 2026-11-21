@file:OptIn(ExperimentalTestApi::class)

package app.katty.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runComposeUiTest
import app.katty.ShortcutMap
import app.katty.core.Environments
import app.katty.core.Settings
import app.katty.ui.KattyTheme
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class EnvironmentsPageTest {
    private val root: Path = Files.createTempDirectory("katty-environments-page")
    private val shared = root.resolve("shared").also { it.createDirectories() }
    private val secrets = root.resolve("secrets").also { it.createDirectories() }
    private var settings by mutableStateOf(Settings(environmentDirectories = listOf(shared.toString(), secrets.toString())))
    private var refreshed = 0
    private var applied = 0
    private val opened = mutableListOf<String>()

    @AfterTest
    fun cleanUp() {
        root.toFile().deleteRecursively()
    }

    private fun ComposeUiTest.show(projectsWithoutEnvironment: Int = 0) {
        val listing = Environments.list(settings.environmentDirectories)
        setContent {
            KattyTheme(dark = false) {
                SettingsContent(
                    page = SettingsPage.ENVIRONMENTS,
                    onPage = {},
                    settings = settings,
                    onChange = { change -> settings = change(settings) },
                    schemes = emptyList(),
                    fontFamilies = emptyList(),
                    shortcuts = ShortcutMap.DEFAULT,
                    onShortcuts = {},
                    agents = emptySet(),
                    onAbout = {},
                    environments = listing,
                    environmentActions = EnvironmentActions(
                        projectsWithoutEnvironment = projectsWithoutEnvironment,
                        onRefresh = { refreshed++ },
                        onOpenFolder = { opened += it },
                        onApplyDefault = { applied++ },
                    ),
                )
            }
        }
    }

    @Test
    fun theEnvironmentsAreListedWithTheirFilesAndNeverAValue() = runComposeUiTest {
        shared.resolve("kodein.env").writeText("CLAUDE_CONFIG_DIR=~/.claude-kodein\nGH_TOKEN=s3cr3t")
        shared.resolve("sunday.env").writeText("A=1")
        secrets.resolve("sunday.env").writeText("TOKEN=s3cr3t\ns3cr3t\nalso s3cr3t")

        show()

        onNodeWithText("kodein").assertExists()
        onNodeWithText("sunday").assertExists()
        onAllNodesWithText("sunday.env", substring = true, useUnmergedTree = true).assertCountEquals(2)
        onNodeWithText("⚠ 2 lines ignored (line 2)", useUnmergedTree = true).assertExists()
        onAllNodesWithText("s3cr3t", substring = true, useUnmergedTree = true).assertCountEquals(0)
        onAllNodesWithText("GH_TOKEN", substring = true, useUnmergedTree = true).assertCountEquals(0)
        assertEquals(1, refreshed, "Read again when the page opens")
    }

    @Test
    fun theFoldersAreOrderedRemovedAndOpened() = runComposeUiTest {
        show()

        onNodeWithContentDescription("Move $secrets up").performClick()
        waitForIdle()
        assertEquals(listOf(secrets.toString(), shared.toString()), settings.environmentDirectories)

        onNodeWithContentDescription("Open $shared").performClick()
        assertEquals(listOf(shared.toString()), opened)

        onNodeWithContentDescription("Remove $secrets").performClick()
        waitForIdle()
        assertEquals(listOf(shared.toString()), settings.environmentDirectories)

        onNodeWithContentDescription("Reset the folders").performClick()
        waitForIdle()
        assertEquals(listOf(Environments.DEFAULT_DIRECTORY), settings.environmentDirectories)
    }

    @Test
    fun theDefaultIsAppliedToTheProjectsThatHaveNone() = runComposeUiTest {
        settings = settings.copy(defaultEnvironment = "kodein")
        shared.resolve("kodein.env").writeText("A=1")

        show(projectsWithoutEnvironment = 3)

        onNodeWithContentDescription("Default environment: kodein").assertExists()
        onNodeWithText("3 projects have no environment.").assertExists()
        onNodeWithContentDescription("Apply the default environment to the projects that have none").performScrollTo().performClick()
        assertEquals(1, applied)
    }

    @Test
    fun aDefaultThatHasNoFileSaysSo() = runComposeUiTest {
        settings = settings.copy(defaultEnvironment = "gone")

        show()

        onNodeWithContentDescription("Default environment: gone (not found)").assertExists()
    }
}
