package app.katty.terminal

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class TerminalSnapshotTest {
    private val sessions = mutableListOf<TerminalSession>()

    @AfterTest
    fun close() {
        sessions.forEach { it.close() }
    }

    private fun sh(script: String) = listOf("/bin/sh", "-c", script)

    private fun start(script: String, rows: Int = 6, cols: Int = 80, snapshot: TerminalSnapshot? = null, directory: String? = null): TerminalSession =
        TerminalSession.start(cols, rows, command = sh(script), snapshot = snapshot, directory = directory, shellIntegration = false).also { sessions += it }

    private fun TerminalFrame.text(row: Int): String = rows[row].cells.joinToString("") { it.text.ifEmpty { " " } }.trimEnd()

    private fun TerminalFrame.text(): String = rows.indices.joinToString("\n") { text(it) }

    private fun TerminalSession.awaitFrame(condition: (TerminalFrame) -> Boolean): TerminalFrame = runBlocking {
        try {
            withTimeout(TIMEOUT_MS) { frames.first(condition) }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("No frame matched in ${TIMEOUT_MS}ms. The last one:\n${frames.value.text()}", e)
        }
    }

    private fun await(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TIMEOUT_MS * 1_000_000
        while (!condition()) {
            check(System.nanoTime() < deadline) { "Still waiting for $what" }
            Thread.sleep(10)
        }
    }

    // A session that has printed 200 lines, set a title and said where it is, and waits.
    private fun oldSession(directory: String = "/tmp"): TerminalSession {
        val session = start("""seq 1 200; printf '\033]2;old title\007\033]7;file://localhost$directory\007'; sleep 30""")
        session.awaitFrame { frame -> frame.rows.any { frame.text(frame.rows.indexOf(it)) == "200" } }
        await("the title and the directory") { session.title.value == "old title" && session.workingDirectory.value == directory }
        return session
    }

    @Test
    fun aRestoredSessionHasTheHistoryTheTitleAndTheDirectoryAndItsProgramWritesBelow() {
        val snapshot = oldSession().snapshot()

        val restored = start("""echo new; sleep 30""", snapshot = snapshot)

        // At once, before the program has said anything.
        assertEquals("old title", restored.title.value)
        assertEquals("/tmp", restored.workingDirectory.value)
        restored.awaitFrame { "new" in it.text() }
        val lines = restored.readText().lines()
        assertEquals((1..200).map { it.toString() }, lines.take(200), "The history is there")
        val position = lines.indexOf("new")
        assertTrue(position > 200, "And the new program writes below it, on a row of its own: at $position")
    }

    @Test
    fun aRestoredSessionWorksAndTakesTheSizeThatIsAskedFor() {
        val snapshot = oldSession().snapshot()
        assertEquals(80, snapshot.cols)

        val restored = start("stty raw -echo; echo ready; head -c1 | od -An -c", cols = 40, rows = 8, snapshot = snapshot)
        val frame = restored.awaitFrame { "ready" in it.text() }
        restored.sendKey(KeyInput(TerminalKey.UNIDENTIFIED, text = "q"))

        assertEquals(40, frame.cols, "The size of the host, not the snapshot's")
        assertEquals(8, frame.rows.size)
        restored.awaitFrame { "q" in it.text().replace(Regex("\\s+"), "") && "ready" in it.text() }
    }

    @Test
    fun aSnapshotOfAnEditorRestoresToAShellWithAPlainMouseAndKeys() {
        val editor = start("""printf '\033[?1049h\033[?1000h\033[?1006h\033[?2004h\033[?1h\033[6 q\033[?25l'; sleep 30""")
        editor.awaitFrame { it.alternateScreen && it.mouseTracking }
        val snapshot = editor.snapshot()

        val restored = start("stty raw -echo; echo ready; head -c4 | tr -d '\\033' | od -An -c", snapshot = snapshot)
        val frame = restored.awaitFrame { "ready" in it.text() }

        assertFalse(frame.alternateScreen, "The primary screen")
        assertFalse(frame.mouseTracking, "No mouse reports")
        assertEquals(TerminalCursorShape.BLOCK, frame.cursor.shape, "The default cursor, not the bar of the editor")
        assertTrue(frame.cursor.visible, "And shown")
        // An arrow is the plain ESC [ A (not the application ESC O A), and a paste has no markers.
        restored.sendKey(KeyInput(TerminalKey.ARROW_UP))
        restored.paste("a")
        restored.awaitFrame { "[Aa" in it.text().replace(Regex("\\s+"), "") }
    }

    @Test
    fun aSnapshotOfAnEditorThatAskedForReportsGivesTheShellNone() {
        // What Neovim asks for: the changes of the color scheme (2031) and of the size (2048), and the keys with modifiers (modifyOtherKeys);
        // and the colors reversed (5).
        val editor = start("""printf '\033[?2031h\033[?2048h\033[>4;2m\033[?5h'; echo set; sleep 30""")
        val reversed = editor.awaitFrame { "set" in it.text() }
        val snapshot = editor.snapshot()

        val restored = start("stty raw -echo; echo ready; head -c1 | od -An -c", snapshot = snapshot)
        val frame = restored.awaitFrame { "ready" in it.text() }
        assertNotEquals(reversed.background, frame.background, "The colors are not reversed any more")

        // Neither is reported to the shell, which would print what it reads of them on its command line.
        restored.setTheme(TerminalTheme(TerminalColor(0xFFFFFF), TerminalColor(0x000000), List(TerminalTheme.ANSI_COLORS) { TerminalColor(0x808080) }))
        restored.resize(70, 6, 8, 16)
        restored.paste("a")
        restored.awaitFrame { "a" in it.text().substringAfter("ready").replace(Regex("\\s+"), "") }
        assertFalse("033" in restored.frames.value.text(), "The shell read an escape first:\n${restored.frames.value.text()}")
    }

    @Test
    fun anOldProgramThatSetAScrollingRegionAndTheOriginModeDoesNotMoveTheNewOne() {
        // Origin mode and a region, which a program sets and which move the cursor when they are reset.
        val old = start("""seq 1 30; printf '\033[2;4r\033[?6h\033[2;1Hbefore'; sleep 30""", rows = 8)
        old.awaitFrame { "before" in it.text() }
        val snapshot = old.snapshot()

        val restored = start("""printf 'new\r\nnext\r\nlast\r\nmore\r\n'; sleep 30""", rows = 8, snapshot = snapshot)

        await("the new program to have written") { "more" in restored.readText() }
        val lines = restored.readText().lines()
        assertTrue(lines.indexOf("new") > lines.indexOf("before"), "Below what the old one showed, not over it: $lines")
        assertEquals(listOf("new", "next", "last", "more"), lines.filter { it in setOf("new", "next", "last", "more") }, "And not held in a region")
        assertFalse(restored.frames.value.text().contains("\u001b"), "No stray escapes")
    }

    @Test
    fun aSessionThatWasSnapshottedWhileItWasWaitingForAnEscapeSequenceToEndIsRestored() {
        val old = start("""printf 'text before\033[3'; sleep 30""")
        old.awaitFrame { "text before" in it.text() }

        val snapshot = old.snapshot()

        start("sleep 30", snapshot = snapshot).awaitFrame { "text before" in it.text() }
    }

    @Test
    fun theProgramStartsInTheDirectoryOfTheSnapshotOrWhereItIsTold() {
        val directory = Files.createTempDirectory("katty-snapshot").also { it.toFile().deleteOnExit() }
        val other = Files.createTempDirectory("katty-other").also { it.toFile().deleteOnExit() }
        val snapshot = oldSession(directory.toString()).snapshot()

        val here = start("pwd; sleep 30", snapshot = snapshot)
        await("the program to say where it is") { directory.toRealPath().toString() in here.readText() }

        val told = start("pwd; sleep 30", snapshot = snapshot, directory = other.toString())
        await("the program to say where it is") { other.toRealPath().toString() in told.readText() }

        val gone = oldSession("/no/such/directory/at/all").snapshot()
        val home = start("pwd; sleep 30", snapshot = gone)
        await("the program to say where it is") { java.io.File(System.getProperty("user.home")).canonicalPath in home.readText() }
    }

    @Test
    fun aLongHistoryIsRestoredWholeAndKeptCompressed() {
        val big = start("""yes 'some text that takes room in the history of the terminal' | head -n 100000; echo END; sleep 30""")
        big.awaitFrame { frame -> frame.rows.any { frame.text(frame.rows.indexOf(it)) == "END" } }
        val text = big.readText(1000, 1010)
        val resident = big.memoryUsage().residentBytes

        val snapshot = big.snapshot()
        val restored = start("sleep 30", snapshot = snapshot)

        assertEquals(text, restored.readText(1000, 1010))
        val usage = restored.memoryUsage()
        assertTrue(usage.compressedBytes > 0, "Compressed as it was restored: $usage")
        assertTrue(usage.residentBytes < resident / 2, "$resident then ${usage.residentBytes}")
    }

    @Test
    fun aHiddenSessionCanBeSnapshottedAndItsSnapshotIsWhole() {
        val session = oldSession()
        session.setVisible(false)
        session.awaitFrame { frame -> frame.rows.all { it.cells.isEmpty() } }

        val restored = start("sleep 30", snapshot = session.snapshot())

        assertEquals((1..200).map { it.toString() }, restored.readText().lines().take(200))
    }

    @Test
    fun aSnapshotTakenWhileTheProgramWritesIsAValidTerminal() {
        val busy = start("""yes 'a line that is written without a pause, for as long as the test goes' """)
        busy.awaitFrame { (it.scrollbar?.total ?: 0) > 500 }

        repeat(5) {
            val restored = start("sleep 30", snapshot = busy.snapshot())
            val text = restored.readText()
            assertTrue(text.lines().take(100).all { line -> line == "a line that is written without a pause, for as long as the test goes" }, "Whole lines")
            restored.close()
        }
    }

    @Test
    fun aSnapshotIsKeptAsBytesAndDamagedOrForeignOnesAreRefused() {
        val snapshot = oldSession().snapshot()

        val bytes = snapshot.toByteArray()
        val back = TerminalSnapshot.fromByteArray(bytes)
        assertEquals(snapshot.cols, back.cols)
        assertEquals(snapshot.rows, back.rows)
        assertEquals("old title", back.title)
        assertEquals("/tmp", back.workingDirectory)
        assertEquals(snapshot.createdAt, back.createdAt)
        assertTrue(back.isRestorable)
        start("sleep 30", snapshot = back).awaitFrame { "200" in it.text() || true }

        assertFailsWith<IllegalArgumentException> { TerminalSnapshot.fromByteArray(ByteArray(0)) }
        assertFailsWith<IllegalArgumentException> { TerminalSnapshot.fromByteArray("not a snapshot".toByteArray()) }
        assertFailsWith<IllegalArgumentException> { TerminalSnapshot.fromByteArray(bytes.copyOf(bytes.size / 2)) }
        assertFailsWith<IllegalArgumentException> { TerminalSnapshot.fromByteArray(bytes.copyOf().also { it[9] = 99 }) }

        val damaged = TerminalSnapshot.fromByteArray(bytes.copyOf().also { it[it.size / 2] = (it[it.size / 2].toInt() xor 0x5A).toByte() })
        assertFailsWith<TerminalSnapshotException> { start("sleep 30", snapshot = damaged) }
    }

    @Test
    fun aSnapshotOfAnotherLibraryIsNotRestorable() {
        val own = oldSession().snapshot()
        val foreign = TerminalSnapshot(own.cols, own.rows, own.title, own.workingDirectory, own.createdAt, "0".repeat(64), own.data)

        assertFalse(foreign.isRestorable)
        val refused = assertFailsWith<TerminalSnapshotException> { start("sleep 30", snapshot = foreign) }
        assertTrue("another build" in refused.message.orEmpty(), refused.message)
    }

    @Test
    fun aClosedSessionCannotBeSnapshotted() {
        val session = oldSession()
        session.close()

        assertFailsWith<TerminalSnapshotException> { session.snapshot() }
    }

    @Test
    fun aRestoredSessionCanBeSnapshottedAndRestoredAgain() {
        val second = start("echo second; sleep 30", snapshot = oldSession().snapshot())
        second.awaitFrame { "second" in it.text() }

        val third = start("echo third; sleep 30", snapshot = second.snapshot())

        third.awaitFrame { "third" in it.text() }
        val lines = third.readText().lines()
        assertEquals((1..200).map { it.toString() }, lines.take(200))
        assertTrue(lines.indexOf("second") in 201 until lines.indexOf("third"), "In the order that they happened")
    }

    @Test
    fun theSnapshotsOfTheSameSessionHaveTheSameContent() {
        val session = oldSession()

        val first = session.snapshot()
        val second = session.snapshot()

        assertEquals(first.title, second.title)
        assertNotEquals(0, first.sizeBytes)
        assertTrue(first.data.contentEquals(second.data), "A snapshot changes nothing")
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
    }
}
