package app.katty.ui

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex

/**
 * The state of a list whose items are reordered by dragging them: which one is dragged, how far, and where it would be dropped.
 *
 * Nothing moves while it is dragged: the item follows the pointer, a line shows between which two it would go, and the order changes
 * once, when it is dropped ([onMove] gets the index it has afterwards).
 *
 * @param horizontal Whether the items are in a row, which is dragged along its width, else a column.
 */
class ReorderState<K : Any>(val horizontal: Boolean) {
    internal var keys: List<K> = emptyList()
    internal var onMove: (K, Int) -> Unit = { _, _ -> }
    private val bounds = HashMap<K, Rect>()

    /** The item that is dragged, or `null`. */
    var dragging by mutableStateOf<K?>(null)
        private set

    /** How far the dragged item is from where it was, along the list. */
    var offset by mutableFloatStateOf(0f)
        private set

    /** The item that the dragged one would be put before, or after it when it would go last: where the line is. */
    var before by mutableStateOf<K?>(null)
        private set
    var after by mutableStateOf<K?>(null)
        private set

    private var target = -1

    internal fun position(key: K, rect: Rect) {
        bounds[key] = rect
    }

    internal fun start(key: K) {
        dragging = key
        offset = 0f
        update(key)
    }

    internal fun drag(delta: Float) {
        val key = dragging ?: return
        offset += delta
        update(key)
    }

    internal fun end() {
        val key = dragging
        val index = target
        cancel()
        if (key != null && index >= 0 && index != keys.indexOf(key)) onMove(key, index)
    }

    internal fun cancel() {
        dragging = null
        offset = 0f
        before = null
        after = null
        target = -1
    }

    private fun Rect.center() = if (horizontal) center.x else center.y

    // The index it would have among the others once dropped: as many as are before its center.
    private fun update(key: K) {
        val own = bounds[key] ?: return
        val others = keys.filter { it != key && it in bounds }
        val center = own.center() + offset
        val index = others.count { bounds.getValue(it).center() < center }
        target = index
        val current = keys.indexOf(key)
        if (others.isEmpty() || index == current) {
            before = null
            after = null
        } else if (index < others.size) {
            before = others[index]
            after = null
        } else {
            before = null
            after = others.last()
        }
    }
}

/** The state for a list in the order of [keys], which [onMove] changes when an item is dropped somewhere else. */
@Composable
fun <K : Any> rememberReorderState(keys: List<K>, horizontal: Boolean = false, onMove: (K, Int) -> Unit): ReorderState<K> {
    val state = remember(horizontal) { ReorderState<K>(horizontal) }
    state.keys = keys
    state.onMove = onMove
    return state
}

/**
 * An item of a reorderable list: it records where it is, follows the pointer while it is dragged and shows the line of the drop.
 * Put it on the whole item, and [reorderHandle] on what is dragged by (the same, or a part of it).
 */
@Composable
fun <K : Any> Modifier.reorderItem(state: ReorderState<K>, key: K): Modifier {
    val color = Katty.colors.accent
    val dragged = state.dragging == key
    val line = if (state.before == key) -1 else if (state.after == key) 1 else 0
    return this
        .onGloballyPositioned { state.position(key, it.boundsInRoot()) }
        .zIndex(if (dragged) 1f else 0f)
        .graphicsLayer {
            if (dragged) {
                if (state.horizontal) translationX = state.offset else translationY = state.offset
                alpha = 0.85f
            }
        }
        .drawWithContent {
            drawContent()
            if (line != 0) {
                val thickness = 2.dp.toPx()
                val atStart = line < 0
                if (state.horizontal) {
                    val x = if (atStart) 0f else size.width - thickness
                    drawRect(color, Offset(x, 0f), Size(thickness, size.height))
                } else {
                    val y = if (atStart) 0f else size.height - thickness
                    drawRect(color, Offset(0f, y), Size(size.width, thickness))
                }
            }
        }
}

/** What an item is dragged by. A click that does not move is left to whatever else the item does with it. */
fun <K : Any> Modifier.reorderHandle(state: ReorderState<K>, key: K, enabled: Boolean = true): Modifier =
    if (!enabled) {
        this
    } else {
        pointerInput(state, key) {
            detectDragGestures(
                onDragStart = { state.start(key) },
                onDrag = { change, amount ->
                    change.consume()
                    state.drag(if (state.horizontal) amount.x else amount.y)
                },
                onDragEnd = { state.end() },
                onDragCancel = { state.cancel() },
            )
        }
    }
