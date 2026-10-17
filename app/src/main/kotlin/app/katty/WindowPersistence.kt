package app.katty

import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Dispatchers
import app.katty.core.WindowFile
import androidx.compose.ui.window.WindowState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import app.katty.core.ScreenBounds
import app.katty.core.WindowGeometry
import app.katty.core.WindowMode
import java.awt.GraphicsEnvironment

internal fun WindowMode.toPlacement(): WindowPlacement = when (this) {
    WindowMode.FLOATING -> WindowPlacement.Floating
    WindowMode.MAXIMIZED -> WindowPlacement.Maximized
    WindowMode.FULLSCREEN -> WindowPlacement.Fullscreen
}

internal fun WindowGeometry.toPosition(): WindowPosition {
    val left = x
    val top = y
    return if (left != null && top != null) WindowPosition(left.dp, top.dp) else WindowPosition.PlatformDefault
}

internal fun WindowGeometry.toSize(): DpSize = DpSize(width.dp, height.dp)

/**
 * What to keep of the window now, given what was kept before.
 *
 * The position and the size are the floating window's: a window that is maximized or full screen reports the size of the screen, and
 * what it goes back to when it is restored is what it was before, which [previous] has. A window that is minimized changes nothing: it
 * would reopen where it cannot be seen.
 */
internal fun geometryOf(placement: WindowPlacement, minimized: Boolean, position: WindowPosition, size: DpSize, previous: WindowGeometry): WindowGeometry {
    if (minimized) return previous
    return when (placement) {
        WindowPlacement.Floating -> previous.copy(
            x = position.takeIf { it.isSpecified }?.x?.value ?: previous.x,
            y = position.takeIf { it.isSpecified }?.y?.value ?: previous.y,
            width = size.takeIf { it.isSpecified }?.width?.value ?: previous.width,
            height = size.takeIf { it.isSpecified }?.height?.value ?: previous.height,
            mode = WindowMode.FLOATING,
        )
        WindowPlacement.Maximized -> previous.copy(mode = WindowMode.MAXIMIZED)
        WindowPlacement.Fullscreen -> previous.copy(mode = WindowMode.FULLSCREEN)
    }
}

/** The screens that the window may be on, in the units of its position. */
internal fun screens(): List<ScreenBounds> = try {
    GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.map {
        val bounds = it.defaultConfiguration.bounds
        ScreenBounds(bounds.x.toFloat(), bounds.y.toFloat(), bounds.width.toFloat(), bounds.height.toFloat())
    }
} catch (e: java.awt.HeadlessException) {
    emptyList()
}

/** How long a window has to stay as it is before its geometry is written: moving or resizing it changes it many times a second. */
internal const val WINDOW_WRITE_DELAY_MS = 500L

/**
 * Keeps the geometry of a window in [file] as it changes, 500 ms after the last change and when the window goes away: where it is, how big
 * it is and in which mode, so that it opens as it was closed. What is kept comes from [geometryOf].
 *
 * @param initial What the window was opened with: the base of what is kept (the floating bounds stay while it is maximized).
 * @return What writes the geometry now, for a window that is closing or an application that is quitting.
 */
@Composable
internal fun keepWindowGeometry(state: WindowState, file: WindowFile, initial: WindowGeometry): () -> Unit {
    val kept = remember { arrayOf(initial) }
    val record = { kept[0] = geometryOf(state.placement, state.isMinimized, state.position, state.size, kept[0]) }
    @OptIn(FlowPreview::class)
    LaunchedEffect(state) {
        snapshotFlow { listOf(state.placement, state.isMinimized, state.position, state.size) }
            .debounce(WINDOW_WRITE_DELAY_MS)
            .collect {
                val before = kept[0]
                record()
                if (kept[0] != before) withContext(Dispatchers.IO) { runCatching { file.save(kept[0]) } }
            }
    }
    val flush = {
        record()
        runCatching { file.save(kept[0]) }
        Unit
    }
    // A window that leaves the composition (the application quits with it open) is written once more.
    val latestFlush = rememberUpdatedState(flush)
    DisposableEffect(state) { onDispose { latestFlush.value() } }
    return flush
}
