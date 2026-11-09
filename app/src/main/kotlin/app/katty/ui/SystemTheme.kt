package app.katty.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.jetbrains.skiko.SystemTheme
import org.jetbrains.skiko.currentSystemTheme

// How often the appearance of the system is read again: what Compose does in its windows.
private const val SYSTEM_THEME_POLL_MS = 1_000L

/**
 * Whether the system is dark, read again every second while [follow] is true, so that the application follows macOS when its appearance changes.
 *
 * Compose's `isSystemInDarkTheme()` does that only inside a window (it is given there by the window's scene), and the theme of the application is
 * decided outside them, where it read the appearance once and kept it.
 */
@Composable
fun rememberSystemDark(follow: Boolean): State<Boolean> = produceState(currentSystemTheme == SystemTheme.DARK, follow) {
    if (!follow) return@produceState
    while (true) {
        value = withContext(Dispatchers.IO) { currentSystemTheme == SystemTheme.DARK }
        delay(SYSTEM_THEME_POLL_MS)
    }
}
