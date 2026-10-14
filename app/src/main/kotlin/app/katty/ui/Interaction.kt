package app.katty.ui

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Calls [onClick] with the position, in the element, of a press of the right button (a press of the left one with Control held is the
 * same on a Mac), without taking it from what is inside.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.onSecondaryClick(onClick: (Offset) -> Unit): Modifier = pointerInput(onClick) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.type != PointerEventType.Press) continue
            val secondary = event.button == PointerButton.Secondary ||
                (event.button == PointerButton.Primary && event.keyboardModifiers.isCtrlPressed)
            if (secondary) onClick(event.changes.first().position)
        }
    }
}
