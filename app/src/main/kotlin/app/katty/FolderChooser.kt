package app.katty

import java.awt.FileDialog
import java.awt.Frame

/**
 * Asks the user for a folder with the system's own dialog (the design does not draw one).
 *
 * @param parent The window the dialog belongs to.
 * @param initial The folder it opens in.
 * @return The path of the folder, `null` if the user cancelled.
 */
fun chooseFolder(parent: Frame?, title: String, initial: String? = null): String? {
    // On macOS, a file dialog that is told so chooses folders. It is a property of the whole process, so it is put back.
    val before = System.getProperty(FOR_DIRECTORIES)
    System.setProperty(FOR_DIRECTORIES, "true")
    try {
        val dialog = FileDialog(parent, title, FileDialog.LOAD)
        if (initial != null) dialog.directory = initial
        // Modal: it returns when the user has answered.
        dialog.isVisible = true
        val directory = dialog.directory ?: return null
        val file = dialog.file ?: return null
        return java.io.File(directory, file).path
    } finally {
        if (before == null) System.clearProperty(FOR_DIRECTORIES) else System.setProperty(FOR_DIRECTORIES, before)
    }
}

private const val FOR_DIRECTORIES = "apple.awt.fileDialogForDirectories"
