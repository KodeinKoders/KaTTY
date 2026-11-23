@file:OptIn(ExperimentalTestApi::class)

package app.katty.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.runComposeUiTest
import app.katty.ShortcutMap
import app.katty.core.EnvironmentFolder
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
    private var addedFolders = 0
    private val appliedFolders = mutableListOf<String>()
    private val opened = mutableListOf<String>()

    @AfterTest
    fun cleanUp() {
        root.toFile().deleteRecursively()
    }

    private fun ComposeUiTest.show(projectsWithoutEnvironment: Int = 0, projectsOffFolder: Map<String, Int> = emptyMap()) {
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
                        projectsOffFolder = projectsOffFolder,
                        onAddProjectFolder = { addedFolders++ },
                        onApplyProjectFolder = { appliedFolders += it },
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
    fun theEnvironmentOfAFolderOfProjectsIsChosenAndTheFolderRemoved() = runComposeUiTest {
        shared.resolve("kodein.env").writeText("A=1")
        shared.resolve("perso.env").writeText("A=2")
        settings = settings.copy(
            defaultEnvironment = "kodein",
            environmentFolders = listOf(EnvironmentFolder("~/dev/kosi-libs", "kodein"), EnvironmentFolder("~/dev/private", null), EnvironmentFolder("~/dev/gone", "sunday")),
        )

        show()

        onNodeWithText("PROJECT FOLDERS").performScrollTo().assertExists()
        onNodeWithText("~/dev/kosi-libs").assertExists()
        onNodeWithContentDescription("Environment of ~/dev/kosi-libs: kodein").assertExists()
        onNodeWithContentDescription("Environment of ~/dev/private: None").assertExists()
        onNodeWithContentDescription("Environment of ~/dev/gone: sunday (not found)").assertExists()

        onNodeWithContentDescription("Environment of ~/dev/private: None").performScrollTo().performClick()
        onAllNodesWithText("perso").onLast().performClick()
        waitForIdle()
        assertEquals(
            listOf(EnvironmentFolder("~/dev/kosi-libs", "kodein"), EnvironmentFolder("~/dev/private", "perso"), EnvironmentFolder("~/dev/gone", "sunday")),
            settings.environmentFolders,
            "Only the folder that was chosen for changes",
        )

        onNodeWithContentDescription("Environment of ~/dev/kosi-libs: kodein").performScrollTo().performClick()
        onAllNodesWithText("None").onLast().performClick()
        waitForIdle()
        assertEquals(null, settings.environmentFolders.first().environment, "None is a choice: it keeps the folder out of the default one")

        onNodeWithContentDescription("Remove ~/dev/gone").performScrollTo().performClick()
        waitForIdle()
        assertEquals(listOf("~/dev/kosi-libs", "~/dev/private"), settings.environmentFolders.map { it.folder })
        assertEquals("kodein", settings.defaultEnvironment, "The default one is not changed")
    }

    @Test
    fun aFolderOfProjectsIsAddedAndItsEnvironmentAppliedToTheProjectsThatHaveAnother() = runComposeUiTest {
        shared.resolve("kodein.env").writeText("A=1")
        settings = settings.copy(environmentFolders = listOf(EnvironmentFolder("~/dev/kosi-libs", "kodein"), EnvironmentFolder("~/dev/private", null)))

        show(projectsOffFolder = mapOf("~/dev/kosi-libs" to 2, "~/dev/private" to 1))

        onNodeWithText("2 projects have another environment.").assertExists()
        onNodeWithText("1 project has another environment.").assertExists()
        onNodeWithContentDescription("Apply the environment of ~/dev/kosi-libs to its projects").performScrollTo().performClick()
        assertEquals(listOf("~/dev/kosi-libs"), appliedFolders)

        onNodeWithText("Add project folder…").performScrollTo().performClick()
        assertEquals(1, addedFolders)
    }

    @Test
    fun aFolderOfProjectsThatHasNothingToApplyHasNoApplyButton() = runComposeUiTest {
        settings = settings.copy(environmentFolders = listOf(EnvironmentFolder("~/dev/kosi-libs", "kodein")))

        show(projectsOffFolder = mapOf("~/dev/kosi-libs" to 0))

        onNodeWithContentDescription("Apply the environment of ~/dev/kosi-libs to its projects").assertDoesNotExist()
        onAllNodesWithText("another environment.", substring = true).assertCountEquals(0)
    }

    @Test
    fun aDefaultThatHasNoFileSaysSo() = runComposeUiTest {
        settings = settings.copy(defaultEnvironment = "gone")

        show()

        onNodeWithContentDescription("Default environment: gone (not found)").assertExists()
    }

    @Test
    fun aBadgeIsGivenATintOfThePaletteAndAutoTakesItBack() = runComposeUiTest {
        shared.resolve("kodein.env").writeText("A=1")
        settings = settings.copy(environmentColors = mapOf("gone" to "green"))

        show()

        onNodeWithContentDescription("Color of kodein: Auto").performScrollTo().performClick()
        onNodeWithText("Orange").performClick()
        waitForIdle()
        assertEquals(mapOf("gone" to "green", "kodein" to "orange"), settings.environmentColors, "The color of an environment that is not found is kept")
        onNodeWithContentDescription("Color of kodein: Orange").assertExists()

        onNodeWithContentDescription("Color of kodein: Orange").performClick()
        onNodeWithText("Auto").performClick()
        waitForIdle()
        assertEquals(mapOf("gone" to "green"), settings.environmentColors)
    }

    @Test
    fun aBadgeIsGivenAHexThatIsShownOnBothThemesFirst() = runComposeUiTest {
        shared.resolve("kodein.env").writeText("A=1")

        show()

        onNodeWithContentDescription("Color of kodein: Auto").performScrollTo().performClick()
        onNodeWithText("Custom…").performClick()
        onNodeWithContentDescription("Color of “kodein”").assertExists()
        onNodeWithContentDescription("Light preview").assertExists()
        onNodeWithContentDescription("Dark preview").assertExists()

        onNodeWithContentDescription("Hex color").performTextInput("zz")
        onNodeWithText("Six hex digits, with or without #.").assertExists()
        onNodeWithText("Apply").assertIsNotEnabled()

        onNodeWithContentDescription("Hex color").performTextReplacement("e08a00")
        onNodeWithText("Apply").assertIsEnabled().performClick()
        waitForIdle()
        assertEquals(mapOf("kodein" to "#E08A00"), settings.environmentColors)
        onNodeWithContentDescription("Color of “kodein”").assertDoesNotExist()
        onNodeWithContentDescription("Color of kodein: #E08A00").assertExists()
    }

    @Test
    fun theHexDialogIsCancelledWithoutAChange() = runComposeUiTest {
        shared.resolve("kodein.env").writeText("A=1")
        settings = settings.copy(environmentColors = mapOf("kodein" to "#00AA55"))

        show()

        onNodeWithContentDescription("Color of kodein: #00AA55").performScrollTo().performClick()
        onNodeWithText("Custom…").performClick()
        onNodeWithContentDescription("Hex color").assertTextContains("#00AA55")
        onNodeWithContentDescription("Hex color").performTextReplacement("123456")
        onNodeWithText("Cancel").performClick()
        waitForIdle()
        assertEquals(mapOf("kodein" to "#00AA55"), settings.environmentColors)
    }
}
