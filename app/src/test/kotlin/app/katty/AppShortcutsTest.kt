@file:OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)

package app.katty

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AppShortcutsTest {
    @kotlin.test.AfterTest
    fun restoreTheShortcuts() {
        Shortcuts.map = ShortcutMap.DEFAULT
    }

    private fun down(
        key: Key, meta: Boolean = true, shift: Boolean = false, alt: Boolean = false, ctrl: Boolean = false, type: KeyEventType = KeyEventType.KeyDown,
    ) = KeyEvent(key = key, type = type, isMetaPressed = meta, isShiftPressed = shift, isAltPressed = alt, isCtrlPressed = ctrl)

    @Test
    fun commandAndAKeyIsAShortcutOfTheWindow() {
        assertEquals(AppShortcut.ADD_PROJECT, down(Key.O).appShortcut())
        assertEquals(AppShortcut.NEW_WORKSPACE, down(Key.N).appShortcut())
        assertEquals(AppShortcut.CLOSE_TERMINAL, down(Key.W).appShortcut())
        assertEquals(AppShortcut.CLOSE_WORKSPACE, down(Key.W, shift = true).appShortcut())
        assertEquals(AppShortcut.RESTART_TERMINAL, down(Key.R).appShortcut())
        assertEquals(AppShortcut.PALETTE, down(Key.K).appShortcut())
        assertEquals(AppShortcut.JUMP_TO_WAITING, down(Key.J, shift = true).appShortcut())
        assertEquals(AppShortcut.CLEAR_SCREEN, down(Key.L).appShortcut())
        assertEquals(AppShortcut.FIND, down(Key.F).appShortcut())
    }

    @Test
    fun jOpensTheListOfAgentsThatNeedTheUserAndWithShiftJumpsToTheNextOne() {
        assertEquals(AppShortcut.ATTENTION, down(Key.J).appShortcut())
        assertEquals(AppShortcut.JUMP_TO_WAITING, down(Key.J, shift = true).appShortcut())
    }

    @Test
    fun bTogglesTheSidebarAndWithShiftTheBoard() {
        assertEquals(AppShortcut.SIDEBAR, down(Key.B).appShortcut())
        assertEquals(AppShortcut.BOARD, down(Key.B, shift = true).appShortcut())
        assertNull(down(Key.B, meta = false).appShortcut())
    }

    @Test
    fun theSplitWithoutShiftIsTheMainOneAndTheOneWithShiftTheOther() {
        assertEquals(AppShortcut.SPLIT_MAIN, down(Key.D).appShortcut())
        assertEquals(AppShortcut.SPLIT_OTHER, down(Key.D, shift = true).appShortcut())
    }

    @Test
    fun f2RenamesTheTerminalAndWithShiftTheWorkspace() {
        assertEquals(AppShortcut.RENAME_TERMINAL, down(Key.F2).appShortcut())
        assertEquals(AppShortcut.RENAME_WORKSPACE, down(Key.F2, shift = true).appShortcut())
        assertNull(down(Key.F2, meta = false).appShortcut(), "F2 alone is the program's")
    }

    @Test
    fun commandAndOptionAndAnArrowIsTheNeighbouringPane() {
        assertEquals(AppShortcut.FOCUS_LEFT, down(Key.DirectionLeft, alt = true).appShortcut())
        assertEquals(AppShortcut.FOCUS_RIGHT, down(Key.DirectionRight, alt = true).appShortcut())
        assertEquals(AppShortcut.FOCUS_UP, down(Key.DirectionUp, alt = true).appShortcut())
        assertEquals(AppShortcut.FOCUS_DOWN, down(Key.DirectionDown, alt = true).appShortcut())
        assertNull(down(Key.D, alt = true).appShortcut(), "Option makes a character: it is not a shortcut")
    }

    @Test
    fun withShiftTheArrowsGoToTheOtherWorkspaces() {
        assertEquals(AppShortcut.WORKSPACE_UP, down(Key.DirectionUp, alt = true, shift = true).appShortcut())
        assertEquals(AppShortcut.WORKSPACE_DOWN, down(Key.DirectionDown, alt = true, shift = true).appShortcut())
        assertEquals(AppShortcut.WORKSPACE_LEFT, down(Key.DirectionLeft, alt = true, shift = true).appShortcut())
        assertEquals(AppShortcut.WORKSPACE_RIGHT, down(Key.DirectionRight, alt = true, shift = true).appShortcut())
        assertNull(down(Key.D, alt = true, shift = true).appShortcut())
    }

    @Test
    fun theBracketsGoToThePreviousAndTheNextWorkspaceWhateverTheLayout() {
        assertEquals(AppShortcut.WORKSPACE_PREVIOUS, down(Key.LeftBracket, shift = true).appShortcut())
        assertEquals(AppShortcut.WORKSPACE_NEXT, down(Key.RightBracket, shift = true).appShortcut())
        assertNull(down(Key.LeftBracket).appShortcut(), "Without Shift they are not taken")
    }

    @Test
    fun commandOptionAndTheBracketsGoToThePreviousAndTheNextProject() {
        assertEquals(AppShortcut.PROJECT_PREVIOUS, down(Key.LeftBracket, alt = true).appShortcut())
        assertEquals(AppShortcut.PROJECT_NEXT, down(Key.RightBracket, alt = true).appShortcut())
        // With Shift and without Option they are the workspaces' keys, and with neither they are not taken.
        assertEquals(AppShortcut.WORKSPACE_PREVIOUS, down(Key.LeftBracket, shift = true).appShortcut())
        assertNull(down(Key.LeftBracket, alt = true, shift = true).appShortcut())
        assertNull(down(Key.RightBracket, alt = true, meta = false).appShortcut())
    }

    @Test
    fun aDigitGoesToAWorkspaceAndWithOptionToAPane() {
        assertEquals(AppShortcut.WORKSPACE_NUMBER, down(Key.One).appShortcut())
        assertEquals(1, down(Key.One).shortcutNumber())
        assertEquals(9, down(Key.Nine).shortcutNumber())
        assertEquals(AppShortcut.PANE_NUMBER, down(Key.Three, alt = true).appShortcut())
        assertEquals(3, down(Key.Three, alt = true).shortcutNumber())
        assertNull(down(Key.Zero).shortcutNumber(), "Zero is not the number of a workspace: it resets the font")
        assertNull(down(Key.One, shift = true).appShortcut(), "Command and Shift and 1 is not taken")
        assertNull(down(Key.One, meta = false).appShortcut())
        assertNull(down(Key.A).shortcutNumber())
    }

    @Test
    fun commandControlAndAnArrowResizesAndNothingElseWithControlIsTheWindows() {
        assertEquals(AppShortcut.RESIZE_LEFT, down(Key.DirectionLeft, ctrl = true).appShortcut())
        assertEquals(AppShortcut.RESIZE_RIGHT, down(Key.DirectionRight, ctrl = true).appShortcut())
        assertEquals(AppShortcut.RESIZE_UP, down(Key.DirectionUp, ctrl = true).appShortcut())
        assertEquals(AppShortcut.RESIZE_DOWN, down(Key.DirectionDown, ctrl = true).appShortcut())
        assertNull(down(Key.F, ctrl = true).appShortcut(), "Command, Control and F is the full screen of the system")
        assertNull(down(Key.D, ctrl = true).appShortcut())
        assertNull(down(Key.DirectionLeft, ctrl = true, shift = true).appShortcut())
    }

    @Test
    fun shiftEnterMaximizesAndGAndShiftGMoveInTheSearch() {
        assertEquals(AppShortcut.MAXIMIZE, down(Key.Enter, shift = true).appShortcut())
        assertNull(down(Key.Enter).appShortcut(), "Command and Enter is not taken")
        assertEquals(AppShortcut.FIND_NEXT, down(Key.G).appShortcut())
        assertEquals(AppShortcut.FIND_PREVIOUS, down(Key.G, shift = true).appShortcut())
    }

    @Test
    fun theFontIsBiggerWithEqualsOrPlusSmallerWithMinusAndBackToTheDefaultWithZero() {
        assertEquals(AppShortcut.FONT_BIGGER, down(Key.Equals).appShortcut())
        assertEquals(AppShortcut.FONT_BIGGER, down(Key.Equals, shift = true).appShortcut(), "The plus sign is shift and equals")
        assertEquals(AppShortcut.FONT_BIGGER, down(Key.Plus).appShortcut())
        assertEquals(AppShortcut.FONT_SMALLER, down(Key.Minus).appShortcut())
        assertEquals(AppShortcut.FONT_RESET, down(Key.Zero).appShortcut())
    }

    @Test
    fun commaOpensTheMainMenu() {
        assertEquals(AppShortcut.MENU, down(Key.Comma).appShortcut())
        assertNull(down(Key.Comma, meta = false).appShortcut())
    }

    @Test
    fun withoutCommandOrWhileKeyIsReleasedItIsNotAShortcut() {
        assertNull(down(Key.D, meta = false).appShortcut())
        assertNull(down(Key.D, ctrl = true, meta = false).appShortcut(), "Control and D is the program's end of input")
        assertNull(down(Key.D, type = KeyEventType.KeyUp).appShortcut())
    }

    @Test
    fun theLabelsAreTheKeysAsAMacWritesThem() {
        assertEquals("⌘⇧F2", AppShortcut.RENAME_WORKSPACE.label)
        assertEquals("⌘L", AppShortcut.CLEAR_SCREEN.label)
        assertEquals("⌘⇧D", SplitLabels.of(vertical = true).rightShortcut)
        assertEquals("⌘D", SplitLabels.of(vertical = true).downShortcut)
        assertEquals("⌘D", SplitLabels.of(vertical = false).rightShortcut)
        assertEquals("⌘⇧D", SplitLabels.of(vertical = false).downShortcut)
    }

    @Test
    fun aShortcutThatWasReboundIsTheNewKeysAndNotTheOldOnes() {
        Shortcuts.map = ShortcutMap.DEFAULT.with(AppShortcut.SPLIT_MAIN, KeyBinding(java.awt.event.KeyEvent.VK_E))

        assertEquals(AppShortcut.SPLIT_MAIN, down(Key.E).appShortcut())
        assertNull(down(Key.D).appShortcut(), "⌘D is not the split any more")
        assertEquals(AppShortcut.SPLIT_OTHER, down(Key.D, shift = true).appShortcut(), "The other ones are as they were")
        assertEquals("⌘E", AppShortcut.SPLIT_MAIN.label)
    }

    @Test
    fun aShortcutThatWasUnboundIsNothingAndHasNoLabel() {
        Shortcuts.map = ShortcutMap.DEFAULT.with(AppShortcut.FIND, null)

        assertNull(down(Key.F).appShortcut())
        assertEquals("", AppShortcut.FIND.label)
    }

    @Test
    fun theKeysThatTookTheKeysOfAnotherShortcutAreThatShortcut() {
        Shortcuts.map = ShortcutMap.DEFAULT.with(AppShortcut.SPLIT_MAIN, null).with(AppShortcut.FIND, KeyBinding(java.awt.event.KeyEvent.VK_D))

        assertEquals(AppShortcut.FIND, down(Key.D).appShortcut())
    }

    @Test
    fun aShortcutThatTakesDigitsFollowsItsModifiers() {
        Shortcuts.map = ShortcutMap.DEFAULT.with(AppShortcut.PANE_NUMBER, KeyBinding(java.awt.event.KeyEvent.VK_1, ctrl = true))

        assertEquals(AppShortcut.PANE_NUMBER, down(Key.Four, ctrl = true).appShortcut())
        assertNull(down(Key.Four, alt = true).appShortcut(), "⌘⌥4 is not the pane any more")
        assertEquals(4, down(Key.Four, ctrl = true).shortcutNumber())
    }
}
