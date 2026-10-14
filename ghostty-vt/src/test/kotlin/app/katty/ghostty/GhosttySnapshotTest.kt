package app.katty.ghostty

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GhosttySnapshotTest {
    private val terminal = GhosttyTerminal(cols = 20, rows = 5, scrollbackMaxBytes = 50_000_000)
    private val restored = mutableListOf<GhosttyTerminal>()
    private val esc = "\u001b"
    private val bel = "\u0007"

    @AfterTest
    fun close() {
        restored.forEach { it.close() }
        terminal.close()
    }

    private fun restore(snapshot: ByteArray = terminal.snapshot(), cursorBlink: Boolean = false): GhosttyTerminal =
        GhosttyTerminal.restore(snapshot, defaultCursorBlink = cursorBlink).also { restored += it }

    // The cells of every row that a render state reads, which is what is shown.
    private fun rows(of: GhosttyTerminal): List<List<GhosttyRenderCell>> = GhosttyRenderState().use { state ->
        state.update(of)
        state.readRows().map { it.cells!! }
    }

    private fun lines(count: Int) = repeat(count) { terminal.vtWrite("line $it\r\n") }

    @Test
    fun theTextTheHistoryAndTheCursorComeBack() {
        lines(100)
        terminal.vtWrite("a prompt> ")

        val copy = restore()

        assertEquals(terminal.totalRows, copy.totalRows)
        assertEquals(terminal.readRows(0, terminal.totalRows - 1), copy.readRows(0, copy.totalRows - 1))
        assertEquals(rows(terminal), rows(copy), "What is shown")
        GhosttyRenderState().use { a ->
            GhosttyRenderState().use { b ->
                a.update(terminal)
                b.update(copy)
                assertEquals(a.cursor, b.cursor)
            }
        }
    }

    @Test
    fun theSizeIsTheSnapshotsAndTheViewportIsAtTheBottom() {
        lines(100)
        terminal.scroll(GhosttyScrollViewport.Top)

        val copy = restore()

        assertEquals(20, copy.cols)
        assertEquals(5, copy.rows)
        val position = copy.scrollbar
        assertEquals(position.total, position.offset + position.len, "At the bottom: the viewport is not saved")
    }

    @Test
    fun theTitleAndTheDirectoryComeBack() {
        terminal.vtWrite("$esc]2;a title$bel$esc]7;file://host/some/dir$bel")

        val copy = restore()

        assertEquals("a title", copy.title)
        assertEquals("file://host/some/dir", copy.pwd)
    }

    @Test
    fun theModesComeBack() {
        terminal.vtWrite("$esc[?2004h$esc[?1000h$esc[?1h")

        val copy = restore()

        assertTrue(copy.isModeEnabled(GhosttyMode.BRACKETED_PASTE))
        assertTrue(copy.mouseTracking)
        assertTrue(copy.isModeEnabled(GhosttyMode.CURSOR_KEYS))
    }

    @Test
    fun theAlternateScreenAndItsContentComeBack() {
        lines(20)
        terminal.vtWrite("$esc[?1049h${esc}[Halt screen text")

        val copy = restore()

        assertEquals(GhosttyScreen.ALTERNATE, copy.activeScreen)
        assertEquals("alt screen text", copy.readRows(0, 0))
        copy.vtWrite("$esc[?1049l")
        terminal.vtWrite("$esc[?1049l")
        assertEquals(terminal.totalRows, copy.totalRows, "And the primary screen is under it")
        assertEquals(terminal.readRows(0, terminal.totalRows - 1), copy.readRows(0, copy.totalRows - 1))
    }

    @Test
    fun theColorsComeBack() {
        terminal.setDefaultColors(GhosttyRgb(0xEEEEEE), GhosttyRgb(0x112233), null, null)
        terminal.vtWrite("$esc]4;1;rgb:ab/cd/ef$bel$esc[31mred$esc[0m")

        val copy = restore()

        GhosttyRenderState().use { a ->
            GhosttyRenderState().use { b ->
                a.update(terminal)
                b.update(copy)
                assertEquals(a.colors, b.colors)
                assertEquals(GhosttyRgb(0x112233), b.colors.background)
            }
        }
        assertEquals(rows(terminal), rows(copy), "The red is the one that the program set")
    }

    @Test
    fun stylesWideCharactersAndEmojiComeBack() {
        terminal.vtWrite("日本語 $esc[1;4;31mbold$esc[0m 😀 é $esc[7mrev$esc[0m\r\n")

        assertEquals(rows(terminal), rows(restore()))
    }

    @Test
    fun hyperlinksComeBack() {
        terminal.vtWrite("$esc]8;;https://example.com$esc\\link$esc]8;;$esc\\ plain")

        val copy = restore()

        assertEquals("https://example.com", copy.hyperlinkAt(1, 0))
        assertEquals(null, copy.hyperlinkAt(6, 0))
    }

    @Test
    fun thePromptMarksComeBackSoThatTheViewportCanJumpToThem() {
        repeat(30) { terminal.vtWrite("$esc]133;A$bel$ command $it\r\noutput of $it\r\n$esc]133;C$bel") }

        val copy = restore()
        val bottom = copy.scrollbar.offset
        copy.scroll(GhosttyScrollViewport.DeltaPrompt(-1))

        assertTrue(copy.scrollbar.offset < bottom, "The viewport jumped to a prompt")
    }

    @Test
    fun anEscapeSequenceThatIsNotFinishedIsKeptAndFinishesInTheCopy() {
        terminal.vtWrite("x$esc[3")

        val copy = restore()
        copy.vtWrite("1mR")

        // The red is the copy's: the sequence that was begun was finished there.
        assertEquals("xR", copy.readRows(0, 0))
        val cells = rows(copy)[0]
        assertTrue(cells[1].style != GhosttyCellStyle.NONE || cells[1].foreground != null, "R is red")
    }

    @Test
    fun theWrapperIsSetUpAsForANewTerminal() {
        val copy = restore()
        val replies = StringBuilder()
        copy.onWritePty = { replies.append(it.toString(Charsets.UTF_8)) }
        val titles = mutableListOf<String>()
        copy.onTitleChanged = { titles += copy.title }

        copy.vtWrite("$esc[5n")
        copy.vtWrite("$esc]2;after$bel")
        copy.resize(20, 5, 9, 19)
        copy.vtWrite("$esc[16t")

        assertEquals("${esc}[0n${esc}[6;19;9t", replies.toString(), "The status report, and the size of a cell")
        assertEquals(listOf("after"), titles)
    }

    @Test
    fun theOptionsThatAreGivenWinOverWhatWasSaved() {
        GhosttyTerminal(cols = 20, rows = 5, defaultCursorBlink = true).use { blinking ->
            val copy = GhosttyTerminal.restore(blinking.snapshot(), defaultCursorBlink = false).also { restored += it }

            copy.vtWrite("$esc[0 q")

            GhosttyRenderState().use { state ->
                state.update(copy)
                assertFalse(state.cursor.blinking)
            }
        }
    }

    @Test
    fun theHistoryIsKeptCompressedAsItIsRestored() {
        GhosttyTerminal(cols = 80, rows = 24, scrollbackMaxBytes = 100_000_000).use { big ->
            repeat(60_000) { big.vtWrite("line number $it with some text to take room\r\n") }
            val before = big.memoryUsage()
            val text = big.readRows(0, 59_999)

            val copy = GhosttyTerminal.restore(big.snapshot()).also { restored += it }

            val usage = copy.memoryUsage()
            if (usage.compressionSupported) {
                assertTrue(usage.compressedBytes > 0, "Compressed: $usage")
                assertTrue(usage.residentBytes < before.residentBytes / 2, "${before.residentBytes} then ${usage.residentBytes}")
            }
            assertEquals(text, copy.readRows(0, 59_999))
        }
    }

    @Test
    fun theImagesOfTheKittyProtocolAreNotSaved() {
        GhosttyTerminal(cols = 20, rows = 10, imageStorageLimitBytes = 10_000_000).use { withImage ->
            withImage.resize(20, 10, 10, 20)
            withImage.vtWrite("\u001b_Ga=T,f=32,s=2,v=2,i=1;/////////////////////w==\u001b\\")
            assertEquals(1, withImage.kittyPlacements().size)

            val copy = GhosttyTerminal.restore(withImage.snapshot(), imageStorageLimitBytes = 10_000_000).also { restored += it }

            assertEquals(emptyList(), copy.kittyPlacements())
        }
    }

    @Test
    fun aRestoredTerminalIsResizedAndReflowedAsAnyOther() {
        terminal.vtWrite("abcdefghijklmnopqrstuvwxyz0123\r\n")
        val copy = restore()

        copy.resize(10, 5, 10, 20)

        assertEquals("abcdefghijklmnopqrstuvwxyz0123", copy.readRows(0, copy.totalRows - 2).lines().first { it.isNotEmpty() }.let { copy.allText().lines().first { line -> line.isNotEmpty() } })
    }

    @Test
    fun whatIsNotASnapshotIsRefused() {
        lines(30)
        val good = terminal.snapshot()

        assertFailsWith<GhosttySnapshotException> { GhosttyTerminal.restore(ByteArray(0)) }
        assertFailsWith<GhosttySnapshotException> { GhosttyTerminal.restore("not a snapshot at all, just text".toByteArray()) }
        assertFailsWith<GhosttySnapshotException> { GhosttyTerminal.restore(good.copyOf(good.size / 2)) }
        assertFailsWith<GhosttySnapshotException> { GhosttyTerminal.restore(good.copyOf(7)) }
    }

    @Test
    fun aSnapshotThatWasDamagedIsRefused() {
        lines(30)
        val good = terminal.snapshot()
        for (position in listOf(good.size / 3, good.size / 2, good.size - 5)) {
            val damaged = good.copyOf().also { it[position] = (it[position].toInt() xor 0x5A).toByte() }

            assertFailsWith<GhosttySnapshotException>("A byte changed at $position") { GhosttyTerminal.restore(damaged) }
        }
    }

    @Test
    fun refusingManySnapshotsLeavesNothingBehind() {
        val good = terminal.snapshot()
        repeat(2000) { GhosttyTerminal.runCatching { restore(good.copyOf(good.size / 2)) } }
        restore(good)
    }

    @Test
    fun theLibraryHasAnIdentity() {
        val fingerprint = GhosttyBuildInfo.fingerprint()

        assertEquals(64, fingerprint.length)
        assertTrue(fingerprint.all { it in "0123456789abcdef" })
        assertEquals(fingerprint, GhosttyBuildInfo.fingerprint())
    }

    @Test
    fun theSnapshotsOfTwoTerminalsThatAreTheSameAreTheSame() {
        lines(20)
        val first = terminal.snapshot()

        assertContentEquals(first, terminal.snapshot(), "A snapshot changes nothing")
    }
}
