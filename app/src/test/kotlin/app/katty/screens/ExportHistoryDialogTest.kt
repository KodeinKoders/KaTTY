@file:OptIn(ExperimentalTestApi::class)

package app.katty.screens

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import app.katty.terminals.ExportFormat
import app.katty.ui.KattyTheme
import app.katty.ui.render
import kotlin.test.Test
import kotlin.test.assertEquals

private const val PROGRAM_SCREEN =
    "Only what the program shows now: it draws on a screen of its own, which keeps no history. Its own export, if it has one, has the rest."

class ExportHistoryDialogTest {
    private val chosen = mutableListOf<ExportFormat?>()
    private val title = "Export the history of “api build”"

    private fun androidx.compose.ui.test.ComposeUiTest.show(initial: ExportFormat = ExportFormat.TEXT, alternateScreen: Boolean = false) {
        setContent {
            KattyTheme(false) { ExportHistoryDialog("api build", alternateScreen, initial, onChoose = { chosen += it }, onCancel = { chosen += null }) }
        }
        waitForIdle()
    }

    private fun androidx.compose.ui.test.ComposeUiTest.press(key: Key) {
        onNodeWithContentDescription(title).performKeyInput { pressKey(key) }
        waitForIdle()
    }

    @Test
    fun itListsTheFormatsWithTheirExtensionsAndStartsOnTheLastOne() = runComposeUiTest {
        show(initial = ExportFormat.HTML)

        onNodeWithText(title).assertExists()
        onNodeWithText("Everything the terminal keeps: its history and its screen.").assertExists()
        onNodeWithText(".txt").assertExists()
        onNodeWithText(".html").assertExists()
        onNodeWithText(".ans").assertExists()
        onNodeWithText("With the escape sequences, that cat or less -R show in color").assertExists()
        onNodeWithContentDescription("HTML").assertIsSelected()
        onNodeWithContentDescription("Text").assertIsNotSelected()
    }

    // A program on the alternate screen (vim, an agent that draws full screen) keeps its history itself: the terminal has only its screen.
    @Test
    fun whileAProgramHasTheAlternateScreenItSaysThatOnlyItsScreenIsExported() = runComposeUiTest {
        show(alternateScreen = true)

        onNodeWithText(PROGRAM_SCREEN).assertExists()
        onNodeWithText("Everything the terminal keeps: its history and its screen.").assertDoesNotExist()
    }

    @Test
    fun theArrowsMoveAndEnterChooses() = runComposeUiTest {
        show()

        press(Key.DirectionDown)
        press(Key.DirectionDown)
        onNodeWithContentDescription("ANSI").assertIsSelected()
        press(Key.DirectionDown)
        onNodeWithContentDescription("Text").assertIsSelected()
        press(Key.DirectionUp)
        press(Key.Enter)

        assertEquals(listOf<ExportFormat?>(ExportFormat.ANSI), chosen)
    }

    @Test
    fun aClickChoosesAndEscapeCancels() = runComposeUiTest {
        show()

        onNodeWithContentDescription("HTML").performClick()
        press(Key.Escape)
        onNodeWithText("Cancel").performClick()

        assertEquals(listOf(ExportFormat.HTML, null, null), chosen)
    }

    @Test
    fun aMessageClosesWithOkEnterOrEscape() = runComposeUiTest {
        var closed = 0
        setContent { KattyTheme(false) { MessageDialog("Could not export the history", "/x could not be written.") { closed++ } } }

        onNodeWithText("/x could not be written.").assertExists()
        onNodeWithText("OK").performClick()
        onNodeWithContentDescription("Could not export the history").performKeyInput { pressKey(Key.Enter) }
        onNodeWithContentDescription("Could not export the history").performKeyInput { pressKey(Key.Escape) }

        assertEquals(3, closed)
    }

    @Test
    fun drawsTheDialogInBothThemes() {
        for (dark in listOf(false, true)) {
            val theme = if (dark) "dark" else "light"
            render("export-history-$theme", 1000, 700, dark = dark) {
                ExportHistoryDialog("api build", alternateScreen = false, ExportFormat.HTML, onChoose = {}, onCancel = {})
            }
            render("export-program-screen-$theme", 1000, 700, dark = dark) {
                ExportHistoryDialog("claude", alternateScreen = true, ExportFormat.HTML, onChoose = {}, onCancel = {})
            }
            render("export-failed-$theme", 1000, 500, dark = dark) {
                MessageDialog("Could not export the history", "/Users/me/Downloads/api build.html could not be written: Permission denied.") {}
            }
        }
    }
}
