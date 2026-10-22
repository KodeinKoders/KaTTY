package app.katty.terminal.compose

import java.io.File
import java.net.URI
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class LinkActionTest {
    private val directory = Files.createTempDirectory("katty-links").toFile()

    @AfterTest
    fun cleanUp() {
        directory.deleteRecursively()
    }

    private fun file(name: String, folder: Boolean = false) = directory.resolve(name).also { if (folder) it.mkdirs() else it.writeText("x") }

    @Test
    fun addressesOfTheWebAndMailAreOpenedByTheSystem() {
        for (url in listOf("https://example.com/a?b=c", "http://example.com", "ftp://example.com/f", "mailto:me@example.com")) {
            assertEquals(LinkAction.Browse(URI(url)), linkAction(url))
        }
    }

    @Test
    fun aFileIsShownInTheFinderAndNeverOpened() {
        // An application, a script that the Finder would run, and a plain file: none is opened.
        val application = file("Tool.app", folder = true)
        val script = file("run.command")
        val text = file("notes.txt")

        assertEquals(LinkAction.Reveal(application), linkAction(application.toURI().toString()))
        assertEquals(LinkAction.Reveal(script), linkAction(script.toURI().toString()))
        assertEquals(LinkAction.Reveal(text), linkAction("file://localhost${text.path}"))
    }

    @Test
    fun aFolderIsOpenedInTheFinder() {
        val folder = file("project", folder = true)

        assertEquals(LinkAction.OpenFolder(folder), linkAction(folder.toURI().toString()))
        // Its name is percent-encoded in the address.
        val spaced = file("my project", folder = true)
        assertEquals(LinkAction.OpenFolder(spaced), linkAction("file://${spaced.path.replace(" ", "%20")}"))
    }

    @Test
    fun whatCannotBeShownIsRefused() {
        assertIs<LinkAction.Refuse>(linkAction("file://${File(directory, "missing").path}"))
        assertIs<LinkAction.Refuse>(linkAction("file://other-host${directory.path}"))
        assertIs<LinkAction.Refuse>(linkAction("javascript:alert(1)"))
        assertIs<LinkAction.Refuse>(linkAction("x-man-page://ls"))
        assertIs<LinkAction.Refuse>(linkAction("not an address"))
    }
}
