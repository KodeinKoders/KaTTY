package app.katty.terminal.compose

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.katty.terminal.TerminalLink
import app.katty.terminal.TerminalMouseButton
import org.slf4j.LoggerFactory
import java.awt.Desktop
import java.net.URI
import kotlin.concurrent.thread
import kotlin.math.floor

private val logger = LoggerFactory.getLogger("app.katty.terminal.compose.Links")

/**
 * Whether the modifier that makes a click open a link is held, and no other: Command on macOS, Control elsewhere. Without it a click is
 * the program's, or selects, as usual. Option and Command together is a block selection, and Shift selects.
 */
internal fun linkModifier(shift: Boolean, ctrl: Boolean, alt: Boolean, meta: Boolean, mac: Boolean = isMac): Boolean =
    if (mac) meta && !ctrl && !alt && !shift else ctrl && !meta && !alt && !shift

/**
 * The links under the pointer, and the click that opens one.
 *
 * While the modifier is held, the link at the pointer is [hovered]: it is underlined, and the pointer is a hand. A left press
 * on one is the link's, not the program's and not a selection, and so are its drag and its release; the release that
 * is still on the same address opens it.
 *
 * Not thread-safe: it is used from the UI thread.
 */
internal class LinkInteraction {
    /** The link that a click would open. A Compose state: it is drawn, and it chooses the pointer. */
    var hovered by mutableStateOf<TerminalLink?>(null)
        private set

    // Where the pointer is, in pixels, and whether it is in the terminal.
    private var x = 0f
    private var y = 0f
    private var inside = false

    private var held = false
    private var pressed: TerminalLink? = null

    /** The pointer left the terminal. */
    fun exit() {
        inside = false
        if (pressed == null) hovered = null
    }

    /**
     * Follows a pointer event, and says whether it is the link's: it is then consumed, and what it does is not done.
     *
     * @param linkAt The link at a cell, `null` for none.
     */
    fun handle(
        event: PointerInfo,
        cellWidth: Float,
        cellHeight: Float,
        linkAt: (column: Int, row: Int) -> TerminalLink?,
        open: (String) -> Unit,
        mac: Boolean = isMac,
    ): Boolean {
        if (event.kind == PointerInfo.Kind.SCROLL) return false
        x = event.x
        y = event.y
        inside = true
        held = linkModifier(event.shift, event.ctrl, event.alt, event.meta, mac)
        // While a press that is the link's goes on, what is under the pointer does not matter.
        if (pressed == null) refresh(cellWidth, cellHeight, linkAt)

        when (event.kind) {
            PointerInfo.Kind.PRESS -> {
                val link = hovered
                if (event.button == TerminalMouseButton.LEFT && held && link != null) {
                    pressed = link
                    return true
                }
            }

            PointerInfo.Kind.RELEASE -> {
                val link = pressed
                if (event.button == TerminalMouseButton.LEFT && link != null) {
                    pressed = null
                    refresh(cellWidth, cellHeight, linkAt)
                    if (held && hovered?.url == link.url) open(link.url)
                    return true
                }
            }

            PointerInfo.Kind.MOVE -> if (pressed != null) return true
            PointerInfo.Kind.SCROLL -> Unit
        }
        return false
    }

    /**
     * The modifier was pressed or released, or the screen changed under the pointer: the link there may be another one, or none.
     */
    fun refresh(held: Boolean, cellWidth: Float, cellHeight: Float, linkAt: (column: Int, row: Int) -> TerminalLink?) {
        this.held = held
        refresh(cellWidth, cellHeight, linkAt)
    }

    private fun refresh(cellWidth: Float, cellHeight: Float, linkAt: (column: Int, row: Int) -> TerminalLink?) {
        val column = floor(x / cellWidth).toInt()
        val row = floor(y / cellHeight).toInt()
        val link = if (held && inside && x >= 0 && y >= 0) linkAt(column, row) else null
        // The same object for the same link, or what draws it would be told of a change that is none.
        if (link != hovered) hovered = link
    }
}

// What a link may open from here: addresses that a program can write, which a program could use to start anything else.
private val OPENABLE_SCHEMES = setOf("http", "https", "mailto", "ftp", "file")

/**
 * Opens [url] with the system: its browser, for the web, or whatever handles a file or a mail address. Only for the schemes that
 * a program has no business starting something with: the others are refused. The host that wants more gives its own to
 * [Terminal].
 */
internal fun openInBrowser(url: String) {
    val uri = try {
        URI(url)
    } catch (e: Exception) {
        logger.warn("Not opening {}: it is not an address", url)
        return
    }
    if (uri.scheme?.lowercase() !in OPENABLE_SCHEMES) {
        logger.warn("Not opening {}: the scheme is not one that is opened from the terminal", url)
        return
    }
    // Starting the browser may take a moment, and not on the UI thread.
    thread(name = "katty-open-link", isDaemon = true) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(uri)
            } else {
                logger.warn("Not opening {}: this system has no way to", url)
            }
        } catch (e: Exception) {
            logger.warn("Could not open {}", url, e)
        }
    }
}
