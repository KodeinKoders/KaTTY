package app.katty

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import app.katty.core.TerminalId
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * What the other windows ask of the main one. The board window shows no terminal: a card that is clicked there is a terminal to select in
 * the main window, which is [openTerminal]'s to say. Nobody collecting drops the request, which a window that has no one to ask cannot
 * miss; and `tryEmit` never blocks the window that asks.
 */
class WindowBridge {
    val openTerminal = MutableSharedFlow<TerminalId>(extraBufferCapacity = 8)

    /** The main window is to have the focus: the board window that was opened again at launch took it, and the user was not asking for it. */
    val focusMain = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /**
     * Writes where the board window is, while it is open: quitting calls it, since a quit from the system's menu (⌘Q, the Dock, logging out)
     * may end the process before the window leaves the composition, which is when it writes it otherwise.
     */
    @Volatile
    var flushBoardWindow: (() -> Unit)? = null
}

/** The time in milliseconds, which moves on by itself every 30 seconds: what the ages on the screen are told from. */
@Composable
fun rememberNow(): Long {
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(30_000)
            value = System.currentTimeMillis()
        }
    }
    return now
}
