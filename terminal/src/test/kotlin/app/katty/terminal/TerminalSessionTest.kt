package app.katty.terminal

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

class TerminalSessionTest {
    private val sessions = mutableListOf<TerminalSession>()

    private val collectors = mutableListOf<Job>()

    @AfterTest
    fun close() {
        collectors.forEach { it.cancel() }
        sessions.forEach { it.close() }
    }

    // A shell script instead of the login shell, so that what the program does is known.
    private fun start(script: String, cols: Int = 80, rows: Int = 5): TerminalSession =
        TerminalSession.start(cols, rows, command = listOf("/bin/sh", "-c", script)).also { sessions += it }

    private fun TerminalFrame.text(row: Int): String =
        rows[row].cells.joinToString("") { it.text.ifEmpty { " " } }.trimEnd()

    private fun TerminalFrame.text(): String = rows.indices.joinToString("\n") { text(it) }

    private fun TerminalSession.awaitFrame(condition: (TerminalFrame) -> Boolean): TerminalFrame = runBlocking {
        try {
            withTimeout(TIMEOUT_MS) { frames.first(condition) }
        } catch (e: TimeoutCancellationException) {
            throw AssertionError("No frame matched in ${TIMEOUT_MS}ms. The last one:\n${frames.value.text()}", e)
        }
    }

    private fun TerminalSession.awaitExit(): Int = runBlocking {
        withTimeout(TIMEOUT_MS) { exited.filterNotNull().first() }
    }

    @Test
    fun publishesWhatTheProgramWrites() {
        val session = start("printf hello")

        val frame = session.awaitFrame { it.text(0) == "hello" }

        assertEquals(80, frame.cols)
        assertEquals(5, frame.rows.size)
        assertEquals(0, session.awaitExit())
    }

    @Test
    fun runsTheProgramInATerminalThatSupportsColors() {
        val session = start("""printf 'a\033[1;31mb\033[0m'; echo; echo "${'$'}TERM ${'$'}COLORTERM"; tput colors""")

        val frame = session.awaitFrame { it.text(2) == "256" }

        assertEquals("xterm-256color truecolor", frame.text(1).trimEnd())
        val (plain, styled) = frame.rows[0].cells
        assertEquals(TerminalStyle.NONE, plain.style)
        assertTrue(styled.style.bold)
        assertNotNull(styled.foreground)
    }

    @Test
    fun reflectsAResize() {
        val session = start("sleep 30")

        session.resize(cols = 40, rows = 10, cellWidthPx = 8, cellHeightPx = 16)

        val frame = session.awaitFrame { it.cols == 40 && it.rows.size == 10 }
        assertEquals(10, frame.rows.size)
    }

    @Test
    fun tellsTheProgramTheSizeOfItsTerminal() {
        val session = start("""sleep 0.5; stty size""")

        // The size is changed while the program sleeps: it is the new one that it reads.
        session.resize(cols = 33, rows = 7, cellWidthPx = 8, cellHeightPx = 16)

        session.awaitFrame { frame -> frame.rows.indices.any { frame.text(it) == "7 33" } }
    }

    @Test
    fun tellsTheProgramTheSizeOfItsCellsInPixelsInTheSizeOfThePty() {
        // The program reads the size of its terminal as TIOCGWINSZ gives it (rows, columns, width and height in
        // pixels) once it was resized: a byte of input tells it to.
        val session = start(
            """stty raw -echo; echo ready; head -c1 >/dev/null; perl -e '${'$'}ws = "\0" x 8; ioctl(STDIN, 0x40087468, ${'$'}ws); print join(" ", unpack("S4", ${'$'}ws)), "\n"'"""
        )
        session.awaitFrame { "ready" in it.text() }

        session.resize(cols = 33, rows = 7, cellWidthPx = 9, cellHeightPx = 19)
        session.paste("x")

        // 33 columns of 9 pixels, 7 rows of 19.
        session.awaitFrame { "7 33 297 133" in it.text() }
    }

    @Test
    fun answersTheProgramWhenItAsksTheSizeOfACell() {
        val session = start("""stty raw -echo; echo ready; head -c1 >/dev/null; printf '\033[16t'; head -c9 | tr -d '\033' | od -An -c""")
        session.awaitFrame { "ready" in it.text() }

        session.resize(cols = 33, rows = 7, cellWidthPx = 9, cellHeightPx = 19)
        session.paste("x")

        // CSI 6 ; height ; width t
        session.awaitFrame { "[6;19;9t" in it.text().replace(Regex("\\s+"), "") }
    }

    @Test
    fun sendsTextToTheProgramAsItIs() {
        val session = start("stty raw -echo; echo ready; head -c3 | od -An -tx1")
        session.awaitFrame { "ready" in it.text() }

        session.sendText("\u001bb\u0001")

        session.awaitFrame { "1b 62 01" in it.text().replace(Regex("\\s+"), " ") }
    }

    @Test
    fun sendsEnterAsACarriageReturn() {
        // In raw mode, the program gets exactly the bytes that are sent. "ready" means it is in raw mode.
        val session = start("stty raw -echo; echo ready; head -c1 | od -An -tx1")
        session.awaitFrame { "ready" in it.text() }

        session.sendKey(KeyInput(TerminalKey.ENTER))

        session.awaitFrame { "0d" in it.text() }
    }

    @Test
    fun encodesKeysAccordingToTheModesTheProgramSet() {
        // The program asks for the Kitty keyboard protocol (CSI > 1 u) before it reads.
        val session = start("""printf '\033[>1u'; stty raw -echo; echo ready; head -c7 | od -An -tx1""")
        session.awaitFrame { "ready" in it.text() }

        session.sendKey(KeyInput(TerminalKey.ENTER, mods = setOf(TerminalModifier.SHIFT)))

        // ESC [ 1 3 ; 2 u, which od separates with more than a space.
        session.awaitFrame { "1b 5b 31 33 3b 32 75" in it.text().replace(Regex("\\s+"), " ") }
    }

    @Test
    fun keepsTheRowsThatDidNotChange() {
        val session = start("printf one; sleep 0.5; printf '\\r\\ntwo'; sleep 30")

        val first = session.awaitFrame { it.text(0) == "one" && it.text(1).isEmpty() }
        val second = session.awaitFrame { it.text(1) == "two" }

        assertSame(first.rows[0], second.rows[0], "A row that did not change is the same object")
        assertEquals(first.rows[0].id, second.rows[0].id)
    }

    @Test
    fun limitsTheFrameRateWhenTheOutputFloods() {
        val started = TimeSource.Monotonic.markNow()
        val session = start("seq 1 300000")

        var published = 0
        val last = runBlocking {
            withTimeout(TIMEOUT_MS) { session.frames.onEach { published++ }.first { "300000" in it.text() } }
        }
        val elapsed = started.elapsedNow()

        // The last output is published, not merged away by the frames that came before it.
        assertTrue("300000" in last.text())
        // An upper bound that does not depend on the speed of the machine: a frame is produced at most
        // every 1/60 s, and the one of the start of the session comes on top.
        val bound = (elapsed / (1.seconds / 60)).toInt() + 2
        assertTrue(published <= bound, "$published frames in $elapsed, at most $bound are allowed")
    }

    private val theme = TerminalTheme(
        background = TerminalColor(0x102030),
        foreground = TerminalColor(0xE0D0C0),
        ansi = List(16) { TerminalColor(0x203040 + it * 0x050709) },
        cursor = TerminalColor(0xFF8800),
        cursorText = TerminalColor(0x001122),
        bold = TerminalColor(0xFFFFFF),
    )

    @Test
    fun startsWithTheColorsOfTheTheme() {
        val session = TerminalSession.start(
            80, 5, command = listOf("/bin/sh", "-c", """printf 'a\033[31mb\033[44mc'; sleep 30"""), theme = theme,
        ).also { sessions += it }

        val frame = session.awaitFrame { it.text(0) == "abc" }

        assertEquals(theme.background, frame.background)
        assertEquals(theme.foreground, frame.foreground)
        assertEquals(theme.cursor, frame.cursorColor)
        assertEquals(theme.cursorText, frame.cursorTextColor)
        assertEquals(theme.bold, frame.boldColor)
        val cells = frame.rows[0].cells
        assertEquals(theme.ansi[1], cells[1].foreground, "SGR 31 is the theme's red")
        assertEquals(theme.ansi[4], cells[2].background, "SGR 44 is the theme's blue")
    }

    @Test
    fun changesTheColorsOfWhatIsOnTheScreen() {
        val session = start("""printf '\033[31mx\033[0m'; sleep 30""")
        val before = session.awaitFrame { it.text(0) == "x" }
        assertNotEquals(theme.ansi[1], before.rows[0].cells[0].foreground)

        session.setTheme(theme)

        val after = session.awaitFrame { it.rows[0].cells[0].foreground == theme.ansi[1] }
        assertEquals(theme.background, after.background)
        assertEquals(theme.cursorText, after.cursorTextColor)
    }

    @Test
    fun goesBackToTheDefaultColorsWithoutATheme() {
        val session = start("""printf '\033[31mx\033[0m'; sleep 30""")
        val before = session.awaitFrame { it.text(0) == "x" }
        session.setTheme(theme)
        session.awaitFrame { it.background == theme.background }

        session.setTheme(null)

        val after = session.awaitFrame { it.background == before.background }
        assertEquals(before.rows[0].cells[0].foreground, after.rows[0].cells[0].foreground)
        assertEquals(null, after.cursorTextColor)
        assertEquals(null, after.boldColor)
    }

    @Test
    fun aProgramsColorsWinOverTheTheme() {
        val session = start("""printf '\033]11;rgb:ff/00/00\033\\'; sleep 30""")
        session.setTheme(theme)

        // Whichever of the two comes first, the program's background is the one that is used, and the theme's
        // foreground, which the program did not change.
        session.awaitFrame { it.background == TerminalColor(0xFF0000) && it.foreground == theme.foreground }
    }

    // Every frame that is published, which a collector that is slow would not see: it runs where the producer does.
    private fun TerminalSession.recordFrames(): Pair<List<TerminalFrame>, Job> {
        val seen = CopyOnWriteArrayList<TerminalFrame>()
        val job = CoroutineScope(Dispatchers.Unconfined).launch { frames.collect { seen += it } }
        return seen to job
    }

    @Test
    fun showsARedrawThatTakesSeveralWritesWhole() {
        // What zsh does when the selection moves in its completion menu: the cursor goes down to the menu,
        // the entries are drawn, and the cursor goes back up to the line, in writes that are about half a
        // millisecond apart. A frame of the middle of it would show the cursor in the menu. It is done many
        // times, with pauses: whether the middle is caught is a matter of timing.
        val script = """perl -e '${'$'}| = 1; print "prompt> ab"; for (1..40) { select(undef, undef, undef, 0.025); """ +
            """print "\r\n"; select(undef, undef, undef, 0.001); print "\e[A\e[11C"; } print "done"; select(undef, undef, undef, 30)'"""
        val session = start(script)
        val (seen, job) = session.recordFrames()

        try {
            session.awaitFrame { "done" in it.text(0) }
        } finally {
            job.cancel()
        }

        assertTrue(seen.any { it.text(0) == "prompt> ab" }, "The prompt was shown")
        // Without waiting for the output to pause, about a quarter of the redraws are caught in the middle. The
        // two that are allowed are for the program being preempted for longer than the wait, on a busy machine.
        val inTheMiddle = seen.count { it.cursor.y == 1 }
        assertTrue(inTheMiddle <= 2, "The cursor was shown in the middle of the redraw in $inTheMiddle of the 40 redraws")
    }

    @Test
    fun stillPublishesWhileTheProgramKeepsWriting() {
        // Waiting for the output to pause must not starve the screen of a program that never does.
        val session = start("""perl -e '${'$'}| = 1; for (1..300) { print "x"; select(undef, undef, undef, 0.001) } select(undef, undef, undef, 30)'""")
        val (seen, job) = session.recordFrames()

        try {
            session.awaitFrame { frame -> frame.text().count { it == 'x' } == 300 }
        } finally {
            job.cancel()
        }

        assertTrue(seen.size >= 8, "Only ${seen.size} frames were published while the program wrote for 300ms")
    }

    // A program in raw mode that asks for the mouse, then prints the bytes that it reads. The escape is removed
    // so that what is left is text: the bytes of a report after it.
    private fun mouseSession(modes: String, bytes: Int): TerminalSession {
        val session = start("""printf '$modes'; stty raw -echo; echo ready; head -c$bytes | tr -d '\033' | od -An -c""", rows = 10)
        session.resize(80, 10, 10, 20)
        session.awaitFrame { "ready" in it.text() }
        return session
    }

    private fun TerminalSession.awaitReports(expected: String): TerminalFrame =
        awaitFrame { expected in it.text().replace(Regex("\\s+"), "") }

    private fun MouseInput(action: TerminalMouseAction, button: TerminalMouseButton?, column: Float, row: Float, held: Boolean = false) =
        MouseInput(action, button, column, row, anyButtonPressed = held)

    @Test
    fun sendsAClickToAProgramThatAskedForTheMouse() {
        val session = mouseSession("\\033[?1000h\\033[?1006h", bytes = 9)

        // The third column and the second row, in the middle of the cell: the program is told 4 and 3.
        session.sendMouse(MouseInput(TerminalMouseAction.PRESS, TerminalMouseButton.LEFT, 3.5f, 2.5f))

        session.awaitReports("[<0;4;3M")
    }

    @Test
    fun convertsPositionsWithTheSizeOfACellThatTheTerminalWasGiven() {
        val session = mouseSession("\\033[?1000h\\033[?1016h", bytes = 11)

        // In pixels, with cells of 10 by 20: 3.5 columns are 35, 2.5 rows are 50.
        session.sendMouse(MouseInput(TerminalMouseAction.PRESS, TerminalMouseButton.LEFT, 3.5f, 2.5f))

        session.awaitReports("[<0;35;50M")
    }

    @Test
    fun sendsADragAndTheWheelInOrder() {
        val session = mouseSession("\\033[?1002h\\033[?1006h", bytes = 38)

        session.sendMouse(MouseInput(TerminalMouseAction.PRESS, TerminalMouseButton.LEFT, 3.5f, 2.5f))
        session.sendMouse(MouseInput(TerminalMouseAction.MOTION, TerminalMouseButton.LEFT, 4.5f, 2.5f, held = true))
        session.sendMouse(MouseInput(TerminalMouseAction.RELEASE, TerminalMouseButton.LEFT, 4.5f, 2.5f))
        session.sendMouse(MouseInput(TerminalMouseAction.PRESS, TerminalMouseButton.WHEEL_UP, 4.5f, 2.5f))

        session.awaitReports("[<0;4;3M[<32;5;3M[<0;5;3m[<64;5;3M")
    }

    @Test
    fun reportsAPositionOutsideTheScreenAtItsEdge() {
        val session = mouseSession("\\033[?1000h\\033[?1006h", bytes = 10)

        session.sendMouse(MouseInput(TerminalMouseAction.PRESS, TerminalMouseButton.LEFT, -5f, 900f))

        // The first column, the last row of the 10.
        session.awaitReports("[<0;1;10M")
    }

    @Test
    fun sendsNothingToAProgramThatDidNotAskForTheMouse() {
        val session = start("stty raw -echo; echo ready; head -c1 | od -An -c")
        session.awaitFrame { "ready" in it.text() }

        session.sendMouse(MouseInput(TerminalMouseAction.PRESS, TerminalMouseButton.LEFT, 3.5f, 2.5f))
        session.sendKey(KeyInput(TerminalKey.ENTER))

        // The first byte that the program reads is the Enter: a report would have come before it.
        session.awaitFrame { "\\r" in it.text() }
    }

    @Test
    fun saysWhetherTheProgramAskedForTheMouse() {
        val session = start("""printf '\033[?1000h'; sleep 0.5; printf '\033[?1000l'; sleep 30""")
        assertEquals(false, session.frames.value.mouseTracking)

        session.awaitFrame { it.mouseTracking }
        session.awaitFrame { !it.mouseTracking }
    }

    @Test
    fun followsThePointerShapeThatTheProgramAskedFor() {
        val session = start("""printf '\033]22;pointer\033\\'; sleep 30""")
        assertEquals(TerminalPointerShape.TEXT, session.frames.value.pointerShape, "The text cursor, until it is told otherwise")

        session.awaitFrame { it.pointerShape == TerminalPointerShape.POINTER }
    }

    @Test
    fun theTerminalsShapesAreTheOnesOfTheBinding() {
        // They are converted by name.
        for (shape in app.katty.ghostty.GhosttyMouseShape.entries) {
            assertEquals(shape.name, shape.toTerminal().name)
        }
        assertEquals(app.katty.ghostty.GhosttyMouseShape.entries.size, TerminalPointerShape.entries.size)
    }

    // A session with "hello world" and "second line" on its screen, whose cells are 10 by 20 pixels.
    private fun selectionSession(): TerminalSession {
        val session = start("""printf 'hello world\r\nsecond line'; sleep 30""", rows = 6)
        session.resize(80, 6, 10, 20)
        session.awaitFrame { it.text(1) == "second line" }
        return session
    }

    private fun TerminalSession.select(fromColumn: Float, fromRow: Float, toColumn: Float, toRow: Float, rectangle: Boolean = false): String? {
        selectionPress(fromColumn, fromRow)
        selectionDrag(toColumn, toRow, rectangle)
        return selectionRelease(toColumn, toRow)
    }

    @Test
    fun selectsWhatIsDraggedOverAndGivesItsTextToCopy() {
        val session = selectionSession()

        // From the left half of the first cell to the right half of the fifth.
        val text = session.select(0.2f, 0.5f, 4.7f, 0.5f)

        assertEquals("hello", text)
        val frame = session.awaitFrame { it.selection.isNotEmpty() }
        assertEquals(0..4, frame.selection[0])
        assertEquals(null, frame.selection[1])
    }

    @Test
    fun aClickWithoutADragSelectsNothingAndUnselects() {
        val session = selectionSession()
        session.select(0.2f, 0.5f, 4.7f, 0.5f)
        session.awaitFrame { it.selection.isNotEmpty() }

        session.selectionPress(2.5f, 1.5f)
        val text = session.selectionRelease(2.5f, 1.5f)

        assertEquals(null, text)
        session.awaitFrame { it.selection.isEmpty() }
    }

    @Test
    fun aDoubleClickSelectsTheWordAndATripleClickTheLine() {
        val session = selectionSession()

        session.selectionPress(7.5f, 0.5f)
        session.selectionRelease(7.5f, 0.5f)
        session.selectionPress(7.5f, 0.5f)
        assertEquals("world", session.selectionRelease(7.5f, 0.5f))

        session.selectionPress(7.5f, 0.5f)
        assertEquals("hello world", session.selectionRelease(7.5f, 0.5f))
    }

    @Test
    fun aRectangleSelectsABlock() {
        val session = selectionSession()

        val text = session.select(2.2f, 0.5f, 4.7f, 1.5f, rectangle = true)

        assertEquals("llo\ncon", text)
        val frame = session.awaitFrame { it.selection.isNotEmpty() }
        assertEquals(2..4, frame.selection[0])
        assertEquals(2..4, frame.selection[1])
    }

    @Test
    fun aPositionOutsideTheScreenIsOnItsEdge() {
        val session = selectionSession()

        val text = session.select(0.2f, 0.5f, 900f, 0.5f)

        assertEquals("hello world", text)
    }

    @Test
    fun typingForTheProgramUnselects() {
        val session = selectionSession()
        session.select(0.2f, 0.5f, 4.7f, 0.5f)
        session.awaitFrame { it.selection.isNotEmpty() }

        session.sendKey(KeyInput(TerminalKey.ENTER))

        session.awaitFrame { it.selection.isEmpty() }
    }

    @Test
    fun aKeyThatSendsNothingDoesNotUnselect() {
        val session = selectionSession()
        session.select(0.2f, 0.5f, 4.7f, 0.5f)
        session.awaitFrame { it.selection.isNotEmpty() }

        // A modifier alone: the program is sent nothing.
        session.sendKey(KeyInput(TerminalKey.SHIFT_LEFT))

        assertEquals(0..4, session.frames.value.selection[0])
    }

    @Test
    fun theProgramWritingOverTheSelectionUnselectsIt() {
        val session = selectionSession()
        session.select(0.2f, 0.5f, 4.7f, 0.5f)
        session.awaitFrame { it.selection.isNotEmpty() }

        session.writeToScreen("\u001b[1;1HHELLO")

        session.awaitFrame { it.text(0) == "HELLO world" && it.selection.isEmpty() }
    }

    @Test
    fun theProgramRedrawingWhatWasThereKeepsTheSelection() {
        val session = selectionSession()
        session.select(0.2f, 0.5f, 4.7f, 0.5f)
        session.awaitFrame { it.selection.isNotEmpty() }

        // A shell does this all the time, with the cursor and the prompt.
        session.writeToScreen("\u001b[1;1Hhello world")
        session.writeToScreen("\u001b[2;1Hsecond line!")
        session.awaitFrame { it.text(1) == "second line!" }

        assertEquals(0..4, session.frames.value.selection[0])
    }

    @Test
    fun clearingUnselects() {
        val session = selectionSession()
        session.select(0.2f, 0.5f, 4.7f, 0.5f)
        session.awaitFrame { it.selection.isNotEmpty() }

        session.clearSelection()

        session.awaitFrame { it.selection.isEmpty() }
    }

    @Test
    fun theFrameHasTheColorsOfTheThemeForWhatIsSelected() {
        val themed = theme.copy(selection = TerminalColor(0x445566), selectedText = TerminalColor(0xAABBCC))

        val session = TerminalSession.start(80, 5, command = listOf("/bin/sh", "-c", "sleep 30"), theme = themed).also { sessions += it }

        val frame = session.frames.value
        assertEquals(TerminalColor(0x445566), frame.selectionColor)
        assertEquals(TerminalColor(0xAABBCC), frame.selectedTextColor)
    }

    // 200 lines in a screen of 6 rows, whose cells are 10 by 20 pixels: most of them are history.
    private fun historySession(): TerminalSession {
        val session = start("seq 1 200; sleep 30", rows = 6)
        session.resize(80, 6, 10, 20)
        session.awaitFrame { frame -> frame.rows.any { frame.text(frame.rows.indexOf(it)) == "200" } && frame.scrollbar != null }
        return session
    }

    private fun TerminalFrame.firstNumber() = text(0).toInt()

    @Test
    fun hasNoScrollbarWhenEverythingIsOnTheScreen() {
        val session = start("echo hi; sleep 30")

        assertEquals(null, session.awaitFrame { it.text(0) == "hi" }.scrollbar)
    }

    @Test
    fun scrollsThroughTheHistory() {
        val session = historySession()
        val bottom = session.frames.value
        assertTrue(bottom.scrollbar!!.atBottom)
        assertEquals(6, bottom.scrollbar!!.len)

        session.scrollBy(-10)

        val scrolled = session.awaitFrame { it.firstNumber() == bottom.firstNumber() - 10 }
        assertEquals(bottom.scrollbar!!.offset - 10, scrolled.scrollbar!!.offset)
        assertEquals(false, scrolled.scrollbar!!.atBottom)
        assertEquals(null, scrolled.cursor.x, "The cursor is on the screen, which is not in view")
    }

    @Test
    fun scrollsByAPageButARow() {
        val session = historySession()
        val bottom = session.frames.value

        session.scrollPage(-1)
        val up = session.awaitFrame { it.firstNumber() == bottom.firstNumber() - 5 }
        session.scrollPage(1)

        session.awaitFrame { it.firstNumber() == up.firstNumber() + 5 }
    }

    @Test
    fun scrollsToTheTopTheBottomAndARow() {
        val session = historySession()

        session.scrollToTop()
        val top = session.awaitFrame { it.firstNumber() == 1 }
        assertEquals(0, top.scrollbar!!.offset)

        session.scrollToRow(50)
        session.awaitFrame { it.firstNumber() == 51 }

        session.scrollToBottom()
        session.awaitFrame { it.scrollbar!!.atBottom }
    }

    @Test
    fun theViewStaysWhereItIsWhileTheProgramWrites() {
        val session = historySession()
        session.scrollBy(-20)
        val scrolled = session.awaitFrame { !it.scrollbar!!.atBottom }

        session.writeToScreen("more\r\nmore\r\nmore\r\n")
        val after = session.awaitFrame { it.scrollbar!!.total > scrolled.scrollbar!!.total }

        assertEquals(scrolled.scrollbar!!.offset, after.scrollbar!!.offset)
        assertEquals(scrolled.firstNumber(), after.firstNumber())
    }

    @Test
    fun typingBringsTheScreenBack() {
        val session = historySession()
        session.scrollBy(-20)
        session.awaitFrame { !it.scrollbar!!.atBottom }

        session.sendKey(KeyInput(TerminalKey.ENTER))

        session.awaitFrame { it.scrollbar!!.atBottom }
    }

    @Test
    fun aKeyThatSendsNothingDoesNotBringTheScreenBack() {
        val session = historySession()
        session.scrollBy(-20)
        val scrolled = session.awaitFrame { !it.scrollbar!!.atBottom }

        session.sendKey(KeyInput(TerminalKey.SHIFT_LEFT))

        assertEquals(scrolled.scrollbar!!.offset, session.frames.value.scrollbar!!.offset)
    }

    @Test
    fun theWheelScrollsTheHistoryOnTheMainScreen() {
        val session = historySession()
        val bottom = session.frames.value

        session.scrollWheel(-3)

        session.awaitFrame { it.firstNumber() == bottom.firstNumber() - 3 }
    }

    @Test
    fun theWheelSendsArrowKeysOnTheAlternateScreen() {
        val session = start("""printf '\033[?1049h'; stty raw -echo; echo ready; head -c9 | tr -d '\033' | od -An -c""")
        session.awaitFrame { "ready" in it.text() }

        // Up, up, down.
        session.scrollWheel(-2)
        session.scrollWheel(1)

        val frame = session.awaitFrame { "[A[A[B" in it.text().replace(Regex("\\s+"), "") }
        assertEquals(null, frame.scrollbar, "The alternate screen has no history")
    }

    @Test
    fun theWheelDoesNothingOnTheAlternateScreenWhenTheProgramTurnedAlternateScrollOff() {
        val session = start("""printf '\033[?1049h\033[?1007l'; stty raw -echo; echo ready; head -c1 | od -An -c""")
        session.awaitFrame { "ready" in it.text() }

        session.scrollWheel(-3)
        session.sendKey(KeyInput(TerminalKey.ENTER))

        // The first byte that the program read is the Enter.
        session.awaitFrame { "\\r" in it.text() }
    }

    @Test
    fun dragsASelectionPastTheBottomAndScrollsWithIt() {
        val session = historySession()
        session.scrollBy(-30)
        val start = session.awaitFrame { !it.scrollbar!!.atBottom }

        session.selectionPress(0.2f, 0.5f)
        // Below the screen, which has 6 rows.
        val autoscroll = session.selectionDrag(2.7f, 8f)
        assertEquals(TerminalAutoscroll.DOWN, autoscroll)
        repeat(3) { assertEquals(TerminalAutoscroll.DOWN, session.selectionAutoscroll(2.7f, 8f)) }
        val text = session.selectionRelease(2.7f, 8f)

        val scrolled = session.awaitFrame { it.scrollbar!!.offset == start.scrollbar!!.offset + 3 }
        assertEquals(start.firstNumber() + 3, scrolled.firstNumber())
        // What was scrolled in is selected, which is more lines than the screen has.
        assertTrue(text!!.lines().size > 6, "The selection is ${text.lines().size} lines: $text")
    }

    @Test
    fun doesNotAutoscrollWhenThePointerIsNotPastAnEdge() {
        val session = historySession()
        session.scrollBy(-30)
        session.awaitFrame { !it.scrollbar!!.atBottom }

        session.selectionPress(0.2f, 0.5f)

        assertEquals(TerminalAutoscroll.NONE, session.selectionDrag(2.7f, 2.5f))
        assertEquals(TerminalAutoscroll.NONE, session.selectionAutoscroll(2.7f, 2.5f))
    }

    @Test
    fun pastesTextToTheProgram() {
        val session = start("""stty raw -echo; echo ready; head -c10 | tr -d '\033' | od -An -c""")
        session.awaitFrame { "ready" in it.text() }

        // Not asked for bracketed paste: the newline is what the Enter key sends.
        session.paste("one\ntwo\nth")

        session.awaitFrame { "one\\rtwo\\rth" in it.text().replace(Regex("\\s+"), "") }
    }

    @Test
    fun pastesInTheMarkersWhenTheProgramAskedForThem() {
        val session = start("""printf '\033[?2004h'; stty raw -echo; echo ready; head -c15 | tr -d '\033' | od -An -c""")
        session.awaitFrame { "ready" in it.text() }

        session.paste("a\nb")

        // ESC [ 2 0 0 ~, a, newline, b, ESC [ 2 0 1 ~: 14 bytes.
        session.awaitFrame { "[200~a\\nb[201~" in it.text().replace(Regex("\\s+"), "") }
    }

    @Test
    fun aPasteIsTypedSoItUnselectsAndBringsTheScreenBack() {
        val session = historySession()
        session.select(0.2f, 0.5f, 4.7f, 0.5f)
        session.awaitFrame { it.selection.isNotEmpty() }
        session.scrollBy(-20)
        session.awaitFrame { !it.scrollbar!!.atBottom }

        session.paste("x")

        session.awaitFrame { it.selection.isEmpty() && it.scrollbar!!.atBottom }
    }

    @Test
    fun anEmptyPasteSendsNothing() {
        val session = start("stty raw -echo; echo ready; head -c1 | od -An -c")
        session.awaitFrame { "ready" in it.text() }

        session.paste("")
        session.sendKey(KeyInput(TerminalKey.ENTER))

        // The first byte that the program read is the Enter.
        session.awaitFrame { "\\r" in it.text() }
    }

    @Test
    fun givesTheSelectedTextToCopy() {
        val session = selectionSession()
        assertEquals(null, session.selectedText())

        session.select(0.2f, 0.5f, 4.7f, 0.5f)

        assertEquals("hello", session.selectedText())
    }

    // Two pixels, red and green, as a program sends them with the Kitty graphics protocol.
    private val twoPixels = java.util.Base64.getEncoder().encodeToString(byteArrayOf(-1, 0, 0, -1, 0, -1, 0, -1))

    // ESC _ G control ; payload ESC \ for a shell script: the escapes are for printf.
    private fun kittyPrintf(control: String, payload: String = twoPixels) = """printf '\033_G$control;%s\033\\' '$payload'"""

    // A program that waits for a key before it does what it is given, so that the terminal has its size when it does.
    private fun imageSession(script: String, rows: Int = 6): TerminalSession {
        val session = start("stty raw -echo; echo ready; head -c1 >/dev/null; $script; sleep 30", rows = rows)
        session.resize(80, rows, 10, 20)
        session.awaitFrame { "ready" in it.text() }
        session.sendKey(KeyInput(TerminalKey.ENTER))
        return session
    }

    @Test
    fun hasNoImagesUntilAProgramSendsOne() {
        val session = start("echo hi; sleep 30")

        assertTrue(session.awaitFrame { it.text(0) == "hi" }.images.isEmpty())
    }

    @Test
    fun showsAnImageThatAProgramSent() {
        val session = imageSession(kittyPrintf("a=T,f=32,s=2,v=1,i=1,c=2,r=1"))

        val placement = session.awaitFrame { it.images.isNotEmpty() }.images.single()

        // The cursor was on the second row, under "ready": the terminal is in raw mode, where a newline does not go back to
        // the first column.
        assertEquals(5f, placement.left)
        assertEquals(1f, placement.top)
        // 2 columns by one row, whatever the pixels of the image are.
        assertEquals(2f, placement.width)
        assertEquals(1f, placement.height)
        assertEquals(2, placement.image.width)
        assertEquals(1, placement.image.height)
        assertContentEquals(byteArrayOf(-1, 0, 0, -1, 0, -1, 0, -1), placement.image.rgba)
        assertEquals(TerminalImageLayer.ABOVE_TEXT, placement.layer)
    }

    @Test
    fun anImageIsInTheLayerThatItsZSays() {
        val session = imageSession(
            kittyPrintf("a=T,f=32,s=2,v=1,i=1,z=5") + "; " + kittyPrintf("a=T,f=32,s=2,v=1,i=2,z=-1") + "; " +
                kittyPrintf("a=T,f=32,s=2,v=1,i=3,z=-2000000000"),
        )

        val images = session.awaitFrame { it.images.size == 3 }.images

        // In the order that they are drawn in: the lowest first.
        assertEquals(listOf(3, 2, 1), images.map { it.image.id })
        assertEquals(
            listOf(TerminalImageLayer.BELOW_BACKGROUND, TerminalImageLayer.BELOW_TEXT, TerminalImageLayer.ABOVE_TEXT),
            images.map { it.layer },
        )
    }

    @Test
    fun theSameImageIsTheSameObjectInTheFramesAfter() {
        val session = imageSession(kittyPrintf("a=T,f=32,s=2,v=1,i=1"))
        val first = session.awaitFrame { it.images.isNotEmpty() }.images.single().image

        session.writeToScreen("\u001b[6;1Hmore text")
        val after = session.awaitFrame { it.text(5).startsWith("more text") }

        assertSame(first, after.images.single().image, "A renderer keeps what it made of it, keyed by the object")
    }

    @Test
    fun anImageThatIsSentAgainIsANewObject() {
        val session = imageSession(kittyPrintf("a=T,f=32,s=2,v=1,i=1"))
        val first = session.awaitFrame { it.images.isNotEmpty() }.images.single().image

        // The same id, with the pixels the other way round.
        session.writeToScreen("\u001b_Ga=T,f=32,s=2,v=1,i=1;" + java.util.Base64.getEncoder().encodeToString(byteArrayOf(0, -1, 0, -1, -1, 0, 0, -1)) + "\u001b\\")

        val replaced = session.awaitFrame { frame -> frame.images.any { it.image !== first } }.images.last().image
        assertNotEquals(first.generation, replaced.generation)
        assertContentEquals(byteArrayOf(0, -1, 0, -1, -1, 0, 0, -1), replaced.rgba)
    }

    @Test
    fun anImageThatWasDeletedIsNotInTheFramesAnyMore() {
        val session = imageSession(kittyPrintf("a=T,f=32,s=2,v=1,i=1"))
        session.awaitFrame { it.images.isNotEmpty() }

        session.writeToScreen("\u001b_Ga=d,d=I,i=1\u001b\\")

        session.awaitFrame { it.images.isEmpty() }
    }

    @Test
    fun anImageScrollsWithTheTextAndGoesWhenItIsOutOfTheView() {
        val session = imageSession(kittyPrintf("a=T,f=32,s=2,v=1,i=1"))
        val first = session.awaitFrame { it.images.isNotEmpty() }.images.single()

        // The screen has 6 rows: the image is on the second, and the cursor is on the third. Four lines go to the last row and
        // the fifth scrolls the screen by one.
        session.writeToScreen("\r\n".repeat(5))
        val scrolled = session.awaitFrame { frame -> frame.images.singleOrNull()?.let { it.top < first.top } == true }
        assertSame(first.image, scrolled.images.single().image)

        session.writeToScreen("\r\n".repeat(10))
        session.awaitFrame { it.images.isEmpty() }
    }

    @Test
    fun anImageIsBackWhenTheHistoryIsScrolledBackToIt() {
        val session = imageSession(kittyPrintf("a=T,f=32,s=2,v=1,i=1"))
        val first = session.awaitFrame { it.images.isNotEmpty() }.images.single().image
        session.writeToScreen("\r\n".repeat(20))
        session.awaitFrame { it.images.isEmpty() }

        session.scrollToTop()

        assertSame(first, session.awaitFrame { it.images.isNotEmpty() }.images.single().image, "The pixels were kept")
    }

    @Test
    fun theAlternateScreenHasItsOwnImages() {
        val session = imageSession(kittyPrintf("a=T,f=32,s=2,v=1,i=1"))
        session.awaitFrame { it.images.isNotEmpty() }

        session.writeToScreen("\u001b[?1049h")
        session.awaitFrame { it.images.isEmpty() }
        session.writeToScreen("\u001b[?1049l")

        assertEquals(1, session.awaitFrame { it.images.isNotEmpty() }.images.single().image.id)
    }

    @Test
    fun theCursorBlinksUnlessTheProgramOrTheCallerSaysOtherwise() {
        val session = start("sleep 30")
        assertTrue(session.awaitFrame { true }.cursor.blinking, "A terminal's cursor blinks")

        session.writeToScreen("\u001b[2 q")
        assertEquals(false, session.awaitFrame { !it.cursor.blinking }.cursor.blinking, "A steady cursor is what the program asked for")

        val steady = TerminalSession.start(80, 5, command = listOf("/bin/sh", "-c", "sleep 30"), cursorBlink = false).also { sessions += it }
        assertEquals(false, steady.awaitFrame { true }.cursor.blinking)
    }

    @Test
    fun writesAMessageOnTheScreenAfterWhatTheProgramWrote() {
        val session = start("printf hello")
        val code = session.awaitExit()

        session.writeToScreen("\r\n[exited with code $code]")

        val frame = session.awaitFrame { it.text(1) == "[exited with code 0]" }
        assertEquals("hello", frame.text(0), "The message comes after what the program wrote")
    }

    @Test
    fun doesNotSendWhatItWritesOnTheScreenToTheProgram() {
        // In raw mode, the program gets exactly the bytes that are sent: it must not get the message.
        val session = start("stty raw -echo; echo ready; head -c1 | od -An -tx1")
        session.awaitFrame { "ready" in it.text() }

        session.writeToScreen("message")
        session.sendKey(KeyInput(TerminalKey.ENTER))

        // The program only got the Enter, which is the one byte it reads.
        val frame = session.awaitFrame { "0d" in it.text() }
        assertTrue("message" in frame.text())
    }

    @Test
    fun ignoresWhatIsWrittenOnTheScreenOnceClosed() {
        val session = start("sleep 30")
        session.close()

        session.writeToScreen("too late")
    }

    @Test
    fun endsTheProgramWhenClosed() {
        val session = start("sleep 30")
        session.awaitFrame { true }

        session.close()

        // 0 would be the exit code of a program that ran to its end.
        assertNotEquals(0, session.awaitExit())
    }

    @Test
    fun tellsAProgramThatAskedToKnowWhenTheFocusChanges() {
        val session = start("""printf '\033[?1004h'; stty raw -echo; echo ready; head -c6 | tr -d '\033' | od -An -c""")
        session.awaitFrame { "ready" in it.text() }

        session.setFocused(true)
        session.setFocused(false)

        session.awaitFrame { "[I[O" in it.text().replace(Regex("\\s+"), "") }
    }

    @Test
    fun doesNotTellAProgramThatDidNotAskToKnow() {
        val session = start("""stty raw -echo; echo ready; head -c1 | od -An -c""")
        session.awaitFrame { "ready" in it.text() }

        session.setFocused(true)
        session.setFocused(false)
        session.sendKey(KeyInput(TerminalKey.ENTER))

        // The first byte that the program read is the Enter.
        session.awaitFrame { "\\r" in it.text() }
    }

    @Test
    fun tellsAProgramOnlyWhenTheFocusChanges() {
        val session = start("""printf '\033[?1004h'; stty raw -echo; echo ready; head -c6 | tr -d '\033' | od -An -c""")
        session.awaitFrame { "ready" in it.text() }

        session.setFocused(false)
        session.setFocused(true)
        session.setFocused(true)
        session.setFocused(false)
        session.setFocused(false)

        // Not focused to start with: the first report is the gain, and nothing is repeated.
        session.awaitFrame { "[I[O" in it.text().replace(Regex("\\s+"), "") }
    }

    @Test
    fun reportingTheFocusDoesNotScrollTheHistoryBack() {
        val session = start("""printf '\033[?1004h'; seq 1 200; sleep 30""", rows = 6)
        session.resize(80, 6, 10, 20)
        session.awaitFrame { frame -> frame.rows.any { frame.text(frame.rows.indexOf(it)) == "200" } && frame.scrollbar != null }
        session.scrollBy(-10)
        val scrolled = session.awaitFrame { it.scrollbar?.atBottom == false }

        session.setFocused(true)
        session.setFocused(false)

        // What is typed brings the screen back to the bottom, a focus report is not typed.
        Thread.sleep(100)
        assertEquals(scrolled.scrollbar!!.offset, session.frames.value.scrollbar!!.offset)
    }

    // The events that the session publishes from now on: nothing is replayed, so this comes before the program writes them.
    private fun TerminalSession.collectEvents(): List<TerminalEvent> {
        val events = CopyOnWriteArrayList<TerminalEvent>()
        // Unconfined: collecting has started when this returns.
        collectors += CoroutineScope(Dispatchers.Unconfined).launch { this@collectEvents.events.collect { events += it } }
        return events
    }

    private fun await(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TIMEOUT_MS * 1_000_000
        while (!condition()) {
            check(System.nanoTime() < deadline) { "Still waiting for $what" }
            Thread.sleep(5)
        }
    }

    // A program that waits for a byte before it writes what is tested, so that the test is listening.
    private fun startThatWaits(
        writes: String,
        rows: Int = 5,
        clipboardWrite: Boolean = true,
        clipboardReader: ((TerminalClipboardLocation) -> String?)? = null,
    ): TerminalSession {
        val script = "stty raw -echo; echo ready; head -c1 >/dev/null; $writes"
        val session = TerminalSession.start(
            80, rows, command = listOf("/bin/sh", "-c", script), clipboardWrite = clipboardWrite, clipboardReader = clipboardReader,
        ).also { sessions += it }
        session.awaitFrame { "ready" in it.text() }
        return session
    }

    @Test
    fun publishesTheTitleTheProgramSets() {
        val session = startThatWaits("""printf '\033]2;my title\007'; sleep 30""")
        assertEquals("", session.title.value)

        session.paste("x")

        await("the title") { session.title.value == "my title" }
    }

    @Test
    fun publishesTheWorkingDirectoryAsAPath() {
        val session = startThatWaits("""printf '\033]7;file://host/tmp/a%%20b\007'; sleep 30""")
        assertEquals(null, session.workingDirectory.value)

        session.paste("x")

        await("the directory") { session.workingDirectory.value == "/tmp/a b" }
    }

    @Test
    fun aResetClearsWhatWasReported() {
        val session = startThatWaits("""printf '\033]2;t\007\033]7;file:///tmp\007'; head -c1 >/dev/null; printf '\033c'; sleep 30""")
        session.paste("x")
        await("the title") { session.title.value == "t" && session.workingDirectory.value == "/tmp" }

        session.paste("x")

        await("the reset") { session.title.value == "" && session.workingDirectory.value == null }
    }

    @Test
    fun publishesTheBellAndNotifications() {
        val session = startThatWaits("""printf 'a\007\033]9;body only\007\033]777;notify;Title;Body\007'; sleep 30""")
        val events = session.collectEvents()

        session.paste("x")

        await("the events") { events.size >= 3 }
        assertEquals(
            listOf(TerminalEvent.Bell, TerminalEvent.Notification("", "body only"), TerminalEvent.Notification("Title", "Body")),
            events.toList(),
        )
    }

    @Test
    fun publishesTheProgress() {
        val session = startThatWaits("""printf '\033]9;4;1;50\007'; head -c1 >/dev/null; printf '\033]9;4;0\007'; sleep 30""")

        session.paste("x")
        await("the progress") { session.progress.value == TerminalProgress(TerminalProgressState.SET, 50) }

        session.paste("x")
        await("the end of the progress") { session.progress.value == null }
    }

    @Test
    fun publishesTheStepsOfTheShell() {
        val session = startThatWaits("""printf '\033]133;A\007\033]133;B\007\033]133;C\007\033]133;D;1\007'; sleep 30""")
        val events = session.collectEvents()

        session.paste("x")

        await("the events") { events.size >= 3 }
        assertEquals(
            listOf(TerminalEvent.PromptStarted, TerminalEvent.CommandStarted(null), TerminalEvent.CommandFinished(1)),
            events.toList(),
        )
    }

    @Test
    fun publishesWhatAProgramPutsInTheClipboard() {
        val session = startThatWaits("""printf '\033]52;c;aGVsbG8=\007\033]52;p;d29ybGQ=\007'; sleep 30""")
        val events = session.collectEvents()

        session.paste("x")

        await("the events") { events.size >= 2 }
        assertEquals(
            listOf(
                TerminalEvent.ClipboardWrite(TerminalClipboardLocation.STANDARD, "hello"),
                TerminalEvent.ClipboardWrite(TerminalClipboardLocation.PRIMARY, "world"),
            ),
            events.toList(),
        )
    }

    @Test
    fun refusesWhatAProgramPutsInTheClipboardWhenItIsNotAllowed() {
        val session = startThatWaits("""printf '\033]52;c;aGVsbG8=\007\007'; sleep 30""", clipboardWrite = false)
        val events = session.collectEvents()

        session.paste("x")

        // The bell that follows is there: what came before it was handled, and is not an event.
        await("the bell") { events.isNotEmpty() }
        assertEquals(listOf<TerminalEvent>(TerminalEvent.Bell), events.toList())
    }

    @Test
    fun answersAProgramThatReadsTheClipboardWithWhatTheHostGives() {
        val reads = CopyOnWriteArrayList<TerminalClipboardLocation>()
        val session = startThatWaits(
            """printf '\033]52;c;?\007'; head -c11 | tr -d '\033'""",
            clipboardReader = { location -> reads += location; "hi" },
        )

        session.paste("x")

        // The answer is the clipboard's text in base64.
        session.awaitFrame { "]52;c;aGk=" in it.text() }
        assertEquals(listOf(TerminalClipboardLocation.STANDARD), reads.toList())
    }

    @Test
    fun doesNotAnswerAProgramThatReadsTheClipboardWithoutAReader() {
        val session = startThatWaits("""printf '\033]52;c;?\007'; printf 'done\n'; sleep 30""")

        session.paste("x")

        session.awaitFrame { "done" in it.text() }
        assertTrue("52;c" !in session.frames.value.text())
    }

    @Test
    fun scrollsToThePreviousPromptTheShellMarked() {
        val session = startThatWaits(
            """i=0; while [ ${'$'}i -lt 60 ]; do printf '\033]133;A\007prompt %s\r\n' ${'$'}i; i=${'$'}((i+1)); done; sleep 30""",
        )
        session.paste("x")
        val bottom = session.awaitFrame { it.scrollbar?.atBottom == true && "prompt 59" in it.text() }.scrollbar!!

        session.scrollToPrompt(-1)

        val scrolled = session.awaitFrame { frame -> frame.scrollbar!!.offset < bottom.offset }.scrollbar!!
        // A prompt is a row here: one row up.
        assertEquals(bottom.offset - 1, scrolled.offset)
    }

    @Test
    fun findsTheLinksOnTheScreen() {
        val session = startThatWaits(
            """printf '\r\033]8;;https://example.com/a\033\\link\033]8;;\033\\ see https://kodein.net/x). end\r\n'; sleep 30""",
        )
        session.paste("x")
        session.awaitFrame { "see https" in it.text(1) }

        // The one the program marked: the four cells of "link".
        assertEquals(TerminalLink("https://example.com/a", 1, 0..3), session.linkAt(2, 1))
        // The one that is written, without the ")." that ends the sentence.
        assertEquals(TerminalLink("https://kodein.net/x", 1, 9..28), session.linkAt(15, 1))
        assertEquals(null, session.linkAt(6, 1), "A word that is not a link")
        assertEquals(null, session.linkAt(60, 1), "An empty cell")
        assertEquals(null, session.linkAt(0, 3), "An empty row")
        assertEquals(null, session.linkAt(500, 1), "A cell that is not on the screen")
    }

    @Test
    fun knowsWhenTheProgramIsOnTheAlternateScreen() {
        val session = startThatWaits("""printf '\033[?1049h'; head -c1 >/dev/null; printf '\033[?1049l'; sleep 30""")
        assertEquals(false, session.frames.value.alternateScreen)

        session.paste("x")
        session.awaitFrame { it.alternateScreen }

        session.paste("x")
        session.awaitFrame { !it.alternateScreen }
    }

    @Test
    fun aHiddenSessionProducesNoFramesButIsStillFedAndPublishes() {
        val session = startThatWaits("""printf '\033]2;hidden title\007hello'; sleep 30""")
        // The frames that the first output asked for.
        Thread.sleep(150)
        val before = session.frames.value

        session.setVisible(false)
        session.paste("x")

        await("the title that the program set while hidden") { session.title.value == "hidden title" }
        Thread.sleep(200)
        assertSame(before, session.frames.value, "No frame was produced")
        assertEquals(false, session.visible.value)
    }

    @Test
    fun showingASessionAgainProducesAFrameWithWhatHappenedWhileItWasHidden() {
        val session = startThatWaits("""i=0; while [ ${'$'}i -lt 100 ]; do printf 'line %s\r\n' ${'$'}i; i=${'$'}((i+1)); done; sleep 30""", rows = 5)
        Thread.sleep(150)

        session.setVisible(false)
        session.paste("x")
        // The program printed a hundred lines, which the screen scrolled through: the last of them is a few rows, and the rest is history.
        Thread.sleep(500)
        session.setVisible(true)

        val frame = session.awaitFrame { frame -> frame.rows.indices.any { frame.text(it) == "line 99" } }
        assertEquals(listOf("line 96", "line 97", "line 98", "line 99"), (0 until 5).map { frame.text(it) }.filter { it.isNotEmpty() })
        assertTrue(frame.scrollbar!!.total > frame.scrollbar!!.len, "The lines that scrolled out are in the history")
    }

    @Test
    fun theKeysOfAHiddenSessionReachTheProgram() {
        val session = startThatWaits("""head -c2 | od -An -c; sleep 30""")
        Thread.sleep(150)

        session.setVisible(false)
        session.paste("x")
        // The program that is waiting for it reads what is there, and drops what it does not keep: the next keys come after.
        Thread.sleep(300)
        session.sendText("ab")
        Thread.sleep(300)
        session.setVisible(true)

        session.awaitFrame { "a b" in it.text().replace(Regex("\\s+"), " ") }
    }

    @Test
    fun showingAVisibleSessionAndHidingAndShowingItAgainAreHarmless() {
        val session = startThatWaits("""printf 'hello'; sleep 30""")
        Thread.sleep(150)
        val before = session.frames.value

        session.setVisible(true)
        Thread.sleep(150)
        assertSame(before, session.frames.value, "It was visible: nothing changes")

        session.setVisible(false)
        session.setVisible(true)
        session.setVisible(false)
        session.setVisible(true)
        session.paste("x")

        session.awaitFrame { "hello" in it.text() }
    }

    @Test
    fun aHiddenSessionCanBeClosed() {
        val session = start("sleep 30")
        session.awaitFrame { true }
        session.setVisible(false)

        session.close()

        assertNotEquals(0, session.awaitExit())
    }

    // A session with the options that are tested: the program writes what it is given by the test, in raw mode.
    private fun startWith(
        script: String,
        rows: Int = 5,
        cursorShape: TerminalCursorShape = TerminalCursorShape.BLOCK,
        scrollbackMaxBytes: Long = 10_000_000L,
        imageStorageLimitBytes: Long = 320_000_000L,
    ): TerminalSession = TerminalSession.start(
        80, rows, command = listOf("/bin/sh", "-c", script), cursorShape = cursorShape,
        scrollbackMaxBytes = scrollbackMaxBytes, imageStorageLimitBytes = imageStorageLimitBytes,
    ).also { sessions += it }

    @Test
    fun theCursorHasTheShapeThatWasAskedForUntilTheProgramSetsOne() {
        val session = startWith("""stty raw -echo; echo ready; head -c1 >/dev/null; printf '\033[4 q'; head -c1 >/dev/null; printf '\033[0 q'; sleep 30""", cursorShape = TerminalCursorShape.BAR)

        assertEquals(TerminalCursorShape.BAR, session.awaitFrame { "ready" in it.text() }.cursor.shape)

        session.paste("x")
        session.awaitFrame { it.cursor.shape == TerminalCursorShape.UNDERLINE }

        session.paste("x")
        session.awaitFrame { it.cursor.shape == TerminalCursorShape.BAR }
    }

    @Test
    fun theHollowBlockIsNotACursorShapeToStartWith() {
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            TerminalSession.start(80, 5, command = listOf("/bin/sh", "-c", "sleep 30"), cursorShape = TerminalCursorShape.BLOCK_HOLLOW)
        }
    }

    @Test
    fun theHistoryIsLimitedByWhatWasAskedForAtTheStart() {
        val small = startWith("seq 1 30000; sleep 30", scrollbackMaxBytes = 1_000_000)
        val large = startWith("seq 1 30000; sleep 30", scrollbackMaxBytes = 100_000_000)

        val smallFrame = small.awaitFrame { frame -> frame.rows.indices.any { frame.text(it) == "30000" } }
        val largeFrame = large.awaitFrame { frame -> frame.rows.indices.any { frame.text(it) == "30000" } }

        assertTrue(smallFrame.scrollbar!!.total < largeFrame.scrollbar!!.total, "${smallFrame.scrollbar!!.total} rows against ${largeFrame.scrollbar!!.total}")
    }

    @Test
    fun loweringTheScrollbackLimitTakesHistoryBack() {
        val session = startWith("seq 1 30000; sleep 30", scrollbackMaxBytes = 100_000_000)
        val before = session.awaitFrame { frame -> frame.rows.indices.any { frame.text(it) == "30000" } }.scrollbar!!.total

        session.setScrollbackLimit(1_000_000)

        val after = session.awaitFrame { (it.scrollbar?.total ?: Long.MAX_VALUE) < before }.scrollbar!!.total
        assertTrue(after > 5, "Some history is kept: $after")
    }

    @Test
    fun noImagesAreStoredWhenTheLimitIsZeroAndTheyGoWhenItIsLowered() {
        val image = """printf '\033_Ga=T,f=32,s=2,v=2,i=1,q=2;/////////////////////w==\033\\'"""
        val off = startWith("$image; sleep 30", imageStorageLimitBytes = 0)
        Thread.sleep(500)
        assertEquals(emptyList(), off.frames.value.images, "Images are off")

        val on = startWith("$image; stty raw -echo; echo ready; head -c1 >/dev/null; sleep 30")
        on.awaitFrame { it.images.isNotEmpty() }

        on.setImageStorageLimit(0)

        on.awaitFrame { it.images.isEmpty() }
    }

    @Test
    fun aNegativeLimitIsRefused() {
        val session = start("sleep 30")
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { session.setScrollbackLimit(-1) }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { session.setImageStorageLimit(-1) }
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
