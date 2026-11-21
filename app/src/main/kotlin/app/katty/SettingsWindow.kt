package app.katty

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.window.rememberWindowState
import app.katty.core.WindowFile
import app.katty.core.WindowGeometry
import app.katty.ui.Katty
import app.katty.ui.OneLine
import app.katty.ui.UiScale
import org.jetbrains.jewel.window.DecoratedWindow
import org.jetbrains.jewel.window.TitleBar
import java.nio.file.Path

/** The size of the settings window the first time it is opened. */
internal const val SETTINGS_WINDOW_WIDTH = 820f
internal const val SETTINGS_WINDOW_HEIGHT = 600f

/**
 * The settings in a window of their own (⌘, , the button of the title bar, the palette, and the Settings item of the application menu of macOS).
 * It is composed by the main window's content, which gives it what it shows (`SettingsContent`), for as long as it is open; it is not opened again
 * at the next launch. The main window stays usable meanwhile: it is not a dialog.
 *
 * ⌘W closes it, once whatever has the focus has not taken the keys: the shortcuts editor takes every key while it records one, ⌘W too.
 *
 * @param geometryFile Where it was last, which it is opened at.
 * @param focusTick Changes when it is asked for while it is open: it comes to the front.
 */
@Composable
internal fun SettingsWindow(uiScale: Float, geometryFile: Path, focusTick: Int, onClose: () -> Unit, content: @Composable () -> Unit) {
    val file = remember(geometryFile) { WindowFile(geometryFile, WindowGeometry(width = SETTINGS_WINDOW_WIDTH, height = SETTINGS_WINDOW_HEIGHT)) }
    // Where it was, unless that is on a screen that is gone.
    val geometry = remember(file) { file.load().sanitized(screens()) }
    val windowState = rememberWindowState(placement = geometry.mode.toPlacement(), position = geometry.toPosition(), size = geometry.toSize())
    val flush = keepWindowGeometry(windowState, file, geometry)
    val close = { flush(); onClose() }
    DecoratedWindow(
        onCloseRequest = close,
        title = "KaTTY — Settings",
        icon = remember { AppIcon.painter() },
        state = windowState,
        onKeyEvent = { event ->
            val closing = event.type == KeyEventType.KeyDown && event.isCommandPressed() && !event.isShiftPressed && !event.isAltPressed && event.key == Key.W
            if (closing) close()
            closing
        },
    ) {
        LaunchedEffect(focusTick) { if (focusTick > 0) window.toFront() }
        // The scale is applied inside the window, which has a density of its own.
        UiScale(uiScale) {
            // Jewel's title bar at its own height, as the main window's: a window that is used, not a panel beside it.
            TitleBar { OneLine("Settings", color = Katty.colors.text) }
            content()
        }
    }
}
