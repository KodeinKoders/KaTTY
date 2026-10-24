package app.katty.terminal

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** What the terminal answers or tells the programs about itself, and how it follows what they ask of the screen. */
class TerminalAnswersTest {
    private val sessions = mutableListOf<TerminalSession>()

    @AfterTest
    fun close() {
        sessions.forEach { it.close() }
    }

    private fun start(script: String, rows: Int = 5, theme: TerminalTheme? = null): TerminalSession =
        TerminalSession.start(80, rows, command = listOf("/bin/sh", "-c", script), theme = theme).also { sessions += it }

    private fun TerminalFrame.text(): String =
        rows.joinToString("\n") { row -> row.cells.joinToString("") { it.text.ifEmpty { " " } }.trimEnd() }

    private fun TerminalSession.awaitFrame(condition: (TerminalFrame) -> Boolean): TerminalFrame = runBlocking {
        try {
            withTimeout(TIMEOUT_MS) { frames.first(condition) }
        } catch (e: TimeoutCancellationException) {
            throw AssertionError("No frame matched in ${TIMEOUT_MS}ms. The last one:\n${frames.value.text()}", e)
        }
    }

    // What od prints, with the blanks that it puts between the bytes taken out.
    private fun TerminalFrame.compact(): String = text().replace(Regex("\\s+"), "")

    private fun lightTheme() = TerminalTheme(
        background = TerminalColor(0xFFFFFF),
        foreground = TerminalColor(0x000000),
        ansi = List(TerminalTheme.ANSI_COLORS) { TerminalColor(0x808080) },
    )

    @Test
    fun sendsAltAndAKeyAsEscapeAndTheKey() {
        val session = start("stty raw -echo; echo ready; head -c2 | od -An -tx1")
        session.awaitFrame { "ready" in it.text() }

        session.sendKey(KeyInput(TerminalKey.B, mods = setOf(TerminalModifier.ALT), text = "b", unshiftedCodepoint = 'b'.code))

        // ESC b: what moves back by a word in the shell's line editor.
        session.awaitFrame { "1b62" in it.compact() }
    }

    @Test
    fun aPasteWithANewlineIsUnsafeUnlessTheProgramAskedForBracketedPaste() {
        val plain = start("sleep 30")
        val bracketed = start("sleep 30")
        bracketed.writeToScreen("\u001b[?2004h")

        assertTrue(plain.isPasteUnsafe("ls\nrm -rf x"))
        // A carriage return is Enter too.
        assertTrue(plain.isPasteUnsafe("ls\rrm -rf x"))
        assertFalse(plain.isPasteUnsafe("ls"))
        assertFalse(bracketed.isPasteUnsafe("ls\nrm -rf x"))
        // The marker that ends a bracketed paste lets the rest out of it.
        assertTrue(bracketed.isPasteUnsafe("ls\u001b[201~\nrm -rf x"))
    }

    @Test
    fun keepsTheColorOfAnUnderline() {
        // A curly underline in a color of its own, then one in a color of the palette (red), then none.
        val session = start("""printf '\033[4:3;58:2::255:0:0mrgb\033[31;58;5;1mpal\033[0mnone'; sleep 30""")

        val frame = session.awaitFrame { it.text().startsWith("rgbpalnone") }

        val cells = frame.rows[0].cells
        assertEquals(TerminalUnderline.CURLY, cells[0].style.underline)
        assertEquals(TerminalColor(0xFF0000), cells[0].style.underlineColor)
        // The red of the palette, which a theme decides: the text of the same cell is in it.
        assertNotNull(cells[3].foreground)
        assertEquals(cells[3].foreground, cells[3].style.underlineColor)
        assertNull(cells[6].style.underlineColor)
    }

    @Test
    fun aColorThatAProgramSetGoesBackToTheThemesWhenItResetsIt() {
        val theme = lightTheme()
        val session = start("sleep 30", theme = theme)
        session.awaitFrame { it.background == theme.background }

        // OSC 11 sets the background, OSC 111 resets it; OSC 10 and 110 are the foreground.
        session.writeToScreen("\u001b]11;rgb:ff/00/00\u0007\u001b]10;rgb:00/00/ff\u0007")
        session.awaitFrame { it.background == TerminalColor(0xFF0000) && it.foreground == TerminalColor(0x0000FF) }

        session.writeToScreen("\u001b]111\u0007\u001b]110\u0007")
        session.awaitFrame { it.background == theme.background && it.foreground == theme.foreground }
    }

    @Test
    fun aPaletteColorThatAProgramSetGoesBackToTheThemesWhenItResetsIt() {
        val session = start("sleep 30", theme = lightTheme())
        session.writeToScreen("\u001b[31mA")
        val original = session.awaitFrame { it.text().startsWith("A") }.rows[0].cells[0].foreground

        // OSC 4 sets a palette color, OSC 104 resets it (all of them without an index).
        session.writeToScreen("\u001b[1;1H\u001b]4;1;rgb:00/ff/00\u0007\u001b[31mB")
        session.awaitFrame { it.rows[0].cells[0].foreground == TerminalColor(0x00FF00) || it.rows[0].cells[1].foreground == TerminalColor(0x00FF00) }

        session.writeToScreen("\u001b]104\u0007\u001b[1;1H\u001b[31mC")
        val frame = session.awaitFrame { it.text().startsWith("C") }
        assertEquals(original, frame.rows[0].cells[0].foreground)
    }

    @Test
    fun aProgramThatAsksForReleasesAndAllKeysIsToldOfTheKeyAndItsRelease() {
        // Flags 1 + 2 + 8: disambiguate, event types, all keys as escape codes.
        val session = start("""printf '\033[>11u'; stty raw -echo; echo ready; head -c14 | od -An -tx1""")
        session.awaitFrame { "ready" in it.text() }

        session.sendKey(KeyInput(TerminalKey.A, text = "a", unshiftedCodepoint = 'a'.code))
        session.sendKey(KeyInput(TerminalKey.A, TerminalKeyAction.RELEASE, unshiftedCodepoint = 'a'.code))

        // CSI 97 u, then CSI 97 ; 1 : 3 u
        session.awaitFrame { "1b5b393775" + "1b5b39373b313a3375" in it.compact() }
    }

    @Test
    fun boldTextInANormalColorIsInTheBrightOneWhenAsked() {
        val session = TerminalSession.start(
            80, 5, command = listOf("/bin/sh", "-c", """printf '\033[31mn\033[1mb\033[0;1;38;2;1;2;3mt\033[0;1mp'; sleep 30"""),
            boldIsBright = true,
        ).also { sessions += it }

        val bright = session.awaitFrame { it.text().startsWith("nbtp") }.rows[0].cells
        session.setBoldIsBright(false)
        val normal = session.awaitFrame { frame -> frame.rows[0].cells[1].foreground != bright[1].foreground }.rows[0].cells

        // Red is 31, and bold red is the palette's 9 instead; it stays when it is not bold.
        assertEquals(normal[0].foreground, bright[0].foreground)
        assertNotEquals(normal[1].foreground, bright[1].foreground)
        // A color that is not of the palette, and the default one, are what they are.
        assertEquals(TerminalColor(0x010203), bright[2].foreground)
        assertEquals(normal[3].foreground, bright[3].foreground)
        assertEquals(normal[2].foreground, bright[2].foreground)
    }

    @Test
    fun theCursorIsAtAPasswordPromptWhileTheProgramReadsALineWithTheEchoOff() {
        val prompt = start("stty -echo; echo prompt; sleep 30")
        val normal = start("echo normal; sleep 30")
        // A program that reads a key at a time is not reading a password.
        val keys = start("stty -echo -icanon; echo keys; sleep 30")

        prompt.awaitFrame { "prompt" in it.text() && it.cursor.passwordInput }
        normal.awaitFrame { "normal" in it.text() }
        keys.awaitFrame { "keys" in it.text() }
        Thread.sleep(200)
        normal.writeToScreen("x")
        keys.writeToScreen("x")

        assertFalse(normal.awaitFrame { "normalx" in it.text().replace("\n", "") || "x" in it.text() }.cursor.passwordInput)
        assertFalse(keys.awaitFrame { "x" in it.text() }.cursor.passwordInput)
    }

    @Test
    fun givesTheSelectedTextInTheFormatThatIsAskedFor() {
        val session = start("""printf '\033[31mhello'; sleep 30""")
        session.awaitFrame { it.text().startsWith("hello") }

        session.selectAll()

        assertEquals("hello", session.selectedText())
        // Styles and colors: the escape sequence of the color, and the markup.
        assertTrue("\u001b[" in session.selectedText(TerminalTextFormat.VT)!!, session.selectedText(TerminalTextFormat.VT))
        assertTrue("<" in session.selectedText(TerminalTextFormat.HTML)!! && "hello" in session.selectedText(TerminalTextFormat.HTML)!!)
    }

    @Test
    fun theHtmlOfASelectionHasWhatTheProgramWroteAsTextNotAsMarkup() {
        // What a program writes is its own: pasted as HTML, it must not become a tag, an attribute or an entity of the page.
        val session = start("""printf '<b onclick="x">&amp;</b>'; sleep 30""")
        session.awaitFrame { it.text().startsWith("<b") }

        session.selectAll()
        val html = session.selectedText(TerminalTextFormat.HTML)!!

        assertTrue("&lt;b" in html, html)
        assertTrue("&amp;amp;" in html, html)
        assertFalse("<b" in html, html)
        assertFalse("\"x\"" in html, html)
    }

    @Test
    fun theProgramFindsTheTerminalItRunsIn() {
        val session = start("""echo "program:${'$'}TERM_PROGRAM"""")

        session.awaitFrame { "program:KaTTY" in it.text() }
    }

    @Test
    fun answersXtversionWithItsOwnName() {
        val session = start("""stty raw -echo; echo ready; head -c1 >/dev/null; printf '\033[>q'; head -c11 | od -An -tx1""")
        session.awaitFrame { "ready" in it.text() }

        session.paste("x")

        // ESC P > | K a T T Y ESC \
        session.awaitFrame { "1b503e7c4b615454591b5c" in it.compact() }
    }

    @Test
    fun answersTheColorSchemeQueryByTheTheme() {
        val dark = start("""printf '\033[?996n'; stty raw -echo; echo ready; head -c9 | tr -d '\033' | od -An -c""")
        val light = start(
            """printf '\033[?996n'; stty raw -echo; echo ready; head -c9 | tr -d '\033' | od -An -c""",
            theme = lightTheme(),
        )

        // CSI ? 997 ; 1 n for dark, 2 for light.
        dark.awaitFrame { "[?997;1n" in it.compact() }
        light.awaitFrame { "[?997;2n" in it.compact() }
    }

    @Test
    fun tellsAProgramThatAskedWhenTheSchemeChanges() {
        val session = start("""printf '\033[?2031h'; stty raw -echo; echo ready; head -c9 | tr -d '\033' | od -An -c""")
        session.awaitFrame { "ready" in it.text() }

        session.setTheme(lightTheme())

        session.awaitFrame { "[?997;2n" in it.compact() }
    }

    @Test
    fun doesNotTellAProgramThatDidNotAsk() {
        val session = start("""stty raw -echo; echo ready; head -c1 | od -An -c""")
        session.awaitFrame { "ready" in it.text() }

        session.setTheme(lightTheme())
        // Nothing arrives that would end the read: only a key does.
        Thread.sleep(300)
        session.paste("x")

        // The one byte that it read is the key's, not a report's.
        session.awaitFrame { "x" in it.text().lines().drop(1).joinToString("") }
    }

    @Test
    fun keepsTheScreenWhileTheProgramHoldsIt() {
        val session = start("sleep 30")
        session.writeToScreen("before")
        session.awaitFrame { "before" in it.text() }

        session.writeToScreen("\u001b[?2026h\u001b[2J\u001b[Hdrawing")
        // Time is what is tested: a frame would be made within a few milliseconds.
        Thread.sleep(300)

        assertTrue("before" in session.frames.value.text(), "The held screen changed:\n${session.frames.value.text()}")
        assertTrue("drawing" !in session.frames.value.text(), "A half-drawn frame was shown:\n${session.frames.value.text()}")

        session.writeToScreen(" done\u001b[?2026l")

        val frame = session.awaitFrame { "drawing done" in it.text() }
        assertTrue("before" !in frame.text())
    }

    @Test
    fun showsTheScreenOfAProgramThatNeverEndsTheHold() {
        val session = start("sleep 30")

        val started = TimeSource.Monotonic.markNow()
        session.writeToScreen("\u001b[?2026hstuck")

        session.awaitFrame { "stuck" in it.text() }
        // After a second, as Ghostty does, and not at once.
        assertTrue(started.elapsedNow() >= 0.9.seconds, "It was shown after ${started.elapsedNow()}")
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
