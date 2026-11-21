package app.katty

import app.katty.core.isMacOs
import java.awt.FileDialog
import java.awt.Frame
import javax.swing.JFileChooser
import javax.swing.UIManager

/**
 * Asks the user for a folder with the system's own dialog (the design does not draw one).
 *
 * @param parent The window the dialog belongs to.
 * @param initial The folder it opens in.
 * @return The path of the folder, `null` if the user cancelled.
 */
fun chooseFolder(parent: Frame?, title: String, initial: String? = null): String? {
    if (!isMacOs) return chooseFolderWithSwing(parent, title, initial)
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

// AWT's file dialog only chooses files outside macOS: Swing's chooser, in the look of the system, which it takes when it is made. The look is
// a setting of the whole process, which Compose does not use, and it is put back at once.
private fun chooseFolderWithSwing(parent: Frame?, title: String, initial: String?): String? {
    val before = UIManager.getLookAndFeel()
    val chooser = try {
        UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())
        JFileChooser(initial)
    } catch (_: Exception) {
        JFileChooser(initial)
    } finally {
        try { UIManager.setLookAndFeel(before) } catch (_: Exception) {}
    }
    chooser.dialogTitle = title
    chooser.fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
    // Modal: it returns when the user has answered.
    return if (chooser.showOpenDialog(parent) == JFileChooser.APPROVE_OPTION) chooser.selectedFile?.path else null
}

/**
 * Asks where a file is saved, with the save panel of the system, proposing [name] in [directory]: the path chosen, `null` when the user cancelled.
 * The panel asks itself before a file that is there is replaced.
 */
fun chooseSaveFile(parent: Frame?, title: String, directory: String?, name: String): String? {
    val dialog = FileDialog(parent, title, FileDialog.SAVE)
    if (directory != null) dialog.directory = directory
    dialog.file = name
    // Modal: it returns when the user has answered.
    dialog.isVisible = true
    val chosenDirectory = dialog.directory ?: return null
    val file = dialog.file ?: return null
    return java.io.File(chosenDirectory, file).path
}

private const val FOR_DIRECTORIES = "apple.awt.fileDialogForDirectories"
