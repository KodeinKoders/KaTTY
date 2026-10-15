package app.katty

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
