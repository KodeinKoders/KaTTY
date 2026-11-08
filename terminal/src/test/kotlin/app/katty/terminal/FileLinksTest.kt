package app.katty.terminal

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class FileLinksTest {
    // The real path: macOS's temporary directory is behind a link (/var is /private/var), and the links name what the program printed.
    private val root: Path = Files.createTempDirectory("katty-file-links").toRealPath()

    @AfterTest
    fun delete() {
        root.toFile().deleteRecursively()
    }

    private fun file(relative: String): Path = root.resolve(relative).also {
        it.parent.createDirectories()
        it.writeText("x")
    }

    private fun written(path: String, line: Int? = null, column: Int? = null) =
        TerminalLink(path, listOf(TerminalLinkSegment(0, 0..path.length)), TerminalFilePosition(path, line, column))

    @Test
    fun aRelativePathIsTheFileOfTheFirstDirectoryThatHasIt() {
        val foo = file("b/src/Foo.kt")
        val link = fileLink(written("src/Foo.kt", 42, 5)) { listOf(root.resolve("a").toString(), root.resolve("b").toString()) }

        assertEquals(TerminalFilePosition(foo.toString(), 42, 5), link?.file)
        assertEquals(foo.toUri().toString(), link?.url, "Its address, without the line, for a host that only opens addresses")
    }

    @Test
    fun anAbsolutePathAsksForNoDirectory() {
        val foo = file("Foo.kt")

        val link = fileLink(written(foo.toString(), 3)) { error("No directory is needed") }

        assertEquals(TerminalFilePosition(foo.toString(), 3), link?.file)
    }

    @Test
    fun aPathThatIsNotThereIsNoLink() {
        file("src/Foo.kt")
        val directories = { listOf(root.toString()) }

        assertNull(fileLink(written("src/Bar.kt", 1), directories), "No such file")
        assertNull(fileLink(written("build"), directories), "No such folder")
        assertNull(fileLink(written("src/Foo.kt"), { emptyList() }), "No directory to look in")
    }

    @Test
    fun aFolderIsALinkWithNoLine() {
        file("src/Foo.kt")
        val src = root.resolve("src")

        val link = fileLink(written("src", 12, 5)) { listOf(root.toString()) }

        assertEquals(TerminalFilePosition(src.toString(), directory = true), link?.file)
        assertEquals(src.toUri().toString(), link?.url)
    }

    @Test
    fun anApplicationIsAFolder() {
        // What a click does with it is the host's: an editor opens it as a folder, and the default shows it in the Finder, which never runs it.
        val tool = root.resolve("Tool.app/Contents").createDirectories().parent

        assertEquals(TerminalFilePosition(tool.toString(), directory = true), fileLink(written("Tool.app")) { listOf(root.toString()) }?.file)
    }

    @Test
    fun aFileAddressHasTheLineAndColumnThatEndItsPath() {
        val foo = file("src/Foo.kt")

        val link = fileLink(TerminalLink("${foo.toUri()}:12:5", 0, 0..10)) { error("An address names its file") }

        assertEquals(TerminalFilePosition(foo.toString(), 12, 5), link?.file)
        assertEquals(foo.toUri().toString(), link?.url, "Its address is the file's, without the line")
    }

    @Test
    fun aFileAddressIsAFileWhenItIsOne() {
        val foo = file("notes.md")

        assertEquals(TerminalFilePosition(foo.toString()), fileLink(TerminalLink(foo.toUri().toString(), 0, 0..10)) { emptyList() }?.file)
    }

    @Test
    fun aFileAddressOfAFolderIsThatFolder() {
        val folder = root.resolve("dir").createDirectories()

        assertEquals(TerminalFilePosition(folder.toString(), directory = true), fileLink(TerminalLink(folder.toUri().toString(), 0, 0..10)) { emptyList() }?.file)
    }

    @Test
    fun anAddressThatIsNoFileIsLeftAsItIs() {
        val links = listOf(
            TerminalLink("https://kodein.net/a.kt:3", 0, 0..10),
            TerminalLink("file:///no/such/file.kt:3", 0, 0..10),
            TerminalLink("file://server.example.com/a.kt", 0, 0..10),
        )

        for (link in links) assertSame(link, fileLink(link) { emptyList() }, link.url)
    }

    @Test
    fun theSessionLooksForAPathInTheDirectoryThatTheShellReported() {
        val foo = file("src/Foo.kt")
        val script = "printf '\\033]7;file://localhost$root\\007e: src/Foo.kt:42:5 bad\\r\\nsrc/Bar.kt:1\\r\\nbuild  src\\r\\nEND'; sleep 30"
        TerminalSession.start(40, 6, command = listOf("/bin/sh", "-c", script), shellIntegration = false).use { session ->
            runBlocking {
                withTimeout(15_000) {
                    session.frames.first { frame -> frame.rows[3].cells.take(3).joinToString("") { it.text } == "END" }
                    session.workingDirectory.first { it == root.toString() }
                }
            }

            val link = session.linkAt(5, 0)
            assertEquals(TerminalFilePosition(foo.toString(), 42, 5), link?.file, "The screen: ${session.frames.value.rows.take(4).map { r -> r.cells.joinToString("") { it.text } }}")
            assertEquals(listOf(TerminalLinkSegment(0, 3..17)), link?.segments)
            assertNull(session.linkAt(2, 1), "There is no such file")
            // What `ls` prints: a folder that is there, and one that is not.
            assertEquals(TerminalFilePosition(root.resolve("src").toString(), directory = true), session.linkAt(8, 2)?.file)
            assertNull(session.linkAt(1, 2), "There is no such folder")
        }
    }
}
