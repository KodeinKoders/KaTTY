package app.katty.screens

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput

// The keys that only change what another one does: pressed alone, they are not the user doing something with the terminal.
private val MODIFIER_KEYS = setOf(
    Key.ShiftLeft, Key.ShiftRight, Key.CtrlLeft, Key.CtrlRight, Key.AltLeft, Key.AltRight, Key.MetaLeft, Key.MetaRight, Key.CapsLock, Key.Function,
)

/**
 * Whether a key is the user doing something with the terminal that has the keyboard: a key that goes down, that is not a modifier alone, and
 * without ⌘. What has ⌘ is the window's or the terminal's own, and the ⌘ of ⌘J goes down in the terminal before the J opens the bell: coming
 * back to the window and opening the bell must not count as having seen the terminal behind it.
 */
internal fun countsAsInteraction(key: Key, type: KeyEventType, meta: Boolean): Boolean =
    type == KeyEventType.KeyDown && !meta && key !in MODIFIER_KEYS

/**
 * Tells [onInteraction] when the user types in what this holds (see [countsAsInteraction]) or presses a button of the mouse over it, and lets
 * the event go on to what it is for. [onInteraction] is a key of the pointer's handler: give one that is remembered.
 */
internal fun Modifier.userInteraction(onInteraction: () -> Unit): Modifier = this
    .onPreviewKeyEvent { event ->
        if (countsAsInteraction(event.key, event.type, event.isMetaPressed)) onInteraction()
        false
    }
    .pointerInput(onInteraction) {
        awaitPointerEventScope {
            while (true) {
                // Before what is under the pointer sees it, which takes it: the terminal, a button of the header.
                if (awaitPointerEvent(PointerEventPass.Initial).type == PointerEventType.Press) onInteraction()
            }
        }
    }
