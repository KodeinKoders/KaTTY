package app.katty.terminal.compose

import androidx.compose.ui.platform.Clipboard
import app.katty.terminal.TerminalMouseButton
import app.katty.terminal.TerminalSelectionAdjust
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.event.KeyEvent as AwtKeyEvent

internal enum class ClipboardShortcut {
    COPY,
    PASTE,
}

internal val isMac: Boolean = System.getProperty("os.name").orEmpty().startsWith("Mac")

/**
 * The keys that copy and paste: Command and C or V on macOS, and Control and Shift and C or V elsewhere, where Control and C
 * is the program's own (an interrupt). Nothing else must be held. They are the terminal's: the program is told nothing of
 * them, nor of their release.
 */
internal fun clipboardShortcut(key: KeyInfo, mac: Boolean = isMac): ClipboardShortcut? {
    if (key.kind == KeyInfo.Kind.TYPED) return null
    val modifiers = if (mac) {
        key.meta && !key.ctrl && !key.alt && !key.shift
    } else {
        key.ctrl && key.shift && !key.alt && !key.meta
    }
    if (!modifiers) return null
    return when (key.keyCode) {
        AwtKeyEvent.VK_C -> ClipboardShortcut.COPY
        AwtKeyEvent.VK_V -> ClipboardShortcut.PASTE
        else -> null
    }
}

/**
 * How the key extends a selection: Shift and an arrow, which are the program's when nothing is selected. The release is the terminal's as
 * well as the press, so that the program is not told of the release of a key that it did not see go down.
 */
internal fun selectionAdjustment(key: KeyInfo): TerminalSelectionAdjust? {
    if (key.kind == KeyInfo.Kind.TYPED || !key.shift || key.ctrl || key.alt || key.meta) return null
    return when (key.keyCode) {
        AwtKeyEvent.VK_LEFT -> TerminalSelectionAdjust.LEFT
        AwtKeyEvent.VK_RIGHT -> TerminalSelectionAdjust.RIGHT
        AwtKeyEvent.VK_UP -> TerminalSelectionAdjust.UP
        AwtKeyEvent.VK_DOWN -> TerminalSelectionAdjust.DOWN
        else -> null
    }
}

/**
 * What was selected last in the terminal, which a middle click pastes: it is not the clipboard, which is the user's own and
 * which selecting does not touch. It stays when the selection goes.
 *
 * Every terminal has one of its own, unless the host gives them the same: then what is selected in one is pasted by the middle click of
 * another. It is used from the UI thread.
 */
public class TerminalPrimarySelection {
    /** The text, `null` until something was selected. */
    public var text: String? = null
}

/**
 * Whether a press of the middle button pastes the last selection: when the program did not ask for the mouse, and with Shift
 * held when it did, since then the other presses are the program's.
 */
internal fun middleClickPastes(event: PointerInfo, mouseTracking: Boolean): Boolean =
    event.kind == PointerInfo.Kind.PRESS && event.button == TerminalMouseButton.MIDDLE && (!mouseTracking || event.shift)

/**
 * A text that the clipboard offers in two ways: as it is, and as HTML, for the programs that paste with their styles. The AWT's own flavors
 * for them: the plain one is what a program that does not know HTML reads.
 */
internal class RichText(private val text: String, private val html: String) : Transferable {
    private val flavors = arrayOf(DataFlavor.stringFlavor, DataFlavor.allHtmlFlavor)

    override fun getTransferDataFlavors(): Array<DataFlavor> = flavors.copyOf()

    override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = flavors.any { it.equals(flavor) }

    override fun getTransferData(flavor: DataFlavor): Any = when {
        flavor.equals(DataFlavor.stringFlavor) -> text
        flavor.equals(DataFlavor.allHtmlFlavor) -> html
        else -> throw java.awt.datatransfer.UnsupportedFlavorException(flavor)
    }
}

/** The text that is on the clipboard, or `null` if there is none. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
internal suspend fun Clipboard.readText(): String? {
    val transferable = getClipEntry()?.nativeClipEntry as? Transferable ?: return null
    if (!transferable.isDataFlavorSupported(DataFlavor.stringFlavor)) return null
    return try {
        transferable.getTransferData(DataFlavor.stringFlavor) as? String
    } catch (_: java.io.IOException) {
        null
    } catch (_: java.awt.datatransfer.UnsupportedFlavorException) {
        null
    }
}
