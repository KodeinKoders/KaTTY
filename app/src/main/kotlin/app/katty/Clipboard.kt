package app.katty

import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection

/** Puts [text] in the system clipboard. */
fun copyToClipboard(text: String) {
    try {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
    } catch (e: IllegalStateException) {
        // The clipboard is held by another program for a moment: the user tries again.
        org.slf4j.LoggerFactory.getLogger("app.katty.Clipboard").warn("The clipboard is not available", e)
    }
}

/** The text in the system clipboard, `null` when there is none. */
fun readClipboard(): String? =
    try {
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        if (clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)) clipboard.getData(DataFlavor.stringFlavor) as? String else null
    } catch (e: Exception) {
        null
    }
