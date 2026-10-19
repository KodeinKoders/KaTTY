@file:OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)

package app.katty.ui

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ListKeysTest {
    private fun event(key: Key, type: KeyEventType = KeyEventType.KeyDown) = KeyEvent(key = key, type = type)

    @Test
    fun theArrowsEnterAndEscapeAreTheKeysOfAList() {
        assertEquals(ListKey.UP, event(Key.DirectionUp).listKey())
        assertEquals(ListKey.DOWN, event(Key.DirectionDown).listKey())
        assertEquals(ListKey.ENTER, event(Key.Enter).listKey())
        assertEquals(ListKey.ENTER, event(Key.NumPadEnter).listKey())
        assertEquals(ListKey.ESCAPE, event(Key.Escape).listKey())
    }

    @Test
    fun otherKeysAndKeysComingUpAreNot() {
        assertNull(event(Key.A).listKey())
        assertNull(event(Key.DirectionDown, KeyEventType.KeyUp).listKey())
    }

    @Test
    fun theSelectionGoesRoundTheList() {
        assertEquals(1, 0.stepped(1, 3))
        assertEquals(0, 2.stepped(1, 3))
        assertEquals(2, 0.stepped(-1, 3))
        assertEquals(0, 0.stepped(1, 0), "A list with no row has nothing to select")
    }

    @Test
    fun aTooltipSaysTheShortcutAfterTheDescriptionInParentheses() {
        assertEquals("Main menu (⌘,)", tooltipText("Main menu", "⌘,"))
        assertEquals("Add project", tooltipText("Add project", null))
    }
}
