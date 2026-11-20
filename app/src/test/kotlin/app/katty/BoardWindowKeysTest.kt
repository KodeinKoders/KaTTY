@file:OptIn(InternalComposeUiApi::class)

package app.katty

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import app.katty.core.AgentInfo
import app.katty.core.AgentReport
import app.katty.core.AgentState
import app.katty.core.KattyStore
import app.katty.core.SplitDirection
import app.katty.core.TerminalId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The shortcuts of the main window that the board window has too: coming back to the application may give it the keyboard. */
class BoardWindowKeysTest {
    private var counter = 0
    private val store = KattyStore(newId = { "id${counter++}" })
    private val state get() = store.state.value

    private fun key(key: Key, shift: Boolean = false, type: KeyEventType = KeyEventType.KeyDown) =
        KeyEvent(key = key, type = type, isMetaPressed = true, isShiftPressed = shift)

    private fun waiting() = AgentInfo(AgentReport(AgentState.NEEDS_INPUT, "claude"), 1)

    @Test
    fun jumpingToTheNextTerminalThatNeedsTheUserOpensItInTheMainWindow() {
        store.addProject("/dev/api")
        val selected = state.selection.terminal!!
        val other = store.split(selected, SplitDirection.ROW)!!
        store.focus(selected)
        val opened = mutableListOf<TerminalId>()

        assertTrue(jumpFromTheBoardWindow(key(Key.J, shift = true), state, mapOf(other to waiting()), emptyMap()) { opened += it })

        assertEquals(listOf(other), opened)
    }

    @Test
    fun withNoTerminalThatNeedsTheUserTheShortcutDoesNothing() {
        store.addProject("/dev/api")
        val opened = mutableListOf<TerminalId>()

        assertTrue(jumpFromTheBoardWindow(key(Key.J, shift = true), state, emptyMap(), emptyMap()) { opened += it }, "The shortcut is the window's still")

        assertEquals(emptyList(), opened)
    }

    @Test
    fun otherKeysAndTheReleaseAreLeftToTheBoard() {
        store.addProject("/dev/api")
        val selected = state.selection.terminal!!
        val other = store.split(selected, SplitDirection.ROW)!!
        val infos = mapOf(other to waiting())
        val opened = mutableListOf<TerminalId>()

        assertFalse(jumpFromTheBoardWindow(key(Key.J), state, infos, emptyMap()) { opened += it })
        assertFalse(jumpFromTheBoardWindow(key(Key.J, shift = true, type = KeyEventType.KeyUp), state, infos, emptyMap()) { opened += it })

        assertEquals(emptyList(), opened)
    }
}
