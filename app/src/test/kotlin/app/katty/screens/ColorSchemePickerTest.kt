@file:OptIn(ExperimentalTestApi::class)

package app.katty.screens

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import app.katty.terminal.TerminalColor
import app.katty.terminal.themes.ITermColorSchemeInfo
import app.katty.terminal.themes.ITermColorSchemes
import app.katty.ui.KattyTheme
import app.katty.ui.render
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ColorSchemePickerTest {
    private fun entry(name: String, dark: Boolean) = ITermColorSchemeInfo(
        name, dark, TerminalColor(if (dark) 0x101010 else 0xF0F0F0), TerminalColor(if (dark) 0xF0F0F0 else 0x101010), List(6) { TerminalColor(0x808080) },
    )

    private val entries = listOf(entry("Dracula", true), entry("Solarized Dark", true), entry("Solarized Light", false), entry("Atom One Light", false))

    @Test
    fun filtersByTheTextAnywhereInTheNameWithoutRegardForTheCase() {
        assertEquals(listOf("Solarized Dark", "Solarized Light"), filterSchemes(entries, "solar", SchemeFilter.ALL).map { it.name })
        assertEquals(listOf("Solarized Light", "Atom One Light"), filterSchemes(entries, "LIGHT", SchemeFilter.ALL).map { it.name })
        assertEquals(listOf("Dracula"), filterSchemes(entries, "  dracula ", SchemeFilter.ALL).map { it.name })
        assertEquals(emptyList(), filterSchemes(entries, "nothing", SchemeFilter.ALL))
    }

    @Test
    fun filtersByTheKindOfScheme() {
        assertEquals(listOf("Dracula", "Solarized Dark"), filterSchemes(entries, "", SchemeFilter.DARK).map { it.name })
        assertEquals(listOf("Solarized Light", "Atom One Light"), filterSchemes(entries, "", SchemeFilter.LIGHT).map { it.name })
        assertEquals(listOf("Solarized Dark"), filterSchemes(entries, "dark", SchemeFilter.DARK).map { it.name })
        assertEquals(emptyList(), filterSchemes(entries, "dracula", SchemeFilter.LIGHT))
    }

    @Test
    fun listsTheSchemesWithTheirCountsAndChoosesOneAtOnce() = runComposeUiTest {
        val chosen = mutableListOf<String?>()
        setContent { KattyTheme(false) { ColorSchemePicker(entries, selected = "Dracula", onSelect = { chosen += it }, onClose = {}) } }

        onNodeWithText("All 4").assertExists()
        onNodeWithText("Light 2").assertExists()
        onNodeWithText("Dark 2").assertExists()
        onNodeWithContentDescription("Dracula").assertIsSelected()
        onNodeWithContentDescription("Solarized Light").performClick()

        assertEquals(listOf<String?>("Solarized Light"), chosen)
    }

    @Test
    fun searchingNarrowsTheList() = runComposeUiTest {
        setContent { KattyTheme(false) { ColorSchemePicker(entries, selected = "Dracula", onSelect = {}, onClose = {}) } }

        onNode(hasSetTextAction()).performTextInput("atom")

        onNodeWithContentDescription("Atom One Light").assertExists()
        assertEquals(0, onAllNodesWithContentDescription("Dracula").fetchSemanticsNodes().size)
    }

    @Test
    fun saysWhenNothingMatches() = runComposeUiTest {
        setContent { KattyTheme(false) { ColorSchemePicker(entries, selected = "Dracula", onSelect = {}, onClose = {}) } }

        onNode(hasSetTextAction()).performTextInput("zzzz")

        onNodeWithText("No schemes match your search.").assertExists()
    }

    @Test
    fun aTerminalThatFollowsTheDefaultHasItFirst() = runComposeUiTest {
        val chosen = mutableListOf<String?>()
        setContent { KattyTheme(false) { ColorSchemePicker(entries, selected = null, defaultName = "Dracula", onSelect = { chosen += it }, onClose = {}) } }

        onNodeWithContentDescription("Default").assertIsSelected()
        onNodeWithContentDescription("Solarized Dark").performClick()
        onNodeWithContentDescription("Default").performClick()

        assertEquals(listOf("Solarized Dark", null), chosen)
    }

    @Test
    fun theCloseButtonCloses() = runComposeUiTest {
        var closed = false
        setContent { KattyTheme(false) { ColorSchemePicker(entries, selected = "Dracula", onSelect = {}, onClose = { closed = true }) } }

        onNodeWithContentDescription("Close").performClick()

        assertTrue(closed)
    }

    @Test
    fun drawsThePickerWithTheRealSchemes() {
        val names = listOf("Dracula", "iTerm2 Solarized Dark", "iTerm2 Solarized Light", "Nord", "Gruvbox Dark")
        val real = ITermColorSchemes.entries
        for (dark in listOf(false, true)) {
            val rendered = render("color-scheme-picker-${if (dark) "dark" else "light"}", 1920, 1300, dark = dark) {
                ColorSchemePicker(real, selected = names[0], onSelect = {}, onClose = {})
            }
            assertTrue(rendered.count(0x282A36, tolerance = 4) > 1000, "The preview is drawn in the colors of Dracula")
        }
    }
}
