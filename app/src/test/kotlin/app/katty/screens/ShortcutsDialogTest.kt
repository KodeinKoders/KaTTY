@file:OptIn(ExperimentalTestApi::class)

package app.katty.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import app.katty.AppShortcut
import app.katty.KeyBinding
import app.katty.ShortcutGroup
import app.katty.ShortcutMap
import app.katty.ui.KattyTheme
import java.awt.event.KeyEvent as Awt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShortcutRecordingTest {
    private val map = ShortcutMap.DEFAULT
    private fun press(keyCode: Int, meta: Boolean = true, ctrl: Boolean = false, alt: Boolean = false, shift: Boolean = false) = KeyBinding(keyCode, meta, ctrl, alt, shift)

    @Test
    fun aModifierAloneWaitsForTheRestOfTheCombination() {
        assertEquals(RecordingResult.Wait, record(map, AppShortcut.FIND, null, press(Awt.VK_META)))
        assertEquals(RecordingResult.Wait, record(map, AppShortcut.FIND, null, press(Awt.VK_SHIFT, meta = false, shift = true)))
    }

    @Test
    fun escapeAloneGivesUpAndBackspaceOrDeleteAloneUnbinds() {
        assertEquals(RecordingResult.Cancel, record(map, AppShortcut.FIND, null, press(Awt.VK_ESCAPE, meta = false)))
        assertEquals(RecordingResult.Done(map.with(AppShortcut.FIND, null)), record(map, AppShortcut.FIND, null, press(Awt.VK_BACK_SPACE, meta = false)))
        assertEquals(RecordingResult.Done(map.with(AppShortcut.FIND, null)), record(map, AppShortcut.FIND, null, press(Awt.VK_DELETE, meta = false)))
        // With Command they are keys like the others: ⌘⌫ is the shell's, so it is refused.
        assertTrue(record(map, AppShortcut.FIND, null, press(Awt.VK_BACK_SPACE)) is RecordingResult.Refused)
    }

    @Test
    fun aFreeCombinationWithCommandIsTheNewKeys() {
        val result = record(map, AppShortcut.SPLIT_MAIN, null, press(Awt.VK_E))

        assertEquals(RecordingResult.Done(map.with(AppShortcut.SPLIT_MAIN, KeyBinding(Awt.VK_E))), result)
    }

    @Test
    fun aCombinationWithoutCommandOrOneThatIsReservedIsRefusedAndSaysWhy() {
        val withoutCommand = record(map, AppShortcut.FIND, null, press(Awt.VK_E, meta = false)) as RecordingResult.Refused
        assertTrue("⌘" in withoutCommand.message && "never see" in withoutCommand.message, withoutCommand.message)
        assertEquals(null, withoutCommand.pending)

        val reserved = record(map, AppShortcut.FIND, null, press(Awt.VK_C)) as RecordingResult.Refused
        assertTrue("copies" in reserved.message, reserved.message)
    }

    @Test
    fun keysThatAnotherShortcutHasNeedASecondPressAndTakeThemFromIt() {
        val first = record(map, AppShortcut.FIND, null, press(Awt.VK_D)) as RecordingResult.Refused

        assertTrue("⌘D" in first.message && "Split in the main direction of the layout" in first.message && "again" in first.message, first.message)
        assertEquals(KeyBinding(Awt.VK_D), first.pending)
        // Another combination is another question, and the same one again answers it.
        val other = record(map, AppShortcut.FIND, first.pending, press(Awt.VK_N)) as RecordingResult.Refused
        assertEquals(KeyBinding(Awt.VK_N), other.pending)
        val second = record(map, AppShortcut.FIND, first.pending, press(Awt.VK_D))
        assertEquals(RecordingResult.Done(map.with(AppShortcut.SPLIT_MAIN, null).with(AppShortcut.FIND, KeyBinding(Awt.VK_D))), second)
    }

    @Test
    fun aShortcutThatTakesDigitsKeepsTheModifiersOfTheDigitThatWasPressed() {
        val result = record(map, AppShortcut.PANE_NUMBER, null, press(Awt.VK_4, ctrl = true)) as RecordingResult.Done

        assertEquals("⌘⌃1…9", result.map.label(AppShortcut.PANE_NUMBER))
        assertTrue(record(map, AppShortcut.PANE_NUMBER, null, press(Awt.VK_E, ctrl = true)) is RecordingResult.Refused)
    }
}

class ShortcutRowsTest {
    private val map = ShortcutMap.DEFAULT

    @Test
    fun everyShortcutIsListedUnderItsGroupAndThenTheFixedKeys() {
        val rows = shortcutRows(map, "")

        assertEquals(AppShortcut.entries.toSet(), rows.filterIsInstance<ShortcutRow.Item>().map { it.shortcut }.toSet())
        assertEquals(ShortcutGroup.entries.map { it.title } + "Fixed keys", rows.filterIsInstance<ShortcutRow.Header>().map { it.title })
        assertEquals(FIXED_KEYS, rows.filterIsInstance<ShortcutRow.Fixed>())
        // Each shortcut is under the title of its group.
        var group: ShortcutGroup? = null
        for (row in rows) when (row) {
            is ShortcutRow.Header -> group = ShortcutGroup.entries.firstOrNull { it.title == row.title }
            is ShortcutRow.Item -> assertEquals(group, row.shortcut.group, "${row.shortcut}")
            is ShortcutRow.Fixed -> Unit
        }
    }

    @Test
    fun theSearchFindsByTheActionByTheKeysAndByTheGroupWithoutRegardForTheCase() {
        fun shortcuts(query: String) = shortcutRows(map, query).filterIsInstance<ShortcutRow.Item>().map { it.shortcut }

        assertEquals(setOf(AppShortcut.SPLIT_MAIN, AppShortcut.SPLIT_OTHER), shortcuts("SPLIT").toSet())
        assertTrue(AppShortcut.RENAME_WORKSPACE in shortcuts("⌘⇧F2"))
        assertTrue(shortcuts("In a terminal").containsAll(listOf(AppShortcut.FIND, AppShortcut.CLEAR_SCREEN)))
        assertEquals(emptyList(), shortcutRows(map, "zzzz"))
    }

    @Test
    fun theFixedKeysAreFoundToo() {
        val rows = shortcutRows(map, "prompt")

        assertTrue(rows.any { it is ShortcutRow.Fixed && "prompt" in it.title })
        assertTrue(rows.any { it is ShortcutRow.Header && it.title == "Fixed keys" })
    }

    @Test
    fun anUnboundShortcutIsNotFoundByTheKeysItHadButByItsAction() {
        val unbound = map.with(AppShortcut.FIND, null)

        assertFalse(shortcutRows(unbound, "⌘F").filterIsInstance<ShortcutRow.Item>().any { it.shortcut == AppShortcut.FIND })
        assertTrue(shortcutRows(unbound, "find in the terminal").filterIsInstance<ShortcutRow.Item>().any { it.shortcut == AppShortcut.FIND })
    }
}

class ShortcutsDialogTest {
    private var map by mutableStateOf(ShortcutMap.DEFAULT)
    private var closed = 0

    private fun androidx.compose.ui.test.ComposeUiTest.show() {
        setContent { KattyTheme(false) { ShortcutsDialog(map, onChange = { map = it }, onClose = { closed++ }) } }
        waitForIdle()
    }

    private fun androidx.compose.ui.test.ComposeUiTest.press(key: Key) {
        onNodeWithContentDescription("Keyboard shortcuts").performKeyInput { pressKey(key) }
        waitForIdle()
    }

    private fun androidx.compose.ui.test.ComposeUiTest.command(key: Key) {
        onNodeWithContentDescription("Keyboard shortcuts").performKeyInput { withKeyDown(Key.MetaLeft) { pressKey(key) } }
        waitForIdle()
    }

    private fun androidx.compose.ui.test.ComposeUiTest.count(text: String) = onAllNodesWithText(text).fetchSemanticsNodes().size

    @Test
    fun listsTheShortcutsWithTheirKeysAndTheyCanBeChanged() = runComposeUiTest {
        show()

        onNodeWithText("Projects and workspaces").assertExists()
        onNodeWithContentDescription("Add a project folder").assertExists()
        onNodeWithContentDescription("Change Add a project folder").assertExists()
        // Nothing has been changed, so nothing can be reset.
        assertEquals(0, onAllNodesWithContentDescription("Reset Add a project folder").fetchSemanticsNodes().size)
    }

    @Test
    fun enterThenTheNewKeysChangeTheChosenShortcut() = runComposeUiTest {
        show()

        press(Key.Enter)
        onNodeWithText("Press the new keys…").assertExists()
        command(Key.E)

        assertEquals("⌘E", map.label(AppShortcut.ADD_PROJECT))
        assertEquals(0, count("Press the new keys…"))
        onNodeWithContentDescription("Reset Add a project folder").assertExists()
    }

    @Test
    fun theArrowsChooseWhichShortcutIsChanged() = runComposeUiTest {
        show()

        press(Key.DirectionDown)
        press(Key.Enter)
        command(Key.E)

        assertEquals("⌘E", map.label(AppShortcut.NEW_WORKSPACE), "The second one of the list")
        assertEquals("⌘O", map.label(AppShortcut.ADD_PROJECT))
    }

    @Test
    fun escapeGivesUpTheChangeAndThenClosesTheDialog() = runComposeUiTest {
        show()

        press(Key.Enter)
        press(Key.Escape)
        assertEquals(0, closed, "The first Escape gave up the change")
        assertEquals(0, count("Press the new keys…"))
        assertEquals(ShortcutMap.DEFAULT, map)
        press(Key.Escape)

        assertEquals(1, closed)
    }

    @Test
    fun backspaceWhileChangingRemovesTheShortcut() = runComposeUiTest {
        show()

        press(Key.Enter)
        press(Key.Backspace)

        assertEquals("", map.label(AppShortcut.ADD_PROJECT))
        onNodeWithContentDescription("No shortcut").assertExists()
    }

    @Test
    fun keysWithoutCommandAndReservedOnesAreRefusedWithTheReasonAndTheChangeGoesOn() = runComposeUiTest {
        show()

        press(Key.Enter)
        press(Key.E)
        onNodeWithText("A shortcut has to include ⌘, so that the programs in your terminals never see it.").assertExists()
        command(Key.C)
        assertTrue(count("⌘C copies, pastes and selects all in a terminal.") == 1)
        assertEquals(ShortcutMap.DEFAULT, map)
        // Still waiting: the next valid keys are the shortcut.
        command(Key.E)

        assertEquals("⌘E", map.label(AppShortcut.ADD_PROJECT))
    }

    @Test
    fun keysThatAnotherShortcutHasAskForASecondPress() = runComposeUiTest {
        show()

        press(Key.Enter)
        command(Key.N)
        onNodeWithText("⌘N is used by “New workspace”. Press it again to take it, or Esc to cancel.").assertExists()
        assertEquals(ShortcutMap.DEFAULT, map)
        command(Key.N)

        assertEquals("⌘N", map.label(AppShortcut.ADD_PROJECT))
        assertEquals("", map.label(AppShortcut.NEW_WORKSPACE), "It was taken from the other one")
    }

    @Test
    fun commandBackspaceBringsTheChosenShortcutBackToItsDefault() = runComposeUiTest {
        map = ShortcutMap.DEFAULT.with(AppShortcut.ADD_PROJECT, KeyBinding(Awt.VK_E))
        show()

        command(Key.Backspace)

        assertEquals("⌘O", map.label(AppShortcut.ADD_PROJECT))
    }

    @Test
    fun theButtonsChangeAndResetWithTheMouse() = runComposeUiTest {
        map = ShortcutMap.DEFAULT.with(AppShortcut.ADD_PROJECT, KeyBinding(Awt.VK_E))
        show()

        onNodeWithContentDescription("Reset Add a project folder").performClick()
        assertEquals("⌘O", map.label(AppShortcut.ADD_PROJECT))

        onNodeWithContentDescription("Change New workspace").performClick()
        command(Key.U)
        assertEquals("⌘U", map.label(AppShortcut.NEW_WORKSPACE), "The keys go to the dialog although the click took the focus to a button")
    }

    @Test
    fun resetAllPutsEveryShortcutBack() = runComposeUiTest {
        map = ShortcutMap.DEFAULT.with(AppShortcut.ADD_PROJECT, KeyBinding(Awt.VK_E)).with(AppShortcut.FIND, null)
        show()

        onNodeWithContentDescription("Reset all shortcuts").performClick()

        assertEquals(ShortcutMap.DEFAULT, map)
    }

    @Test
    fun theSearchNarrowsTheList() = runComposeUiTest {
        show()

        onNode(hasSetTextAction()).performTextInput("split")
        waitForIdle()

        onNodeWithContentDescription("Split in the main direction of the layout").assertExists()
        assertEquals(0, onAllNodesWithContentDescription("Add a project folder").fetchSemanticsNodes().size)
        // The first that is left is the one that is chosen.
        press(Key.Enter)
        command(Key.U)
        assertEquals("⌘U", map.label(AppShortcut.SPLIT_MAIN))
    }

    @Test
    fun theCloseButtonAndEscapeCloseIt() = runComposeUiTest {
        show()

        onNodeWithContentDescription("Close").performClick()
        press(Key.Escape)

        assertEquals(2, closed)
    }

    @Test
    fun whileTheDialogChangesAShortcutTheKeysAreNotTypedInTheSearch() = runComposeUiTest {
        show()

        press(Key.Enter)
        // Plain keys are refused, and they are not typed in the search either: its placeholder is still there, which it only is while it is empty.
        press(Key.U)
        press(Key.V)

        onNodeWithText("Search by action or keys").assertExists()
        assertEquals(ShortcutMap.DEFAULT, map)
    }
}
