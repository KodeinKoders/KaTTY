package app.katty.terminal

import app.katty.ghostty.GhosttyKeyEncoder
import app.katty.ghostty.GhosttyMouseEncoder
import app.katty.ghostty.GhosttyMouseShape
import app.katty.ghostty.GhosttySelectionGesture
import app.katty.ghostty.GhosttyRenderState
import app.katty.ghostty.GhosttyRowId
import app.katty.ghostty.GhosttyTerminal
import com.pty4j.PtyProcess
import com.pty4j.PtyProcessBuilder
import com.pty4j.WinSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

private val logger = LoggerFactory.getLogger(TerminalSession::class.java)

/**
 * A program running in a pseudo-terminal, and the virtual terminal that makes sense of what it writes.
 *
 * The program's output is read on a thread of its own and fed to the terminal; [frames] publishes what the
 * screen looks like when it changes. Neither blocks the other, and no call here blocks the UI thread
 * for longer than it takes to encode a key.
 *
 * Create one with [start], and [close] it.
 */
public class TerminalSession private constructor(
    private val process: PtyProcess,
    private val terminal: GhosttyTerminal,
    private val renderState: GhosttyRenderState,
    private val keyEncoder: GhosttyKeyEncoder,
    private val mouseEncoder: GhosttyMouseEncoder,
    private val selectionGesture: GhosttySelectionGesture,
    theme: TerminalTheme?,
) : AutoCloseable {
    // Guards the terminal, and the key and mouse encoders that read it: the thread that feeds the terminal and the
    // ones that encode keys or resize it take turns. The render state is only ever used by the frame
    // producer, and only its update reads the terminal.
    private val lock = ReentrantLock()

    private val closed = AtomicBoolean(false)

    // The size of a cell in pixels that the terminal was given, which is what positions of the mouse are
    // converted with, and the geometry that the mouse encoder has. Under the lock.
    private var cellWidthPx = INITIAL_CELL_WIDTH_PX
    private var cellHeightPx = INITIAL_CELL_HEIGHT_PX
    private var mouseGeometry: List<Int>? = null

    // Whether something is selected, as far as the session knows, so that the keys that unselect do not have to ask
    // the terminal. Under the lock.
    private var hasSelection = false

    // The colors that a frame has besides the ones libghostty resolves. Written under the lock, with the
    // colors of the terminal, and read by the producer.
    @Volatile
    private var theme: TerminalTheme? = theme

    // What goes to the program: the keys, and what the terminal answers to its queries.
    private val outgoing = LinkedBlockingQueue<ByteArray>()

    // A conflated channel: any number of signals while a frame is being produced mean one more frame.
    private val frameRequests = Channel<Unit>(Channel.CONFLATED)

    // When the reader last fed the terminal, in System.nanoTime() terms: what the producer waits on.
    @Volatile
    private var lastOutputNanos = System.nanoTime() - 1_000_000_000L

    // The rows of the last frame, to hand the same ones out again when they did not change. Only used by
    // the producer, once it runs.
    private var rowCache: Map<GhosttyRowId, TerminalRow> = emptyMap()

    private val frameState = MutableStateFlow(produceFrame())

    private val exitCode = MutableStateFlow<Int?>(null)

    private val producerThreads = Executors.newSingleThreadExecutor { Thread(it, "katty-terminal-frames").apply { isDaemon = true } }
    private val producerDispatcher = producerThreads.asCoroutineDispatcher()
    private val producerScope = CoroutineScope(SupervisorJob() + producerDispatcher)

    private val reader = Thread(::readOutput, "katty-terminal-reader").apply { isDaemon = true }
    private val writer = Thread(::writeInput, "katty-terminal-writer").apply { isDaemon = true }

    /**
     * What the screen looks like. A new frame is published whenever it changes, at most 60 times per
     * second: what the program writes in between is merged, and a frame waits for the output to pause for a
     * moment (3 milliseconds, at most eight), so that a redraw is not shown half-way.
     */
    public val frames: StateFlow<TerminalFrame> get() = frameState

    /**
     * The exit code of the program, once it has exited.
     */
    public val exited: StateFlow<Int?> get() = exitCode

    private fun launch() {
        // What the terminal answers to the program's queries (where the cursor is, what the terminal is) is
        // written to its input like what is typed. It is called while the terminal is written to, under
        // the lock: it only enqueues.
        terminal.onWritePty = { outgoing.put(it) }

        producerScope.launch {
            for (request in frameRequests) {
                val started = TimeSource.Monotonic.markNow()
                settle(started)
                try {
                    frameState.value = produceFrame()
                } catch (e: Throwable) {
                    if (closed.get()) break
                    logger.error("Could not produce a frame", e)
                }

                // What is requested while waiting is merged into the one next frame, so the last output is
                // always published, only later. After an idle period there is nothing to wait for.
                val remaining = MIN_FRAME_INTERVAL - started.elapsedNow()
                if (remaining.isPositive()) delay(remaining)
            }
        }
        reader.start()
        writer.start()
    }

    /**
     * Waits for the program's output to pause, so that a redraw is shown whole.
     *
     * A program redraws in several writes, a fraction of a millisecond apart, that are read separately: zsh,
     * moving the selection in its completion menu, moves the cursor down to the menu, draws the entries, and
     * moves the cursor back to the line. A frame made after the first of them has the cursor in the menu, and
     * the next frame is only made one frame interval later, so it is that frame that the display shows for a
     * moment.
     *
     * The wait is at most [SETTLE_MAX] from [requested], so a program that never pauses is still shown, and
     * it is part of the interval between two frames, which it does not lengthen.
     */
    private suspend fun settle(requested: TimeMark) {
        while (true) {
            val quiet = (System.nanoTime() - lastOutputNanos).nanoseconds
            if (quiet >= SETTLE_QUIET || requested.elapsedNow() >= SETTLE_MAX) return
            delay(1)
        }
    }

    /**
     * Sends a key to the program, as the terminal's current modes ask for it to be encoded.
     */
    public fun sendKey(input: KeyInput) {
        val bytes = lock.withLock {
            if (closed.get()) return
            keyEncoder.setOptionsFrom(terminal)
            val bytes = keyEncoder.encode(input.toGhostty())
            // What is typed for the program unselects: the selection is of what was on the screen before.
            if (bytes.isNotEmpty()) unselect()
            bytes
        }
        if (bytes.isNotEmpty()) {
            outgoing.put(bytes)
            frameRequests.trySend(Unit)
        }
    }

    /**
     * A mouse button goes down to start selecting text, on the cell at [column] and [row] (fractional, from the top
     * left of the screen: see [MouseInput]). A double click selects the word under the pointer, and a triple click
     * the line; a single click selects nothing yet and unselects what was selected. Selecting is the terminal's
     * own, and the program is told nothing: it is for the caller to decide which presses are for it.
     */
    public fun selectionPress(column: Float, row: Float) {
        lock.withLock {
            if (closed.get()) return
            val at = locate(column, row)
            val selected = selectionGesture.press(
                at.column, at.row, at.xPx, at.yPx,
                timeNs = System.nanoTime(),
                repeatDistancePx = REPEAT_CLICK_DISTANCE_PX,
                repeatIntervalNs = REPEAT_CLICK_INTERVAL_NS,
            )
            if (selected) hasSelection = true else unselect()
        }
        frameRequests.trySend(Unit)
    }

    /**
     * The pointer moves with the button held, which extends the selection to the cell at [column] and [row].
     *
     * @param rectangle Whether to select a block of cells instead of a flow of text.
     */
    public fun selectionDrag(column: Float, row: Float, rectangle: Boolean = false) {
        lock.withLock {
            if (closed.get()) return
            val at = locate(column, row)
            val selected = selectionGesture.drag(
                at.column, at.row, at.xPx, at.yPx,
                cols = terminal.cols,
                cellWidthPx = cellWidthPx,
                screenHeightPx = terminal.rows * cellHeightPx,
                rectangle = rectangle,
            )
            if (selected) hasSelection = true
        }
        frameRequests.trySend(Unit)
    }

    /**
     * The button goes up, which ends the selection.
     *
     * @return The text that is selected, for the caller to copy, or `null` if nothing is selected.
     */
    public fun selectionRelease(column: Float, row: Float): String? {
        val text = lock.withLock {
            if (closed.get()) return null
            val at = locate(column, row)
            selectionGesture.release(at.column, at.row)
            terminal.selectedText()?.takeIf { it.isNotEmpty() }
        }
        frameRequests.trySend(Unit)
        return text
    }

    /** Unselects what is selected. */
    public fun clearSelection() {
        lock.withLock {
            if (closed.get()) return
            selectionGesture.reset()
            unselect()
        }
        frameRequests.trySend(Unit)
    }

    // Under the lock.
    private fun unselect() {
        if (!hasSelection) return
        hasSelection = false
        terminal.clearSelection()
    }

    // The cell under a position in cells, kept inside the screen, and the position in pixels with the size of a cell
    // that the terminal was given. Under the lock.
    private class Located(val column: Int, val row: Int, val xPx: Double, val yPx: Double)

    private fun locate(column: Float, row: Float): Located {
        val cols = terminal.cols
        val rows = terminal.rows
        val x = column.coerceIn(0f, cols - 0.001f)
        val y = row.coerceIn(0f, rows - 0.001f)
        return Located(x.toInt(), y.toInt(), (x * cellWidthPx).toDouble(), (y * cellHeightPx).toDouble())
    }

    /**
     * Sends a mouse event to the program, in the protocol it chose, if it asked to be told about the mouse.
     * Nothing is sent otherwise.
     */
    public fun sendMouse(input: MouseInput) {
        val bytes = lock.withLock {
            if (closed.get()) return
            mouseEncoder.setOptionsFrom(terminal)
            val cols = terminal.cols
            val rows = terminal.rows
            val geometry = listOf(cols, rows, cellWidthPx, cellHeightPx)
            if (geometry != mouseGeometry) {
                mouseEncoder.setSize(cols, rows, cellWidthPx, cellHeightPx)
                mouseGeometry = geometry
            }
            // Inside the screen: a drag that goes out of the window is reported at its edge.
            val x = (input.column * cellWidthPx).coerceIn(0f, cols * cellWidthPx - 1f)
            val y = (input.row * cellHeightPx).coerceIn(0f, rows * cellHeightPx - 1f)
            mouseEncoder.encode(input.toGhostty(x, y), input.anyButtonPressed)
        }
        if (bytes.isNotEmpty()) outgoing.put(bytes)
    }

    /**
     * Resizes the screen, reflowing what is on it, and tells the program.
     *
     * The size of a cell in pixels is what programs get when they query the size of the terminal in pixels.
     */
    public fun resize(cols: Int, rows: Int, cellWidthPx: Int, cellHeightPx: Int) {
        lock.withLock {
            if (closed.get()) return
            terminal.resize(cols, rows, cellWidthPx, cellHeightPx)
            this.cellWidthPx = cellWidthPx
            this.cellHeightPx = cellHeightPx
        }
        // pty4j only passes the size in cells to the program, not the one in pixels.
        process.winSize = WinSize(cols, rows)
        frameRequests.trySend(Unit)
    }

    /**
     * Writes [text] on the screen, as if the program had written it: a message of the application, such as
     * the one that says that the program exited. It is not sent to the program.
     *
     * Control sequences are interpreted, so a line ends with `\r\n`.
     */
    public fun writeToScreen(text: String) {
        lock.withLock {
            if (closed.get()) return
            terminal.vtWrite(text)
        }
        frameRequests.trySend(Unit)
    }

    /**
     * Changes the colors: what is on the screen is drawn again with them, and what programs write from now on
     * uses them. A color that a program set itself, with an OSC sequence, stays.
     *
     * @param theme `null` goes back to libghostty's own colors.
     */
    public fun setTheme(theme: TerminalTheme?) {
        lock.withLock {
            if (closed.get()) return
            terminal.applyTheme(theme)
            this.theme = theme
        }
        // The terminal flags every row as changed, so the rows are read again, with their new colors.
        frameRequests.trySend(Unit)
    }

    /**
     * Ends the program if it is still running, and releases everything.
     */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        logger.debug("Closing the session")

        // Ending the program ends the reader, and unblocks the writer.
        process.destroy()
        if (!process.waitFor(DESTROY_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            logger.warn("The program did not end within {}ms, killing it", DESTROY_TIMEOUT_MS)
            process.destroyForcibly()
        }
        outgoing.put(STOP)
        frameRequests.close()

        // The producer first: it uses the render state, which is freed below.
        runBlocking { producerScope.coroutineContext.job.cancelAndJoin() }
        producerDispatcher.close()

        reader.join(DESTROY_TIMEOUT_MS)
        writer.join(DESTROY_TIMEOUT_MS)
        if (reader.isAlive || writer.isAlive) logger.warn("The session threads did not end within {}ms", DESTROY_TIMEOUT_MS)

        // What the reader still does with the terminal is under the lock, and checks it is not closed.
        lock.withLock {
            selectionGesture.close()
            keyEncoder.close()
            mouseEncoder.close()
            renderState.close()
            terminal.close()
        }
    }

    private fun readOutput() {
        val buffer = ByteArray(READ_BUFFER_SIZE)
        try {
            val input = process.inputStream
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                val fed = lock.withLock {
                    if (closed.get()) {
                        false
                    } else {
                        terminal.vtWrite(buffer, 0, read)
                        true
                    }
                }
                if (!fed) break
                lastOutputNanos = System.nanoTime()
                frameRequests.trySend(Unit)
            }
        } catch (e: IOException) {
            // The pty is closed, which is how the program exiting shows on some platforms.
            logger.debug("The pty closed while reading", e)
        }
        val code = try {
            process.waitFor()
        } catch (_: InterruptedException) {
            return
        }
        logger.info("The program exited with code {}", code)
        frameRequests.trySend(Unit)
        exitCode.value = code
    }

    private fun writeInput() {
        val output = process.outputStream
        try {
            while (true) {
                val bytes = outgoing.take()
                if (bytes === STOP) break
                output.write(bytes)
                output.flush()
            }
        } catch (e: IOException) {
            // The program is gone: what is left to send has nowhere to go.
            logger.debug("The pty closed while writing", e)
        } catch (_: InterruptedException) {
        }
    }

    // Only the update reads the terminal, so only it is under the lock: reading the rows, which is the
    // costly part, does not hold up the thread that feeds the terminal.
    private fun produceFrame(): TerminalFrame {
        val tracking: Boolean
        val shape: GhosttyMouseShape
        lock.withLock {
            renderState.update(terminal)
            tracking = terminal.mouseTracking
            shape = terminal.mouseShape
        }

        val previous = rowCache
        val rows = renderState.readRows { id, dirty -> dirty || id !in previous }
        val terminalRows = rows.map { row ->
            val cells = row.cells ?: return@map previous.getValue(row.id)
            val rebuilt = cells.map { it.toTerminal() }
            // The dirty flag is conservative: libghostty also flags the row that the cursor leaves. A row
            // that did not change keeps its identity, which is what a renderer's cache relies on.
            val old = previous[row.id]
            if (old != null && old.cells == rebuilt) old else TerminalRow(TerminalRowId(row.id.bits0, row.id.bits1), rebuilt)
        }
        rowCache = rows.indices.associate { rows[it].id to terminalRows[it] }

        // libghostty keeps a selection on its cells when the program writes over them. What was selected is then
        // not what is there: the selection goes, which is what happens when what is selected changes, and not
        // when it is only redrawn as it was (the row is then the same object).
        val written = rows.indices.any { i ->
            val before = previous[rows[i].id]
            rows[i].selection != null && before != null && before !== terminalRows[i]
        }
        if (written) {
            lock.withLock { unselect() }
            frameRequests.trySend(Unit)
        }
        val selection = if (written || rows.all { it.selection == null }) emptyList() else rows.map { it.selection }

        val colors = renderState.colors
        val frame = TerminalFrame(
            cols = renderState.cols,
            rows = terminalRows,
            background = colors.background.toTerminal(),
            foreground = colors.foreground.toTerminal(),
            cursorColor = colors.cursor?.toTerminal(),
            cursor = renderState.cursor.toTerminal(),
            cursorTextColor = theme?.cursorText,
            boldColor = theme?.bold,
            mouseTracking = tracking,
            pointerShape = shape.toTerminal(),
            selection = selection,
            selectionColor = theme?.selection,
            selectedTextColor = theme?.selectedText,
        )
        renderState.clean()
        return frame
    }

    public companion object {
        /** What the terminal tells programs it is, and what they look up to know what it can do. */
        private const val TERM = "xterm-256color"

        // A program that floods the output must not keep the producer, and the lock it takes to read the
        // terminal, busy: more frames than a display shows are of no use.
        private val MIN_FRAME_INTERVAL = 1.seconds / 60

        // A frame is made once the program has been silent for this long, or after SETTLE_MAX anyway. The writes
        // of a redraw are 0.02 to 0.7 ms apart when zsh draws its completion menu, and the margin is for the
        // reader's thread being late. This is up to 3 ms of latency, which is what shows a redraw whole. They are
        // whole milliseconds, which is the resolution of delay on the producer's dispatcher.
        private val SETTLE_QUIET = 3.milliseconds
        private val SETTLE_MAX = 8.milliseconds

        // Two clicks are one double click if they are this close in space and in time. The time is the one that the
        // system gives for it, or half a second.
        private const val REPEAT_CLICK_DISTANCE_PX = 5.0
        private val REPEAT_CLICK_INTERVAL_NS = ((java.awt.Toolkit.getDefaultToolkit().getDesktopProperty("awt.multiClickInterval") as? Int) ?: 500) * 1_000_000L

        // What a cell is until the first resize tells it.
        private const val INITIAL_CELL_WIDTH_PX = 8
        private const val INITIAL_CELL_HEIGHT_PX = 16

        private const val READ_BUFFER_SIZE = 64 * 1024
        private const val DESTROY_TIMEOUT_MS = 1000L

        private val STOP = ByteArray(0)

        /**
         * Starts [command] in a pseudo-terminal of [cols] by [rows] cells.
         *
         * @param command By default the user's login shell.
         * @param environment Added to the environment of the current process, and overrides it. `TERM` and
         * `COLORTERM` are set to what the terminal supports, unless it says otherwise.
         * @param directory Where the program starts, by default the user's home.
         * @param cursorBlink Whether the cursor blinks unless the program says otherwise. It almost never does,
         * a shell included, and a terminal's cursor is expected to blink: without this it would not.
         * @param theme The colors, libghostty's own by default. [setTheme] changes them.
         */
        public fun start(
            cols: Int,
            rows: Int,
            command: List<String> = listOf(System.getenv("SHELL") ?: "/bin/zsh", "-l"),
            environment: Map<String, String> = emptyMap(),
            directory: String = System.getProperty("user.home"),
            cursorBlink: Boolean = true,
            theme: TerminalTheme? = null,
        ): TerminalSession {
            val env = HashMap(System.getenv())
            env["TERM"] = TERM
            env["COLORTERM"] = "truecolor"
            env.putAll(environment)

            logger.info("Starting {} in a {}x{} terminal, in {}", command, cols, rows, directory)
            val process = PtyProcessBuilder(command.toTypedArray())
                .setEnvironment(env)
                .setDirectory(directory)
                .setConsole(false)
                .setInitialColumns(cols)
                .setInitialRows(rows)
                .start()

            // Whatever fails after the program started must not leave it, or the native objects, behind.
            val toClose = ArrayDeque<AutoCloseable>()
            try {
                val terminal = GhosttyTerminal(cols, rows, terminfoName = TERM, defaultCursorBlink = cursorBlink).also { toClose.addFirst(it) }
                // Before the session, so that the first frame already has the colors.
                terminal.applyTheme(theme)
                val renderState = GhosttyRenderState().also { toClose.addFirst(it) }
                val keyEncoder = GhosttyKeyEncoder().also { toClose.addFirst(it) }
                val mouseEncoder = GhosttyMouseEncoder().also { toClose.addFirst(it) }
                val selectionGesture = GhosttySelectionGesture(terminal).also { toClose.addFirst(it) }
                return TerminalSession(process, terminal, renderState, keyEncoder, mouseEncoder, selectionGesture, theme).also { it.launch() }
            } catch (e: Throwable) {
                logger.error("Could not start the session", e)
                process.destroyForcibly()
                toClose.forEach { it.close() }
                throw e
            }
        }
    }
}
