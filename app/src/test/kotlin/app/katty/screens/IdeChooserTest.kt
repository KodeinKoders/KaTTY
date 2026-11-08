@file:OptIn(ExperimentalTestApi::class)

package app.katty.screens

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import app.katty.core.Ide
import app.katty.terminal.TerminalFilePosition
import app.katty.ui.KattyTheme
import kotlin.test.Test
import kotlin.test.assertEquals

class IdeChooserTest {
    private val file = TerminalFilePosition("/Users/me/dev/api/src/Foo.kt", 42, 5)
    private val ides = listOf(Ide.VS_CODE, Ide.INTELLIJ_IDEA, Ide.XCODE)

    @Test
    fun aClickOnAnIdeOpensTheFileInItAndTheBoxSaysWhetherTheWorkspaceKeepsIt() = runComposeUiTest {
        val answers = mutableListOf<Pair<Ide, Boolean>>()
        setContent { KattyTheme(false) { OpenFileDialog(file, "refactor-auth", ides, onOpen = { ide, remember -> answers += ide to remember }, onCancel = {}) } }

        onNodeWithText("Open Foo.kt:42 in…").assertExists()
        onNodeWithContentDescription("Xcode").performClick()
        onNodeWithText("Always use it in “refactor-auth”").performClick()
        onNodeWithContentDescription("IntelliJ IDEA").performClick()

        assertEquals(listOf(Ide.XCODE to false, Ide.INTELLIJ_IDEA to true), answers)
    }

    @Test
    fun theArrowsAndEnterChooseAndEscapeCancels() = runComposeUiTest {
        val answers = mutableListOf<String>()
        setContent { KattyTheme(false) { OpenFileDialog(file, "api", ides, onOpen = { ide, _ -> answers += ide.name }, onCancel = { answers += "cancel" }) } }
        waitForIdle()

        val dialog = onNodeWithContentDescription("Open Foo.kt:42 in…")
        dialog.performKeyInput { pressKey(Key.DirectionDown); pressKey(Key.DirectionDown); pressKey(Key.Enter) }
        dialog.performKeyInput { pressKey(Key.DirectionDown); pressKey(Key.Enter) }
        dialog.performKeyInput { pressKey(Key.DirectionUp); pressKey(Key.Enter) }
        dialog.performKeyInput { pressKey(Key.Escape) }

        assertEquals(listOf("XCODE", "VS_CODE", "XCODE", "cancel"), answers, "Down twice from the first; down again goes round to the first, and up round to the last")
    }

    @Test
    fun aClickOutsideTheDialogCancelsItAndOneInsideDoesNot() = runComposeUiTest {
        var cancelled = 0
        setContent { KattyTheme(false) { OpenFileDialog(file, "api", ides, onOpen = { _, _ -> }, onCancel = { cancelled++ }) } }
        waitForIdle()

        onNodeWithText("Open Foo.kt:42 in…").performClick()
        assertEquals(0, cancelled, "A click in the dialog, where nothing takes it")
        onRoot().performTouchInput { click(Offset(5f, 5f)) }
        assertEquals(1, cancelled)
    }

    @Test
    fun theQuestionNamesTheFileWithItsLineOrTheFolder() {
        assertEquals("Foo.kt:42", fileLabel(file))
        assertEquals("Foo.kt", fileLabel(file.copy(line = null, column = null)))
        assertEquals("src/", fileLabel(TerminalFilePosition("/Users/me/dev/api/src", directory = true)))
    }

    @Test
    fun theIdeOfAWorkspaceIsCheckedAndOneCanBeChosenOrNone() = runComposeUiTest {
        val chosen = mutableListOf<Ide?>()
        setContent { KattyTheme(false) { WorkspaceIdeDialog("api", ides, Ide.INTELLIJ_IDEA, onChoose = { chosen += it }, onCancel = {}) } }

        onNodeWithContentDescription("IntelliJ IDEA").assertIsSelected()
        onNodeWithContentDescription(ASK_EVERY_TIME).performClick()
        onNodeWithContentDescription("Visual Studio Code").performClick()

        assertEquals(listOf(null, Ide.VS_CODE), chosen)
    }
}
