@file:OptIn(ExperimentalTestApi::class)

package app.katty.screens

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InteractionTest {
    @Test
    fun typingForTheProgramIsDoingSomethingWithTheTerminal() {
        assertTrue(countsAsInteraction(Key.A, KeyEventType.KeyDown, meta = false))
        assertTrue(countsAsInteraction(Key.Escape, KeyEventType.KeyDown, meta = false))
        assertFalse(countsAsInteraction(Key.A, KeyEventType.KeyUp, meta = false), "Only the key going down")
    }

    @Test
    fun aModifierAloneOrAKeyWithCommandIsNot() {
        assertFalse(countsAsInteraction(Key.MetaLeft, KeyEventType.KeyDown, meta = true), "The ⌘ of ⌘J, which opens the bell")
        assertFalse(countsAsInteraction(Key.J, KeyEventType.KeyDown, meta = true))
        assertFalse(countsAsInteraction(Key.ShiftLeft, KeyEventType.KeyDown, meta = false))
        assertFalse(countsAsInteraction(Key.CtrlLeft, KeyEventType.KeyDown, meta = false))
    }

    @Test
    fun theKeysAndTheClicksOfWhatItHoldsAreTold() = runComposeUiTest {
        var told = 0
        setContent {
            val focus = remember { FocusRequester() }
            val onInteraction = remember { { told++; Unit } }
            Box(Modifier.size(100.dp).testTag("pane").userInteraction(onInteraction).focusRequester(focus).focusable())
            LaunchedEffect(Unit) { focus.requestFocus() }
        }
        waitForIdle()

        onNodeWithTag("pane").performKeyInput { keyDown(Key.MetaLeft); pressKey(Key.J); keyUp(Key.MetaLeft) }
        assertEquals(0, told, "⌘J is not")
        onNodeWithTag("pane").performKeyInput { pressKey(Key.A) }
        assertEquals(1, told)
        onNodeWithTag("pane").performMouseInput { click(center) }
        assertEquals(2, told)
    }
}
