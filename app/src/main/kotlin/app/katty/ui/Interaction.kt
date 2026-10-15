package app.katty.ui

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
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

/** How long after a click and how far from it a second one makes a double click. */
private const val DOUBLE_CLICK_MS = 400L
private const val DOUBLE_CLICK_SLOP_PX = 12f

/**
 * Calls [onDoubleClick] for a second press of the left button that comes soon after a first one, in about the same place. It only watches:
 * the clicks are not taken from what is inside, so a single click still does its own thing at once, with no wait to know whether another
 * comes.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.onDoubleClick(onDoubleClick: () -> Unit): Modifier = composed {
    // The first click may change what is shown (it selects a row) and so the action: the detector must not start again then, or it would
    // forget that first click.
    val latest = rememberUpdatedState(onDoubleClick)
    pointerInput(Unit) { watchDoubleClicks { latest.value() } }
}

@OptIn(ExperimentalComposeUiApi::class)
private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.watchDoubleClicks(onDoubleClick: () -> Unit) {
    awaitPointerEventScope {
        var lastTime = Long.MIN_VALUE
        var lastPosition = Offset.Zero
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.type != PointerEventType.Press || event.button != PointerButton.Primary) continue
            val change = event.changes.first()
            val second = change.uptimeMillis - lastTime <= DOUBLE_CLICK_MS && (change.position - lastPosition).getDistance() <= DOUBLE_CLICK_SLOP_PX
            if (second) {
                // Another two clicks are needed for the next one.
                lastTime = Long.MIN_VALUE
                onDoubleClick()
            } else {
                lastTime = change.uptimeMillis
                lastPosition = change.position
            }
        }
    }
}

/**
 * Fills the element with the hover color of the design while the pointer is over it, so that what can be clicked says so.
 *
 * @param enabled `false` for what already has a fill that says something else, such as a selected row.
 * @param color What it is filled with: the hover color, which is no change for what is already filled with it (a tile).
 */
fun Modifier.hoverHighlight(enabled: Boolean = true, color: androidx.compose.ui.graphics.Color? = null): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val fill = color ?: Katty.colors.hover
    this.hoverable(source).drawBehind { if (enabled && hovered) drawRect(fill) }
}
