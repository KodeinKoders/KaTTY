package app.katty.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type

/** What a key does in a list that is moved with the keyboard: the palette, the list of the agents that need the user. */
internal enum class ListKey { UP, DOWN, ENTER, ESCAPE }

/** The [ListKey] that [this] event is, `null` when it is another key or a key coming up. */
internal fun KeyEvent.listKey(): ListKey? {
    if (type != KeyEventType.KeyDown) return null
    return when (key) {
        Key.DirectionUp -> ListKey.UP
        Key.DirectionDown -> ListKey.DOWN
        Key.Enter, Key.NumPadEnter -> ListKey.ENTER
        Key.Escape -> ListKey.ESCAPE
        else -> null
    }
}

/** The place [delta] rows from this one in a list of [size] rows, going round at the ends; 0 for a list that has none. */
internal fun Int.stepped(delta: Int, size: Int): Int = if (size == 0) 0 else Math.floorMod(this + delta, size)
