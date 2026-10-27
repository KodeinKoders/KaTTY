package app.katty

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.window.rememberWindowState
import org.jetbrains.jewel.window.DecoratedWindow
import app.katty.core.WindowFile
import app.katty.core.WindowGeometry
import app.katty.ui.Katty
import app.katty.ui.UiScale
import java.nio.file.Path

/** The size of the usage window the first time it is opened. */
internal const val USAGE_WINDOW_WIDTH = 360f
internal const val USAGE_WINDOW_HEIGHT = 460f

/**
 * The usage of the agents in a window of its own, to keep on another screen. It is composed by the main window's content, which gives it what it
 * shows, and it is there for as long as the setting says so: [onClose] is only for the user closing it (its button, or ⌘W), which puts the usage
 * back in the panel. The application quitting with it open leaves the setting as it is, so that it is open again at the next launch.
 *
 * @param geometryFile Where it was last, which it is opened at.
 */
@Composable
internal fun UsageWindow(uiScale: Float, geometryFile: Path, onClose: () -> Unit, content: @Composable () -> Unit) {
    val file = remember(geometryFile) { WindowFile(geometryFile, WindowGeometry(width = USAGE_WINDOW_WIDTH, height = USAGE_WINDOW_HEIGHT)) }
    // Where it was, unless that is on a screen that is gone.
    val geometry = remember(file) { file.load().sanitized(screens()) }
    val windowState = rememberWindowState(placement = geometry.mode.toPlacement(), position = geometry.toPosition(), size = geometry.toSize())
    val flush = keepWindowGeometry(windowState, file, geometry)
    val close = { flush(); onClose() }
    DecoratedWindow(
        onCloseRequest = close,
        title = "KaTTY — Agent usage",
        icon = remember { AppIcon.painter() },
        state = windowState,
        // ⌘W closes the window that has the focus, as it does in any window of the system.
        onPreviewKeyEvent = { event ->
            val closing = event.type == KeyEventType.KeyDown && event.isMetaPressed && !event.isShiftPressed && !event.isAltPressed && event.key == Key.W
            if (closing) close()
            closing
        },
    ) {
        // The scale is applied inside the window, which has a density of its own.
        UiScale(uiScale) {
            ShortTitleBar("Agent usage")
            Box(Modifier.fillMaxSize().background(Katty.colors.side)) { content() }
        }
    }
}
