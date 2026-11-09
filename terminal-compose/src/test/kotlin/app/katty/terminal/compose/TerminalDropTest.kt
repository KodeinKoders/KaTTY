package app.katty.terminal.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import app.katty.terminal.TerminalSession
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.jetbrains.skia.Bitmap
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TerminalDropTest {
    // What the Finder gives for files that are dragged: their list.
    private class Files(private val files: List<File>) : Transferable {
        override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(DataFlavor.javaFileListFlavor)

        override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = flavor == DataFlavor.javaFileListFlavor

        override fun getTransferData(flavor: DataFlavor): Any = if (flavor == DataFlavor.javaFileListFlavor) files else throw UnsupportedFlavorException(flavor)
    }

    // An image that is no file, as a browser may drag one.
    private object Image : Transferable {
        override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(DataFlavor.imageFlavor)

        override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = flavor == DataFlavor.imageFlavor

        override fun getTransferData(flavor: DataFlavor): Any = throw UnsupportedFlavorException(flavor)
    }

    @Test
    fun aPathIsEscapedForAnyShellWithABackslash() {
        assertEquals("/tmp/a\\ b.png", shellEscaped("/tmp/a b.png"))
        assertEquals("/tmp/it\\'s\\ \\\$HOME\\ \\(1\\)\\ \\&\\ \\*.txt", shellEscaped("/tmp/it's \$HOME (1) & *.txt"))
        assertEquals("/tmp/a\\\tb", shellEscaped("/tmp/a\tb"))
        assertEquals("/Users/me/Screenshot-2026.png", shellEscaped("/Users/me/Screenshot-2026.png"), "Nothing that needs it")
    }

    @Test
    fun theFilesThatAreDroppedAreTheirPathsSeparatedByASpace() {
        val files = Files(listOf(File("/tmp/a b.png"), File("/tmp/c.txt")))

        assertTrue(files.isDroppable())
        assertEquals("/tmp/a\\ b.png /tmp/c.txt", files.droppedText())
    }

    @Test
    fun aFileWhoseNameHasALineBreakIsLeftOut() {
        assertEquals("/tmp/c.txt", Files(listOf(File("/tmp/a\nb"), File("/tmp/c.txt"))).droppedText())
        assertNull(Files(listOf(File("/tmp/a\nb"))).droppedText(), "Nothing is left")
    }

    @Test
    fun aTextIsDroppedAsItIsAndAnImageThatIsNoFileIsNot() {
        assertEquals("https://kodein.net/a b", StringSelection("https://kodein.net/a b").droppedText())
        assertFalse(Image.isDroppable())
        assertNull(Image.droppedText())
        assertFalse(drop(Image) { error("Nothing is pasted") }, "The drop is refused")
    }

    @Test
    fun whatIsDroppedIsPastedForTheProgram() {
        // The program reads what is pasted, byte by byte.
        TerminalSession.start(60, 5, command = listOf("/bin/sh", "-c", "stty raw -echo; head -c 13 | od -An -c; sleep 30")).use { session ->
            runBlocking { withTimeout(15_000) { session.frames.first { it.cols == 60 } } }

            assertTrue(drop(Files(listOf(File("/tmp/a b.png")))) { session.paste(it) })

            val read = runBlocking {
                withTimeout(15_000) {
                    session.frames.first { frame -> frame.rows.any { row -> row.cells.joinToString("") { it.text }.contains("p   n   g") } }
                }
            }.rows.joinToString("\n") { row -> row.cells.joinToString("") { it.text.ifEmpty { " " } }.trimEnd() }
            // `od -c` writes the backslash as it is, and the space as blanks.
            assertTrue(read.replace(Regex("\\s+"), " ").contains("/ t m p / a \\ b . p n g"), "What the program read:\n$read")
        }
    }

    @Test
    fun everyTerminalSaysItTakesTheDragAndTheOneUnderThePointerThatItGetsIt() {
        var feedback = DropFeedback.NONE
        fun on(event: DropEvent) = feedback.next(event).also { feedback = it }

        assertEquals(DropFeedback.AVAILABLE, on(DropEvent.STARTED), "Something it takes is over the window")
        assertEquals(DropFeedback.TARGET, on(DropEvent.ENTERED))
        assertEquals(DropFeedback.AVAILABLE, on(DropEvent.EXITED), "The pointer went to another terminal")
        assertEquals(DropFeedback.TARGET, on(DropEvent.ENTERED))
        assertEquals(DropFeedback.NONE, on(DropEvent.DROPPED))
        assertEquals(DropFeedback.NONE, DropFeedback.AVAILABLE.next(DropEvent.ENDED), "A drag that leaves the window, or is given up")
        assertEquals(DropFeedback.NONE, DropFeedback.NONE.next(DropEvent.EXITED))
    }

    @Test
    fun theLabelSaysWhatADropWillPaste() {
        assertEquals("Drop to paste the path of the file", Files(listOf(File("/tmp/a.png"))).dropLabel())
        assertEquals("Drop to paste the paths of the 3 files", Files(listOf(File("/a"), File("/b"), File("/c"))).dropLabel())
        assertEquals("Drop to paste the text", StringSelection("hello").dropLabel())
        // A list that cannot be read while dragging.
        val unreadable = object : Transferable {
            override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(DataFlavor.javaFileListFlavor)
            override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = flavor == DataFlavor.javaFileListFlavor
            override fun getTransferData(flavor: DataFlavor): Any = throw java.awt.dnd.InvalidDnDOperationException("not while dragging")
        }
        assertEquals("Drop to paste the paths of the files", unreadable.dropLabel())
    }

    @Test
    fun aTerminalShowsThatItTakesADropAndTheOneUnderThePointerSaysItGetsIt() {
        fun render(name: String, feedback: DropFeedback, width: Int = 400): Bitmap {
            val scene = ImageComposeScene(width = width, height = 200, density = Density(2f)) {
                val measurer = androidx.compose.ui.text.rememberTextMeasurer()
                Box(Modifier.fillMaxSize().background(Color.Black).dropFeedback(feedback, Color.White, Color.Black, "Drop to paste the path of the file", measurer, DROP_LABEL_STYLE))
            }
            try {
                val image = scene.render()
                File("build/renders").apply { mkdirs() }.resolve("$name.png").writeBytes(image.encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)!!.bytes)
                return Bitmap.makeFromImage(image)
            } finally {
                scene.close()
            }
        }
        fun Bitmap.at(x: Int, y: Int) = getColor(x, y) and 0xFFFFFF

        val none = render("drop-none", DropFeedback.NONE)
        assertEquals(0x000000, none.at(1, 100), "Nothing while nothing is dragged")

        // A dashed line: some of the left edge is drawn and some is not.
        val available = render("drop-available", DropFeedback.AVAILABLE)
        val edge = (0 until 200).map { available.at(1, it) }
        assertTrue(edge.any { it != 0 } && edge.any { it == 0 }, "Dashes along the edge")
        assertEquals(0x000000, available.at(60, 100), "Nothing inside")

        val target = render("drop-target", DropFeedback.TARGET)
        assertTrue((0 until 200).all { target.at(1, it) != 0 }, "A solid line")
        assertTrue(target.at(12, 20) in 0x101010..0x404040, "A tint over the terminal, outside the label: ${target.at(12, 20).toString(16)}")
        val middle = (150..250).flatMap { x -> (80..120).map { y -> target.at(x, y) } }
        assertTrue(middle.count { it == 0xFFFFFF } > 100 && middle.any { it == 0x000000 }, "The label, dark on light, in the middle")

        // A wide terminal has it on one line, a narrow one on two: it is never left out for lack of width.
        val wide = render("drop-target-wide", DropFeedback.TARGET, width = 1200)
        val narrowRows = (0 until 200).count { y -> (0 until 400).any { x -> target.at(x, y) == 0x000000 } }
        val wideRows = (0 until 200).count { y -> (0 until 1200).any { x -> wide.at(x, y) == 0x000000 } }
        assertTrue(narrowRows > wideRows, "Two lines in the narrow one ($narrowRows rows of text) and one in the wide one ($wideRows)")
    }
}
