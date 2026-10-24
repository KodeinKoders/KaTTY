@file:OptIn(ExperimentalTestApi::class)

package app.katty.screens

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import app.katty.core.LayoutMode
import app.katty.core.Settings
import app.katty.core.ThemeMode
import app.katty.ui.KattyTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MainMenuTest {
    private var settings by mutableStateOf(Settings())
    private val picked = mutableListOf<String>()

    private fun androidx.compose.ui.test.ComposeUiTest.show(families: List<String> = listOf("Menlo", "Monaco")) {
        setContent {
            KattyTheme(dark = false) {
                MainMenu(
                    settings = settings,
                    onChange = { change -> settings = change(settings) },
                    colorScheme = null,
                    fontFamilies = families,
                    onPickColorScheme = { picked += "colorScheme" },
                    onDismiss = { picked += "dismiss" },
                )
            }
        }
    }

    @Test
    fun hasTheSettingsOfTheDesign() = runComposeUiTest {
        show()

        onNodeWithText("LAYOUT").assertExists()
        onNodeWithText("Auto uses Vertical when the window is taller than it is wide.").assertExists()
        onNodeWithText("THEME").assertExists()
        onNodeWithText("INTERFACE TEXT SIZE").assertExists()
        onNodeWithText("100%").assertExists()
        onNodeWithText("TERMINAL COLOR SCHEME").assertExists()
        onNodeWithText("FONT FAMILY").assertExists()
        onNodeWithText("Font ligatures").assertExists()
        onNodeWithText("Shows sequences like => and != as single glyphs.").assertExists()
        onNodeWithText("14 px").assertExists()
    }

    @Test
    fun theLayoutAndTheThemeAreChosenAmongTheirOptions() = runComposeUiTest {
        show()

        onNodeWithText("Vertical").performClick()
        onNodeWithText("Dark").performClick()
        waitForIdle()

        assertEquals(LayoutMode.VERTICAL, settings.layout)
        assertEquals(ThemeMode.DARK, settings.theme)
        onNodeWithText("System").performClick()
        assertEquals(ThemeMode.SYSTEM, settings.theme)
    }

    @Test
    fun theFontSizeGoesUpAndDownByHalfAPixel() = runComposeUiTest {
        show()

        onNodeWithContentDescription("Increase font size").performClick()
        onNodeWithContentDescription("Increase font size").performClick()
        assertEquals(15f, settings.fontSize)
        onNodeWithContentDescription("Decrease font size").performClick()
        assertEquals(14.5f, settings.fontSize)
        waitForIdle()
        onNodeWithText("14.5 px").assertExists()
    }

    @Test
    fun ligaturesAreTurnedOffAndOn() = runComposeUiTest {
        show()

        onNodeWithText("Font ligatures").performClick()
        assertFalse(settings.ligatures)
        onNodeWithText("Font ligatures").performClick()
        assertTrue(settings.ligatures)
    }

    @Test
    fun theColorSchemeFieldOpensThePicker() = runComposeUiTest {
        show()

        onNodeWithContentDescription("Terminal color scheme: Alabaster").performClick()

        assertEquals(listOf("colorScheme"), picked)
    }

    @Test
    fun theFontIsChosenAmongTheFamiliesOfTheSystemOrTheBundledOne() = runComposeUiTest {
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
    fun theChosenLayoutIsMarked() = runComposeUiTest {
        settings = Settings(layout = LayoutMode.HORIZONTAL)
        show()

        onNodeWithText("Horizontal").assertIsSelected()
    }

    @Test
    fun theSizeIsWrittenWithoutADecimalWhenItIsWhole() {
        assertEquals("12.5 px", formatSize(12.5f))
        assertEquals("13 px", formatSize(13f))
        assertEquals("8 px", formatSize(8f))
    }

    @Test
    fun theMenuOpensTheDialogOfTheShortcuts() = runComposeUiTest {
        val opened = mutableListOf<String>()
        setContent {
            KattyTheme(dark = false) {
                MainMenu(
                    settings = settings, onChange = { change -> settings = change(settings) }, colorScheme = null, fontFamilies = emptyList(),
                    onPickColorScheme = {}, onDismiss = {}, onEditShortcuts = { opened += "shortcuts" },
                )
            }
        }

        onNodeWithText("KEYBOARD").assertExists()
        onNodeWithContentDescription("Edit the keyboard shortcuts").performClick()

        assertEquals(listOf("shortcuts"), opened)
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
}

class MainMenuRenderTest {
    @Test
    fun drawsTheMenuInBothThemes() {
        for (dark in listOf(false, true)) {
            val rendered = app.katty.ui.render("main-menu-${if (dark) "dark" else "light"}", 640, 1300, dark = dark) {
                androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize().padding(8.dp)) {
                    MainMenu(
                        settings = Settings(layout = LayoutMode.HORIZONTAL, theme = if (dark) ThemeMode.DARK else ThemeMode.LIGHT),
                        onChange = {},
                        colorScheme = app.katty.terminal.themes.ITermColorSchemes.entries.first { it.name == "Dracula" },
                        fontFamilies = emptyList(),
                        onPickColorScheme = {},
                        onDismiss = {},
                    )
                }
            }
            // The panel of the menu is drawn: a popup that is not shown would leave the window empty.
            assertTrue(rendered.count(if (dark) 0x2B2D30 else 0xFFFFFF) > 20_000)
        }
    }
}
