package app.katty.terminal.compose

import androidx.compose.foundation.focusable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import app.katty.terminal.TerminalSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Shows what [session] displays, and sends it what is typed.
 *
 * It takes the focus when it is shown. The terminal is resized to fill the space that it is given, with
 * as many cells as fit in it.
 *
 * @param fontSize The size of the text, which gives the size of a cell. The font is always monospace.
 */
@Composable
public fun Terminal(
    session: TerminalSession,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 13.sp,
) {
    val frame by session.frames.collectAsState()
    val typography = rememberTerminalTypography(fontSize)

    var size by remember { mutableStateOf(IntSize.Zero) }
    var focused by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val keys = remember { KeyTranslator() }

    // As many cells as fit. It is the number of them that matters, not the size in pixels: the terminal is
    // not resized while a window is dragged for as long as the grid is the same.
    val cols = (size.width / typography.cellWidth).toInt()
    val rows = (size.height / typography.cellHeight).toInt()
    LaunchedEffect(cols, rows, typography) {
        if (cols < 1 || rows < 1) return@LaunchedEffect
        // Resizing takes the lock of the terminal: not on the UI thread, which would wait for the program's output.
        withContext(Dispatchers.Default) {
            session.resize(cols, rows, typography.cellWidth.roundToInt(), typography.cellHeight.roundToInt())
        }
    }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    TerminalView(
        frame = frame,
        typography = typography,
        focused = focused,
        modifier = modifier
            .onSizeChanged { size = it }
            .focusRequester(focusRequester)
            .onFocusChanged {
                focused = it.isFocused
                // The release of the keys that are down will not be seen.
                if (!it.isFocused) keys.reset()
            }
            .onPreviewKeyEvent { event ->
                val input = keys.translate(event.toKeyInfo()) ?: return@onPreviewKeyEvent false
                session.sendKey(input)
                true
            }
            .focusable(),
    )
}
