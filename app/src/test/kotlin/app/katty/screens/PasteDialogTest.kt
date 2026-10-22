@file:OptIn(ExperimentalTestApi::class)

package app.katty.screens

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import app.katty.ui.KattyTheme
import kotlin.test.Test
import kotlin.test.assertEquals

class PasteDialogTest {
    @Test
    fun showsTheLinesThatWouldRun() = runComposeUiTest {
        setContent { KattyTheme(false) { PasteDialog("echo hi\nrm -rf build\n", onConfirm = {}, onCancel = {}) } }

        onNodeWithText("Paste and run?").assertExists()
        onNodeWithText("This text has 2 lines. Pasted here, each of them could run as a command.").assertExists()
        onNodeWithText("echo hi").assertExists()
        onNodeWithText("rm -rf build").assertExists()
    }

    @Test
    fun aLongPasteShowsItsFirstLinesAndCountsTheOthers() = runComposeUiTest {
        setContent { KattyTheme(false) { PasteDialog((1..10).joinToString("\n") { "line $it" }, onConfirm = {}, onCancel = {}) } }

        onNodeWithText("line 6").assertExists()
        onNodeWithText("line 7").assertDoesNotExist()
        onNodeWithText("…and 4 more lines").assertExists()
    }

    @Test
    fun enterPastesAndEscapeDoesNot() = runComposeUiTest {
        val answers = mutableListOf<String>()
        setContent { KattyTheme(false) { PasteDialog("a\rb", onConfirm = { answers += "paste" }, onCancel = { answers += "cancel" }) } }
        waitForIdle()

        onNodeWithText("Paste").performKeyInput { pressKey(Key.Enter) }
        onNodeWithText("Paste").performKeyInput { pressKey(Key.Escape) }
        onNodeWithText("Cancel").performClick()

        assertEquals(listOf("paste", "cancel", "cancel"), answers)
    }

    @Test
    fun theLinesAreSplitAtAnyBreakAndTheControlCharactersAreShown() {
        assertEquals(listOf("a", "b", "c", "d"), pasteLines("a\r\nb\rc\nd\n"))
        assertEquals(listOf("x ␛[201~y"), pasteLines("x\t\u001b[201~y"))
        assertEquals(listOf("one"), pasteLines("one"))
    }
}
