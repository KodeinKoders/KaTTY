package app.katty.terminal.compose

import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/** The paths of files dropped on a terminal of Windows, which runs PowerShell: apart from [TerminalDropTest], which needs a Unix system. */
class PowerShellDropTest {
    // What File Explorer gives for files that are dragged: their list.
    private class Files(private val files: List<File>) : Transferable {
        override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(DataFlavor.javaFileListFlavor)

        override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = flavor == DataFlavor.javaFileListFlavor

        override fun getTransferData(flavor: DataFlavor): Any = if (flavor == DataFlavor.javaFileListFlavor) files else throw UnsupportedFlavorException(flavor)
    }

    @Test
    fun aPathIsAsItIsUnlessPowerShellWouldReadItOtherwise() {
        assertEquals("C:\\Users\\me\\Screenshot-2026.png", powerShellQuoted("C:\\Users\\me\\Screenshot-2026.png"), "The backslashes stay as they are")
        assertEquals("'C:\\Program Files\\a.txt'", powerShellQuoted("C:\\Program Files\\a.txt"))
        assertEquals("'C:\\tmp\\\$HOME (1) & a.txt'", powerShellQuoted("C:\\tmp\\\$HOME (1) & a.txt"), "Nothing is expanded in single quotes")
        assertEquals("'C:\\tmp\\it''s.txt'", powerShellQuoted("C:\\tmp\\it's.txt"))
        assertEquals("'C:\\tmp\\it\u2019\u2019s.txt'", powerShellQuoted("C:\\tmp\\it\u2019s.txt"), "A typographic quote is one for PowerShell")
        assertEquals("'C:\\tmp\\a`b@c.txt'", powerShellQuoted("C:\\tmp\\a`b@c.txt"))
    }

    @Test
    fun theDroppedFilesAreQuotedForPowerShellOnWindows() {
        val spaced = File("a b.png").absoluteFile
        val plain = File("c.txt").absoluteFile
        val files = Files(listOf(spaced, plain))

        assertEquals("${powerShellQuoted(spaced.path)} ${powerShellQuoted(plain.path)}", files.droppedText(windows = true))
        assertEquals("${shellEscaped(spaced.path)} ${shellEscaped(plain.path)}", files.droppedText(windows = false))
    }
}
