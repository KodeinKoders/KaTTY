package app.katty.terminal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TerminalLinksTest {
    // The address that the character at the start of [around] is part of.
    private fun url(text: String, around: String = "http"): String? {
        val index = text.indexOf(around).takeIf { it >= 0 } ?: error("$around is not in $text")
        return findUrl(text, index)?.let { text.substring(it.first, it.last + 1) }
    }

    @Test
    fun findsAnAddress() {
        assertEquals("https://kodein.net/a/b?c=d#e", url("see https://kodein.net/a/b?c=d#e now"))
        assertEquals("ftp://host/file", url("ftp://host/file", "ftp"))
        assertEquals("file:///tmp/a.txt", url("open file:///tmp/a.txt", "file"))
        assertEquals("mailto:a@b.c", url("write to mailto:a@b.c please", "mailto"))
    }

    @Test
    fun anyCharacterOfTheAddressIsPartOfIt() {
        val text = "go https://kodein.net/x now"
        val range = findUrl(text, text.indexOf("kodein"))
        assertEquals("https://kodein.net/x", text.substring(range!!.first, range.last + 1))
        assertNull(findUrl(text, 1), "Before it")
        assertNull(findUrl(text, text.indexOf("now")), "After it")
    }

    @Test
    fun whatEndsASentenceIsNotPartOfTheAddress() {
        assertEquals("https://kodein.net", url("see https://kodein.net."))
        assertEquals("https://kodein.net", url("see https://kodein.net, then"))
        assertEquals("https://kodein.net/a", url("is it https://kodein.net/a?"))
        assertEquals("https://kodein.net", url("\"https://kodein.net\""))
        assertEquals("https://kodein.net", url("'https://kodein.net';"))
    }

    @Test
    fun aClosingBracketWithoutItsOpeningOneIsNotPartOfTheAddress() {
        assertEquals("https://kodein.net", url("(https://kodein.net)"))
        assertEquals("https://kodein.net/a", url("[https://kodein.net/a]"))
        assertEquals("https://en.wikipedia.org/wiki/A_(b)", url("https://en.wikipedia.org/wiki/A_(b)"))
        assertEquals("https://en.wikipedia.org/wiki/A_(b)", url("(https://en.wikipedia.org/wiki/A_(b))"))
    }

    @Test
    fun findsTheRightOneOfSeveral() {
        val text = "https://a.example and https://b.example"
        assertEquals("https://a.example", findUrl(text, 3)?.let { text.substring(it.first, it.last + 1) })
        assertEquals("https://b.example", findUrl(text, 30)?.let { text.substring(it.first, it.last + 1) })
    }

    @Test
    fun noAddressInPlainText() {
        assertNull(findUrl("nothing to see here, http is a word", 0))
        assertNull(findUrl("http:/", 0))
    }

    // The path that the character at the start of [around] is part of, with its line and column.
    private fun path(text: String, around: String): Triple<String, Int?, Int?>? {
        val index = text.indexOf(around).takeIf { it >= 0 } ?: error("$around is not in $text")
        return findPath(text, index)?.let { Triple(it.path, it.line, it.column) }
    }

    // What the link covers.
    private fun pathText(text: String, around: String): String? {
        val index = text.indexOf(around)
        return findPath(text, index)?.range?.let { text.substring(it.first, it.last + 1) }
    }

    @Test
    fun findsThePathOfAFileWithItsLineAndColumn() {
        assertEquals(Triple("src/Foo.kt", 42, null), path("src/Foo.kt:42", "Foo"))
        assertEquals(Triple("src/main.rs", 42, 5), path("  --> src/main.rs:42:5", "main"))
        assertEquals(Triple("/Users/me/a/B.java", 7, null), path("/Users/me/a/B.java:7: error: ';' expected", "B.java"))
        assertEquals(Triple("~/notes.md", 3, null), path("see ~/notes.md:3", "notes"))
        assertEquals(Triple("./build.sh", null, null), path("run ./build.sh now", "build"))
    }

    @Test
    fun aPathIsFoundFromAnyOfItsCharactersAndItsLine() {
        val text = "e: src/Foo.kt:42:5 Unresolved reference"
        assertEquals("src/Foo.kt:42:5", pathText(text, "src"))
        assertEquals("src/Foo.kt:42:5", pathText(text, "42"))
        assertEquals("src/Foo.kt:42:5", pathText(text, ":5"))
        assertEquals("Unresolved", pathText(text, "Unresolved"), "What follows it is a word of its own")
        assertNull(findPath(text, text.indexOf(" Unresolved")), "Nor is the space between them")
    }

    @Test
    fun whatIsAroundAPathIsNotPartOfIt() {
        assertEquals(Triple("Foo.kt", 42, null), path("at app.Foo.bar(Foo.kt:42)", "Foo.kt"))
        assertEquals(Triple("x/y.py", null, null), path("File \"x/y.py\", line 3", "x/y"))
        assertEquals(Triple("src/Foo.kt", null, null), path("see src/Foo.kt.", "src"), "The end of a sentence")
        assertEquals(Triple("src/Foo.kt", 12, null), path("in src/Foo.kt:12.", "src"))
        assertEquals("src/Foo.kt:12", pathText("in src/Foo.kt:12:", "src"), "A colon after the line")
        assertEquals(Triple("README.adoc", null, null), path("\tmodified:   README.adoc", "README"))
    }

    @Test
    fun aBareNameMayBeAPathWhichTheDiskDecides() {
        // `ls` prints folders and files with neither a slash nor an extension: only the ones that are there are links.
        assertEquals(Triple("src", null, null), path("build  docs  src", "src"))
        assertEquals(Triple("Makefile", 3, null), path("an error: Makefile:3", "Makefile"))
        assertEquals(Triple("localhost", 8080, null), path("listening on localhost:8080", "localhost"))
    }

    @Test
    fun aWordWithoutALetterIsNoPath() {
        assertNull(path("at 12:34:56", "12"))
        assertNull(path("version 1.2.3", "1.2"))
        assertNull(path("cd ..", ".."))
    }

    @Test
    fun aLineOfZeroIsNone() {
        assertEquals(Triple("a/b.kt", null, null), path("a/b.kt:0:4", "a/b"))
    }

    @Test
    fun decodesTheWorkingDirectoryTheShellReported() {
        assertEquals("/Users/me/code", decodeWorkingDirectory("file://localhost/Users/me/code"))
        assertEquals("/Users/me/a b", decodeWorkingDirectory("file:///Users/me/a%20b"))
        assertEquals("/tmp/é", decodeWorkingDirectory("file:///tmp/%C3%A9"))
        assertEquals("/tmp/100%", decodeWorkingDirectory("file:///tmp/100%"), "A percent that is not an escape is kept")
        assertEquals("/just/a/path", decodeWorkingDirectory("/just/a/path"))
        assertNull(decodeWorkingDirectory(""))
        assertNull(decodeWorkingDirectory("file://localhost"))
    }

    @Test
    fun aDirectoryOfThisMachineIsOneWhateverItsNameIsWritten() {
        val name = localHostName()!!
        assertEquals("/tmp", decodeWorkingDirectory("file://$name/tmp"))
        assertEquals("/tmp", decodeWorkingDirectory("file://${name.uppercase()}/tmp"))
        // zsh's `$HOST` may be the short name, and the system's the long one, or the other way round.
        assertEquals("/tmp", decodeWorkingDirectory("file://${name.substringBefore('.')}.local/tmp"))
    }

    @Test
    fun aDirectoryOfAnotherMachineIsNotThisOnes() {
        // After `ssh server`, the shell of the server says where it is: on the server.
        assertTrue(isRemoteDirectory("file://build-server.example.com/home/me/project"))
        assertNull(decodeWorkingDirectory("file://build-server.example.com/home/me/project"))
        assertFalse(isRemoteDirectory("file:///tmp"))
        assertFalse(isRemoteDirectory("/tmp"))
    }

    @Test
    fun aNameThatTheNetworkChangedIsReadAgainWhenAnAddressDoesNotMatch() {
        var remembered: String? = "old.home"
        var reads = 0
        val current = { reads++; "new.home" }
        val remember = { name: String -> remembered = name }

        assertTrue(isThisMachine("old.home", { remembered }, current, remember), "The name that is known needs no new reading")
        assertEquals(0, reads)
        // A shell that was started after the network changed says the new name.
        assertTrue(isThisMachine("new.home", { remembered }, current, remember))
        assertEquals("new.home", remembered, "The new name is kept")
        assertTrue(isThisMachine("NEW.home", { remembered }, current, remember))
        assertEquals(1, reads, "Once it is kept it is not read again")
    }

    @Test
    fun anotherMachineIsStillAnotherMachineWhenTheNameIsReadAgain() {
        var remembered: String? = "mac.home"
        assertFalse(isThisMachine("build-server.example.com", { remembered }, { "mac.home" }, { remembered = it }))
        assertFalse(isThisMachine("build-server.example.com", { remembered }, { null }, { remembered = it }), "A name that cannot be read says nothing")
        assertFalse(isThisMachine("build-server.example.com", { remembered }, { "other.home" }, { remembered = it }))
        assertEquals("other.home", remembered)
    }

    @Test
    fun anEscapeIsTwoHexadecimalDigits() {
        assertEquals("/tmp/%+F", decodeWorkingDirectory("file:///tmp/%+F"))
        assertEquals("/tmp/%-1", decodeWorkingDirectory("file:///tmp/%-1"))
    }
}
