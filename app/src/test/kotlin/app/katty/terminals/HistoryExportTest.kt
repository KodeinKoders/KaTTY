package app.katty.terminals

import app.katty.terminal.TerminalColor
import app.katty.terminal.TerminalHistory
import java.nio.file.Files
import java.time.LocalDateTime
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HistoryExportTest {
    private val now = LocalDateTime.of(2026, 11, 10, 19, 43, 12)

    @Test
    fun theNameIsTheTerminalsAndTheTimeInIsoOrder() {
        assertEquals("api build 2026-11-10 19-43.html", exportFileName("api build", now, ExportFormat.HTML))
        assertEquals("api build 2026-11-10 19-43.txt", exportFileName("api build", now, ExportFormat.TEXT))
        assertEquals("api build 2026-11-10 19-43.ans", exportFileName("api build", now, ExportFormat.ANSI))
    }

    @Test
    fun whatMacOsRefusesInANameIsADash() {
        assertEquals("~-src-app- vim 2026-11-10 19-43.txt", exportFileName("~/src/app: vim", now, ExportFormat.TEXT))
        assertEquals("Terminal 2026-11-10 19-43.txt", exportFileName("  ", now, ExportFormat.TEXT))
        // A name that starts with a dot is hidden by the Finder.
        assertEquals("zshrc 2026-11-10 19-43.txt", exportFileName(".zshrc", now, ExportFormat.TEXT))
    }

    @Test
    fun theHtmlIsAPageOnTheColorsOfTheTerminal() {
        val fragment = "<div style=\"font-family: monospace;\">  | a table |\n  | line |</div>"

        val page = htmlDocument(fragment, "a <b> & \"c\"", TerminalColor(0xF8F8F2), TerminalColor(0x282A36))

        assertTrue(page.startsWith("<!DOCTYPE html>"))
        assertTrue("<meta charset=\"utf-8\">" in page)
        assertTrue("<title>a &lt;b&gt; &amp; &quot;c&quot;</title>" in page, "The title is escaped")
        assertTrue("background: #282a36; color: #f8f8f2;" in page)
        assertTrue("font-family: ui-monospace" in page, "Not Safari's Courier")
        assertTrue("a { color: inherit; }" in page, "Not the browser's blue")
        assertTrue(fragment in page, "The fragment is as it is, its lines that start with a margin too")
    }

    @Test
    fun theTextAndAnsiEndWithANewlineAndTheHtmlIsAPage() {
        val history = TerminalHistory("<text>", TerminalColor(0xFFFFFF), TerminalColor(0x000000))

        assertEquals("<text>\n", exportContent(history, ExportFormat.TEXT, "t"))
        assertEquals("<text>\r\n", exportContent(history, ExportFormat.ANSI, "t"), "As the other lines of VT")
        assertEquals("a\n", exportContent(history.copy(text = "a\n"), ExportFormat.TEXT, "t"), "Not two")
        assertEquals("", exportContent(history.copy(text = ""), ExportFormat.TEXT, "t"))
        assertTrue(exportContent(history, ExportFormat.HTML, "t").startsWith("<!DOCTYPE html>"))
    }

    @Test
    fun aFailureSaysWhyInWords() {
        assertEquals("its folder is not there", exportFailureReason(java.nio.file.NoSuchFileException("/x/.history.txt123.part")))
        assertEquals("permission denied", exportFailureReason(java.nio.file.AccessDeniedException("/x")))
        assertEquals("No space left on device", exportFailureReason(java.nio.file.FileSystemException("/x", null, "No space left on device")))
        assertEquals("boom", exportFailureReason(IllegalStateException("boom")))
    }

    @Test
    fun aFileIsWrittenWholeAndReplacesTheOneThatWasThere() {
        val folder = Files.createTempDirectory("katty-export")
        val file = folder.resolve("history 2026-11-10 19-43.txt")
        file.writeText("what was there before, which is longer")

        writeExport(file, "héllo\n")

        assertEquals("héllo\n", file.readText())
        assertEquals(listOf(file), folder.listDirectoryEntries(), "Nothing else is left in the folder")
    }
}
