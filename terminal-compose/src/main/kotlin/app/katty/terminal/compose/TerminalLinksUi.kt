package app.katty.terminal.compose

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.katty.terminal.TerminalLink
import app.katty.terminal.TerminalMouseButton
import org.slf4j.LoggerFactory
import java.awt.Desktop
import java.io.File
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
private val BROWSED_SCHEMES = setOf("http", "https", "mailto", "ftp")

/** What clicking a link does, which [linkAction] decides. */
internal sealed interface LinkAction {
    /** The system opens the address: its browser, its mail program. */
    data class Browse(val uri: URI) : LinkAction

    /** The Finder opens the folder. */
    data class OpenFolder(val folder: File) : LinkAction

    /** The Finder shows the file, selected in its folder: it is not opened. */
    data class Reveal(val file: File) : LinkAction

    /** Nothing is opened. */
    data class Refuse(val reason: String) : LinkAction
}

/**
 * What clicking the link [url] does. The addresses of the web and of mail are opened by the system. A `file:` link is not opened: a program
 * can write any text over a link (OSC 8), and opening a file runs it when it is an application or a script (`.app`, `.command`), so it is
 * shown in the Finder instead, and only a folder (with no extension: a bundle such as `X.app` is a folder that would run) is opened there.
 * A file of another machine, one that is not there, and any other scheme are refused.
 */
internal fun linkAction(url: String): LinkAction {
    val uri = try {
        URI(url)
    } catch (_: Exception) {
        return LinkAction.Refuse("it is not an address")
    }
    return when (uri.scheme?.lowercase()) {
        in BROWSED_SCHEMES -> LinkAction.Browse(uri)
        "file" -> {
            val host = uri.host ?: uri.authority
            if (!host.isNullOrEmpty() && host != "localhost") return LinkAction.Refuse("the file is on another machine")
            val path = uri.path?.takeIf { it.isNotEmpty() } ?: return LinkAction.Refuse("it names no file")
            val file = File(path)
            when {
                !file.exists() -> LinkAction.Refuse("there is no such file")
                file.isDirectory && file.extension.isEmpty() -> LinkAction.OpenFolder(file)
                else -> LinkAction.Reveal(file)
            }
        }
        else -> LinkAction.Refuse("the scheme is not one that is opened from the terminal")
    }
}

/**
 * Opens [url] as [linkAction] says: with the system's browser, for the web, or in the Finder, for a file. The host that wants more gives
 * its own to [Terminal].
 */
internal fun openInBrowser(url: String) {
    val action = linkAction(url)
    if (action is LinkAction.Refuse) {
        logger.warn("Not opening {}: {}", url, action.reason)
        return
    }
    // Starting the browser may take a moment, and not on the UI thread.
    thread(name = "katty-open-link", isDaemon = true) {
        try {
            val desktop = if (Desktop.isDesktopSupported()) Desktop.getDesktop() else null
            val supported = when (action) {
                is LinkAction.Browse -> desktop?.isSupported(Desktop.Action.BROWSE)?.also { if (it) desktop.browse(action.uri) }
                is LinkAction.OpenFolder -> desktop?.isSupported(Desktop.Action.OPEN)?.also { if (it) desktop.open(action.folder) }
                is LinkAction.Reveal -> desktop?.isSupported(Desktop.Action.BROWSE_FILE_DIR)?.also { if (it) desktop.browseFileDirectory(action.file) }
                is LinkAction.Refuse -> true
            }
            if (supported != true) logger.warn("Not opening {}: this system has no way to", url)
        } catch (e: Exception) {
            logger.warn("Could not open {}", url, e)
        }
    }
}
