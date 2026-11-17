@file:OptIn(ExperimentalTestApi::class)

package app.katty.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.runSkikoComposeUiTest
import app.katty.AppShortcut
import app.katty.ShortcutMap
import app.katty.core.Agent
import app.katty.core.BellMode
import app.katty.core.CursorShape
import app.katty.core.LayoutMode
import app.katty.core.MinimumContrast
import app.katty.core.OptionKey
import app.katty.core.ScrollbackSize
import app.katty.core.SecureInput
import app.katty.core.Settings
import app.katty.core.ThemeMode
import app.katty.terminal.themes.ITermColorSchemes
import app.katty.ui.KattyTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettingsPagesTest {
    private var settings by mutableStateOf(Settings())
    private var page by mutableStateOf(SettingsPage.APPEARANCE)
    private var shortcuts by mutableStateOf(ShortcutMap.DEFAULT)
    private var abouts = 0
    private var agents by mutableStateOf(setOf(Agent.CLAUDE, Agent.CODEX))

    private fun ComposeUiTest.show(families: List<String> = listOf("Menlo", "Monaco")) {
        setContent {
            KattyTheme(dark = false) {
                SettingsContent(
                    page = page,
                    onPage = { page = it },
                    settings = settings,
                    onChange = { change -> settings = change(settings) },
                    schemes = ITermColorSchemes.entries,
                    fontFamilies = families,
                    shortcuts = shortcuts,
                    onShortcuts = { shortcuts = it },
                    agents = agents,
                    onAbout = { abouts++ },
                )
            }
        }
    }

    // The entry of a page in the list on the left: its title is also the heading of the page that is shown.
    private fun ComposeUiTest.goTo(page: SettingsPage) {
        onNode(hasText(page.title) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)).performClick()
        waitForIdle()
    }

    // A page that is taller than the window scrolls: what is below is scrolled to before it is clicked.
    private fun ComposeUiTest.clickOn(text: String, index: Int = 0) {
        onAllNodesWithText(text)[index].performScrollTo().performClick()
        waitForIdle()
    }

    @Test
    fun thePagesAreListedAndTheFirstIsTheAppearance() = runComposeUiTest {
        show()

        for (entry in SettingsPage.entries) onNode(hasText(entry.title) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)).assertExists()
        onNode(hasText("Appearance") and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)).assertIsSelected()
        onNodeWithText("LAYOUT").assertExists()
        onNodeWithText("Auto uses Vertical when the window is taller than it is wide.").assertExists()
        onNodeWithText("THEME").assertExists()
        onNodeWithText("INTERFACE TEXT SIZE").assertExists()
        onNodeWithText("100%").assertExists()
        onNodeWithText("TERMINAL COLOR SCHEME").assertExists()
        onNodeWithText("FONT FAMILY").assertExists()
        onNodeWithText("Shows sequences like => and != as single glyphs.").assertExists()
        onNodeWithText("14 px").assertExists()
    }

    // At the bottom of the list of the pages, whatever page is shown.
    @Test
    fun theButtonAtTheBottomOfThePagesOpensTheAboutWindow() = runComposeUiTest {
        show()
        goTo(SettingsPage.AGENTS)

        onNode(hasText("About KaTTY") and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)).performClick()
        waitForIdle()

        assertEquals(1, abouts)
        assertEquals(SettingsPage.AGENTS, page)
    }

    @Test
    fun aClickOrTheArrowsShowAnotherPage() = runComposeUiTest {
        show()

        goTo(SettingsPage.TERMINAL)
        assertEquals(SettingsPage.TERMINAL, page)
        onNodeWithText("CURSOR").assertExists()
        assertEquals(0, onAllNodesWithText("LAYOUT").fetchSemanticsNodes().size, "One page at a time")

        onNodeWithContentDescription("Settings pages").requestFocus()
        onNodeWithContentDescription("Settings pages").performKeyInput { pressKey(Key.DirectionDown) }
        waitForIdle()
        assertEquals(SettingsPage.KEYBOARD, page)
        onNodeWithText("OPTION AS ALT").assertExists()
        onNodeWithContentDescription("Settings pages").performKeyInput { pressKey(Key.DirectionUp); pressKey(Key.DirectionUp); pressKey(Key.DirectionUp) }
        waitForIdle()
        assertEquals(SettingsPage.AGENTS, page, "The arrows go round")
    }

    @Test
    fun theLayoutAndTheThemeAreChosenAmongTheirOptions() = runComposeUiTest {
        show()

        onNodeWithText("Vertical").performClick()
        // The button of the theme, not the label of the terminals' scheme of the dark theme.
        onNode(hasText("Dark") and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)).performClick()
        waitForIdle()

        assertEquals(LayoutMode.VERTICAL, settings.layout)
        assertEquals(ThemeMode.DARK, settings.theme)
        onNodeWithText("System").performClick()
        assertEquals(ThemeMode.SYSTEM, settings.theme)
    }

    @Test
    fun theChosenLayoutIsMarked() = runComposeUiTest {
        settings = Settings(layout = LayoutMode.HORIZONTAL)
        show()

        onNodeWithText("Horizontal").assertIsSelected()
    }

    @Test
    fun theFontSizeGoesUpAndDownByHalfAPixelAndLigaturesAreTurnedOffAndOn() = runSkikoComposeUiTest(size = Size(1400f, 1600f)) {
        show()

        onNodeWithContentDescription("Increase font size").performClick()
        onNodeWithContentDescription("Increase font size").performClick()
        assertEquals(15f, settings.fontSize)
        onNodeWithContentDescription("Decrease font size").performClick()
        assertEquals(14.5f, settings.fontSize)
        waitForIdle()
        onNodeWithText("14.5 px").assertExists()

        onNodeWithText("Font ligatures").performClick()
        assertFalse(settings.ligatures)
        onNodeWithText("Font ligatures").performClick()
        assertTrue(settings.ligatures)
    }

    @Test
    fun theFontIsChosenAmongTheFamiliesOfTheSystemOrTheBundledOne() = runSkikoComposeUiTest(size = Size(1400f, 1600f)) {
        show()

        onNodeWithContentDescription("Font family: JetBrains Mono").performClick()
        onNodeWithText("Menlo").performClick()
        waitForIdle()
        assertEquals("Menlo", settings.fontFamily)

        onNodeWithContentDescription("Font family: Menlo").performClick()
        onNodeWithText("JetBrains Mono (bundled)").performClick()
        waitForIdle()
        assertEquals(null, settings.fontFamily)
    }

    @Test
    fun theTextOfTheApplicationIsMadeBiggerAndSmallerOneSizeAtATime() = runComposeUiTest {
        show()

        onNodeWithContentDescription("Increase interface text size").performClick()
        assertEquals(1.1f, settings.uiScale)
        onNodeWithText("110%").assertExists()
        onNodeWithContentDescription("Increase interface text size").performClick()
        assertEquals(1.25f, settings.uiScale)
        onNodeWithContentDescription("Decrease interface text size").performClick()
        onNodeWithContentDescription("Decrease interface text size").performClick()
        onNodeWithContentDescription("Decrease interface text size").performClick()
        assertEquals(0.9f, settings.uiScale)
        // The terminals' own font is another setting, and does not change with it.
        assertEquals(14f, settings.fontSize)
    }

    @Test
    fun theStepperStopsAtTheBiggestAndTheSmallestSize() = runComposeUiTest {
        settings = Settings(uiScale = Settings.UI_SCALES.last())
        show()

        onNodeWithContentDescription("Increase interface text size").performClick()
        assertEquals(Settings.UI_SCALES.last(), settings.uiScale)
        settings = Settings(uiScale = Settings.UI_SCALES.first())
        waitForIdle()
        onNodeWithContentDescription("Decrease interface text size").performClick()
        assertEquals(Settings.UI_SCALES.first(), settings.uiScale)
    }

    // The picker opens over the settings, in their window, on the scheme of the theme it was opened for; its tabs go to the other one.
    @Test
    fun theColorSchemeOfEachThemeIsChosenInThePickerOverTheSettings() = runSkikoComposeUiTest(size = Size(2000f, 1600f)) {
        show()

        onNodeWithContentDescription("Terminal color scheme, dark theme: Dracula").performClick()
        waitForIdle()
        onNodeWithContentDescription("Terminal color scheme").assertExists()
        onNode(androidx.compose.ui.test.hasSetTextAction()).performTextInput("Darcula")
        waitForIdle()
        onNodeWithText("Solarized Darcula").performClick()
        waitForIdle()
        assertEquals("Solarized Darcula", settings.darkColorScheme)
        assertEquals("Alabaster", settings.lightColorScheme, "The other theme's is left")

        onNodeWithContentDescription("Close").performClick()
        waitForIdle()
        assertEquals(0, onAllNodesWithContentDescription("Terminal color scheme").fetchSemanticsNodes().size, "The picker is closed")
        onNodeWithContentDescription("Terminal color scheme, dark theme: Solarized Darcula").assertExists()
    }

    @Test
    fun theBehaviorOfTheTerminalsIsChosenInItsPage() = runSkikoComposeUiTest(size = Size(1400f, 900f)) {
        show()
        goTo(SettingsPage.TERMINAL)
        assertEquals(BellMode.VISUAL, settings.bell)
        assertEquals(MinimumContrast.OFF, settings.minimumContrast)
        assertEquals(ScrollbackSize.DEFAULT, settings.scrollback)
        assertFalse(settings.boldIsBright)
        assertFalse(settings.copyOnSelect)
        assertTrue(settings.commandMarks, "The marks are on by default")
        assertTrue(settings.restoreContents, "On by default")

        clickOn("Bar")
        assertEquals(CursorShape.BAR, settings.cursorShape)
        clickOn("Blinking")
        assertFalse(settings.cursorBlink)
        // The bell's Off is the first, the contrast's the second.
        clickOn("Off", 0)
        assertEquals(BellMode.OFF, settings.bell)
        clickOn("Sound")
        assertEquals(BellMode.SOUND, settings.bell)
        clickOn("Both")
        assertEquals(BellMode.VISUAL_AND_SOUND, settings.bell)
        clickOn("Bold text uses bright colors")
        assertTrue(settings.boldIsBright)
        clickOn("4.5")
        assertEquals(MinimumContrast.NORMAL, settings.minimumContrast)
        clickOn("50")
        assertEquals(ScrollbackSize.LARGE, settings.scrollback)
        clickOn("Copy on select")
        assertTrue(settings.copyOnSelect)
        clickOn("Command marks")
        assertFalse(settings.commandMarks)
        clickOn("Restore terminal contents")
        assertFalse(settings.restoreContents)
    }

    @Test
    fun theKeyboardPageHasTheOptionKeysAndTheSecureEntry() = runComposeUiTest {
        show()
        goTo(SettingsPage.KEYBOARD)
        assertEquals(OptionKey.CHARACTERS, settings.optionKey, "Today's behavior is the default")

        clickOn("Left")
        assertEquals(OptionKey.LEFT_IS_ALT, settings.optionKey)
        clickOn("Both")
        assertEquals(OptionKey.BOTH_ARE_ALT, settings.optionKey)
        clickOn("Passwords")
        assertEquals(SecureInput.PASSWORD_PROMPTS, settings.secureInput)
    }

    @Test
    fun theShortcutsAreChangedInTheirPage() = runComposeUiTest {
        show()
        goTo(SettingsPage.SHORTCUTS)

        onNodeWithContentDescription("Change Add a project folder").performClick()
        onNodeWithContentDescription("Keyboard shortcuts").performKeyInput { withKeyDown(Key.MetaLeft) { pressKey(Key.E) } }
        waitForIdle()

        assertEquals("⌘E", shortcuts.label(AppShortcut.ADD_PROJECT))
    }

    @Test
    fun theUsageIsShownOrHiddenAndPutInThePanelOrInAWindowButNotWhileHidden() = runSkikoComposeUiTest(size = Size(1400f, 1600f)) {
        show()
        goTo(SettingsPage.AGENTS)

        onNodeWithText("Window").performClick()
        waitForIdle()
        assertTrue(settings.usageInWindow)
        onNodeWithText("Panel").performClick()
        waitForIdle()
        assertFalse(settings.usageInWindow)

        onNodeWithText("Show agent usage").performClick()
        waitForIdle()
        assertFalse(settings.showUsage)
        onNodeWithText("Window").performClick()
        waitForIdle()
        assertFalse(settings.usageInWindow, "The choice does nothing while the usage is hidden")
    }

    @Test
    fun theSoundOfAnAgentThatNeedsTheUserIsTurnedOffAndOn() = runSkikoComposeUiTest(size = Size(1400f, 1600f)) {
        show()
        goTo(SettingsPage.AGENTS)
        assertTrue(settings.attentionSound, "On by default")

        onNodeWithText("Sound when an agent needs you").performClick()
        assertFalse(settings.attentionSound)
        onNodeWithText("Sound when an agent needs you").performClick()
        assertTrue(settings.attentionSound)
    }

    // The text of the AI command's field.
    private fun ComposeUiTest.aiCommandText(): String =
        onNodeWithContentDescription("AI command").fetchSemanticsNode().config[SemanticsProperties.EditableText].text

    // The field always shows the command in use: the default is in it, the select puts an agent's command in it, and emptying it turns the AI off.
    @Test
    fun theAiCommandIsInItsFieldAndAnAgentsCommandIsChosenInTheSelect() = runSkikoComposeUiTest(size = Size(1400f, 1600f)) {
        show()
        goTo(SettingsPage.AGENTS)
        assertEquals(DEFAULT_AI, aiCommandText())
        onNodeWithContentDescription("AI agent: Claude Code").assertExists()

        onNodeWithContentDescription("AI agent: Claude Code").performClick()
        onNodeWithText("Codex").performClick()
        waitForIdle()
        assertEquals(CODEX_AI, aiCommandText())
        assertEquals(CODEX_AI, settings.aiCommand)
        onNodeWithContentDescription("AI agent: Codex").assertExists()

        onNodeWithContentDescription("AI agent: Codex").performClick()
        onNodeWithText("Claude Code (default)").performClick()
        waitForIdle()
        assertEquals(DEFAULT_AI, aiCommandText())
        assertNull(settings.aiCommand, "The default is not kept as the user's: a default that changes is followed")

        onNodeWithContentDescription("AI command").performTextInput(" --model sonnet")
        waitForIdle()
        assertEquals("$DEFAULT_AI --model sonnet", settings.aiCommand)
        onNodeWithContentDescription("AI agent: Custom").assertExists()

        onNodeWithContentDescription("AI command").performTextClearance()
        waitForIdle()
        assertEquals("", settings.aiCommand, "Empty is the AI turned off")
        onNodeWithContentDescription("AI agent: Off").assertExists()

        onNodeWithContentDescription("AI agent: Off").performClick()
        onNodeWithText("Codex").performClick()
        waitForIdle()
        onNodeWithContentDescription("AI agent: Codex").performClick()
        onNodeWithText("Off").performClick()
        waitForIdle()
        assertEquals("", aiCommandText())
        assertEquals("", settings.aiCommand)
    }

    // The agents are found after the window opens: a field that follows the default shows it once it is known, and one that is off stays off.
    @Test
    fun theFieldShowsTheDefaultOnceTheAgentsAreFound() = runComposeUiTest {
        agents = emptySet()
        page = SettingsPage.AGENTS
        show()
        assertEquals("", aiCommandText())
        onNodeWithText("No installed agent has an AI command: type one. Empty, the AI is off.").assertExists()

        agents = setOf(Agent.CODEX, Agent.CLAUDE)
        waitForIdle()
        assertEquals(DEFAULT_AI, aiCommandText())
        assertNull(settings.aiCommand)
    }

    @Test
    fun anAiCommandThatIsOffStaysOffWhenTheAgentsAreFound() = runComposeUiTest {
        agents = emptySet()
        settings = Settings(aiCommand = "")
        page = SettingsPage.AGENTS
        show()
        agents = setOf(Agent.CLAUDE)
        waitForIdle()
        assertEquals("", aiCommandText())
        assertEquals("", settings.aiCommand)
    }

    @Test
    fun whatIsTypedIsTheSettingUnlessItIsTheDefaultOrEmptyWithoutADefault() {
        assertEquals("make run", aiCommandSetting("  make run \n", DEFAULT_AI))
        assertEquals("", aiCommandSetting("   ", DEFAULT_AI))
        assertNull(aiCommandSetting("   ", null))
        assertNull(aiCommandSetting(" $DEFAULT_AI ", DEFAULT_AI))
        assertEquals(DEFAULT_AI, aiCommandSetting(DEFAULT_AI, null))
    }

    @Test
    fun theSizeIsWrittenWithoutADecimalWhenItIsWhole() {
        assertEquals("12.5 px", formatSize(12.5f))
        assertEquals("13 px", formatSize(13f))
        assertEquals("8 px", formatSize(8f))
    }

    private companion object {
        const val DEFAULT_AI = "claude -p \"\$PROMPT\""
        const val CODEX_AI = "codex exec --skip-git-repo-check \"\$PROMPT\""
    }
}

/** Each page of the settings, drawn in `build/renders` (`settings-*.png`) in both themes: look at them. */
class SettingsPagesRenderTest {
    @Test
    fun drawsEveryPageInBothThemes() {
        for (dark in listOf(false, true)) {
            for (page in SettingsPage.entries) {
                val rendered = app.katty.ui.render("settings-${page.name.lowercase()}-${if (dark) "dark" else "light"}", 1640, 1200, dark = dark) {
                    SettingsContent(
                        page, onPage = {},
                        settings = Settings(layout = LayoutMode.HORIZONTAL, theme = if (dark) ThemeMode.DARK else ThemeMode.LIGHT),
                        onChange = {}, schemes = ITermColorSchemes.entries, fontFamilies = emptyList(),
                        shortcuts = ShortcutMap.DEFAULT, onShortcuts = {}, agents = setOf(Agent.CLAUDE), onAbout = {},
                    )
                }
                // The panel of the page is drawn.
                assertTrue(rendered.count(if (dark) 0x2B2D30 else 0xFFFFFF) > 20_000, "${page.title}, dark: $dark")
            }
        }
    }

    // A window that is shorter than a page: the page scrolls, and its scrollbar is drawn, which the image shows.
    @Test
    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    fun drawsTheScrollbarOfAPageInAShortWindow() {
        val scene = androidx.compose.ui.ImageComposeScene(width = 1640, height = 600, density = androidx.compose.ui.unit.Density(2f)) {
            KattyTheme(false) {
                SettingsContent(
                    SettingsPage.TERMINAL, onPage = {}, settings = Settings(), onChange = {}, schemes = emptyList(), fontFamilies = emptyList(),
                    shortcuts = ShortcutMap.DEFAULT, onShortcuts = {}, agents = emptySet(), onAbout = {},
                )
            }
        }
        try {
            scene.render(0)
            val inside = androidx.compose.ui.geometry.Offset(1000f, 300f)
            scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Move, inside)
            scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Scroll, inside, scrollDelta = androidx.compose.ui.geometry.Offset(0f, 2f))
            // Jewel draws the thumb some frames after the scroll, whatever the time between them.
            for (frame in 1..4) scene.render(frame * 100_000_000L)
            val image = scene.render(500_000_000)
            java.io.File("build/renders").apply { mkdirs() }.resolve("settings-scrolled.png")
                .writeBytes(image.encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)!!.bytes)
            // The thumb, a gray bar along the right edge of the white page.
            val bitmap = org.jetbrains.skia.Bitmap.makeFromImage(image)
            val thumb = (0 until 600).map { y -> (1620 until 1640).map { x -> bitmap.getColor(x, y) and 0xFFFFFF } }.flatten().filter { it != 0xFFFFFF && it < 0xF0F0F0 }
            assertTrue(thumb.size > 50, "The scrollbar is drawn")
        } finally {
            scene.close()
        }
    }
}
