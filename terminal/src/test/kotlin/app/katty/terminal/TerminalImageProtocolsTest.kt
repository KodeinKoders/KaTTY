package app.katty.terminal

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.awt.image.BufferedImage
import java.io.File
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TerminalImageProtocolsTest {
    private val sessions = mutableListOf<TerminalSession>()

    @AfterTest
    fun close() {
        sessions.forEach { it.close() }
    }

    private fun start(script: String, rows: Int = 5, advertiseSixel: Boolean = true): TerminalSession =
        TerminalSession.start(80, rows, command = listOf("/bin/sh", "-c", script), shellIntegration = false, advertiseSixel = advertiseSixel)
            .also { sessions += it }

    private fun TerminalFrame.text(row: Int): String = rows[row].cells.joinToString("") { it.text.ifEmpty { " " } }.trimEnd()

    private fun TerminalFrame.text(): String = rows.indices.joinToString("\n") { text(it) }

    private fun TerminalSession.awaitFrame(condition: (TerminalFrame) -> Boolean): TerminalFrame = runBlocking {
        try {
            withTimeout(TIMEOUT_MS) { frames.first(condition) }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("No frame matched in ${TIMEOUT_MS}ms. The last one:\n${frames.value.text()}", e)
        }
    }

    // A 16 by 12 sixel image: two bands of green.
    private val sixel = """\033P0;1;0q"1;1;16;12#1;2;0;100;0!16~-!16~\033\\"""

    private fun pngFile(width: Int = 4, height: Int = 2): File {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until height) for (x in 0 until width) image.setRGB(x, y, if (x < width / 2) 0xFF0000 else 0x0000FF)
        return File.createTempFile("katty-image", ".png").also {
            it.deleteOnExit()
            ImageIO.write(image, "png", it)
        }
    }

    @Test
    fun aSixelImageIsShownWhereTheCursorWasAndTheCursorGoesToTheNextLine() {
        val session = start("""printf 'ab'; printf '$sixel'; printf 'X'; sleep 30""")

        val frame = session.awaitFrame { it.images.isNotEmpty() && "X" in it.text() }

        val image = frame.images.single()
        assertEquals(2f, image.left, "After ab")
        assertEquals(0f, image.top)
        // 16 by 12 pixels, on cells of 8 by 16.
        assertEquals(2f, image.width)
        assertEquals(0.75f, image.height)
        assertEquals(16, image.image.width)
        assertEquals(12, image.image.height)
        assertEquals("ab", frame.text(0))
        assertEquals("X", frame.text(1), "The cursor is at the start of the line after the image")
    }

    @Test
    fun anItermImageIsShownAndTheCursorIsAfterIt() {
        val file = pngFile()
        val base64 = File.createTempFile("katty-image", ".b64").also {
            it.deleteOnExit()
            it.writeText(Base64.getEncoder().encodeToString(file.readBytes()))
        }
        val session = start("""printf 'ab'; printf '\033]1337;File=inline=1:'; cat ${base64.path}; printf '\007X'; sleep 30""")

        val frame = session.awaitFrame { it.images.isNotEmpty() && "X" in it.text() }

        val image = frame.images.single()
        assertEquals(2f, image.left)
        assertEquals(0f, image.top)
        assertEquals(4, image.image.width)
        assertEquals("ab X", frame.text(0), "The image takes the cell at the column 2, and the cursor is after it")
    }

    @Test
    fun anImageScrollsWithTheText() {
        // 36 pixels high: three rows of 16.
        val tall = """\033P0;1;0q"1;1;16;36#1;2;0;100;0""" + "!16~-".repeat(6) + """\033\\"""
        val session = start("""printf '$tall'; seq 1 3; sleep 30""", rows = 6)

        val frame = session.awaitFrame { frame -> frame.images.isNotEmpty() && frame.rows.indices.any { frame.text(it) == "3" } }

        // It took the first three rows, the three lines went under it, and the screen scrolled by one row.
        assertEquals(-1f, frame.images.single().top)
        assertEquals(2.25f, frame.images.single().height)
    }

    @Test
    fun clearingTheScreenRemovesTheImages() {
        val session = start("""stty raw -echo; printf '$sixel'; echo ready; head -c1 >/dev/null; printf '\033[2J\033[3J\033[H'; sleep 30""")
        session.awaitFrame { it.images.isNotEmpty() && "ready" in it.text() }

        session.paste("x")

        session.awaitFrame { it.images.isEmpty() }
    }

    @Test
    fun theDeviceAttributesSaySixelWhenItIsDrawn() {
        val session = start("""stty raw -echo; printf '\033[c'; head -c11 | tr -d '\033' | od -An -c""")

        session.awaitFrame { "[?62;4;22c" in it.text().replace(Regex("\\s+"), "") }
    }

    @Test
    fun theDeviceAttributesDoNotSaySixelWhenItIsNotAdvertised() {
        val session = start("""stty raw -echo; printf '\033[c'; head -c9 | tr -d '\033' | od -An -c""", advertiseSixel = false)

        session.awaitFrame { "[?62;22c" in it.text().replace(Regex("\\s+"), "") }
    }

    private fun chafa(): String? = listOf("/opt/homebrew/bin/chafa", "/usr/local/bin/chafa", "/usr/bin/chafa").firstOrNull { File(it).canExecute() }

    @Test
    fun chafaWithTheFormatSixelDrawsAnImage() {
        val chafa = chafa() ?: return
        val session = start("""$chafa -f sixel --probe off -s 10x4 ${pngFile(40, 40).path}; echo END; sleep 30""", rows = 8)

        val frame = session.awaitFrame { it.images.isNotEmpty() && it.rows.indices.any { row -> it.text(row) == "END" } }

        assertEquals(1, frame.images.size)
    }

    @Test
    fun chafaWithTheFormatItermDrawsAnImage() {
        val chafa = chafa() ?: return
        val session = start("""$chafa -f iterm --probe off -s 10x4 ${pngFile(40, 40).path}; echo END; sleep 30""", rows = 8)

        val frame = session.awaitFrame { it.images.isNotEmpty() && it.rows.indices.any { row -> it.text(row) == "END" } }

        assertEquals(1, frame.images.size)
    }

    @Test
    fun aPlainChafaDrawsAnImageNowThatTheTerminalAnswersWhatItProbes() {
        val chafa = chafa() ?: return
        val session = start("""$chafa -s 10x4 ${pngFile(40, 40).path}; echo END; sleep 30""", rows = 8)

        val frame = session.awaitFrame { it.rows.indices.any { row -> it.text(row) == "END" } }

        assertTrue(frame.images.isNotEmpty(), "A real image, not characters: ${frame.text()}")
    }

    private companion object {
        const val TIMEOUT_MS = 15_000L
    }
}
