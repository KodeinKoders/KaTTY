package app.katty.terminal

import app.katty.ghostty.GhosttyImage
import app.katty.ghostty.GhosttyImagePlacement
import app.katty.ghostty.GhosttyKeyEncoder
import app.katty.ghostty.GhosttyMode
import app.katty.ghostty.GhosttyMouseEncoder
import app.katty.ghostty.GhosttyMouseShape
import app.katty.ghostty.GhosttyProgressState
import app.katty.ghostty.GhosttyPromptEventKind
import app.katty.ghostty.GhosttyScreen
import app.katty.ghostty.GhosttyScrollViewport
import app.katty.ghostty.GhosttyScrollbar
import app.katty.ghostty.GhosttySelectionGesture
import app.katty.ghostty.GhosttyRenderState
import app.katty.ghostty.GhosttyRowId
import app.katty.ghostty.GhosttyTerminal
import com.pty4j.PtyProcess
import com.pty4j.PtyProcessBuilder
import com.pty4j.WinSize
import com.pty4j.unix.UnixPtyProcess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
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
    private val clipboardWrite: Boolean,
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

    // The images of the last frames, by id, which are only the ones whose placements exist: what is deleted frees its memory.
    // Only the frame producer uses it, once it runs. It is before the first frame, which uses it.
    private val imageCache = HashMap<Int, TerminalImage>()

    private val frameState = MutableStateFlow(produceFrame())

    private val exitCode = MutableStateFlow<Int?>(null)

    private val titleState = MutableStateFlow("")
    private val workingDirectoryState = MutableStateFlow<String?>(null)
    private val progressState = MutableStateFlow<TerminalProgress?>(null)

    // Events are for whoever is collecting at the time: one that nobody collects is dropped, and a collector that is slow loses
    // the oldest. The terminal calls back on the thread that reads the output, which must never wait for a collector.
    private val eventFlow = MutableSharedFlow<TerminalEvent>(extraBufferCapacity = EVENT_BUFFER, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    // Set by the terminal's callbacks, which must not read the terminal: the title and the directory are read once the write
    // that changed them has returned (see [publishAfterWrite]). Under the lock.
    private var titleChanged = false
    private var workingDirectoryChanged = false

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

    /** The title that the program set (OSC 0 or 2), empty when it did not. */
    public val title: StateFlow<String> get() = titleState

    /**
     * The directory that the shell reported (OSC 7), as a path, `null` when it did not. A shell does not report it on its own: see
     * `shellIntegration` of [start].
     */
    public val workingDirectory: StateFlow<String?> get() = workingDirectoryState

    /** What the program reports of its progress (OSC 9;4), `null` when it does not. */
    public val progress: StateFlow<TerminalProgress?> get() = progressState

    /** What happens besides the screen: see [TerminalEvent]. Nothing is replayed to a collector that comes late. */
    public val events: SharedFlow<TerminalEvent> get() = eventFlow

    private fun launch() {
        // What the terminal answers to the program's queries (where the cursor is, what the terminal is) is
        // written to its input like what is typed. It is called while the terminal is written to, under
        // the lock: it only enqueues.
        terminal.onWritePty = { outgoing.put(it) }
        installEffects()

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
        sendTyped {
            keyEncoder.setOptionsFrom(terminal)
            keyEncoder.encode(input.toGhostty())
        }
    }

    /**
     * Sends [text] to the program as it is, with none of the terminal's modes applied: what a shortcut stands for, such as
     * the escape sequence of the line editor that a key is mapped to. It is what is typed, like a key.
     */
    public fun sendText(text: String) {
        if (text.isEmpty()) return
        sendTyped { text.encodeToByteArray() }
    }

    /**
     * Sends to the program what [encode] makes, under the lock. It is what is typed: what was selected is not any more, since
     * the selection is of what was on the screen before, and the screen is brought back to the bottom.
     */
    private inline fun sendTyped(encode: () -> ByteArray) {
        val bytes = lock.withLock {
            if (closed.get()) return
            val bytes = encode()
            if (bytes.isNotEmpty()) {
                unselect()
                snapToBottom()
            }
            bytes
        }
        if (bytes.isNotEmpty()) {
            outgoing.put(bytes)
            frameRequests.trySend(Unit)
        }
    }

    /** Scrolls the viewport by [rows] rows through the history: up is negative. Nothing on the alternate screen, which has none. */
    public fun scrollBy(rows: Int) {
        scroll(GhosttyScrollViewport.Delta(rows.toLong()))
    }

    /** Scrolls a page: [direction] is `-1` for up and `1` for down. A page is the screen but a row, so that what was at the edge stays in view. */
    public fun scrollPage(direction: Int) {
        val rows = lock.withLock { if (closed.get()) return else terminal.rows }
        scrollBy(direction.coerceIn(-1, 1) * maxOf(1, rows - 1))
    }

    public fun scrollToTop() {
        scroll(GhosttyScrollViewport.Top)
    }

    public fun scrollToBottom() {
        scroll(GhosttyScrollViewport.Bottom)
    }

    /** Scrolls so that [row], counted from 0 at the top of the history, is the first row: [TerminalScrollbar.offset] is such a row. */
    public fun scrollToRow(row: Long) {
        scroll(GhosttyScrollViewport.Row(row))
    }

    /**
     * Scrolls the history to the [delta]th prompt before the one at the top of the viewport (up is negative), as the shell
     * marked them (OSC 133: see `shellIntegration` of [start]). It stops at the last one, and does nothing without marks.
     */
    public fun scrollToPrompt(delta: Int) {
        scroll(GhosttyScrollViewport.DeltaPrompt(delta.toLong()))
    }

    /**
     * What the wheel does when the program did not ask for the mouse: [rows] rows, up negative. It scrolls the history,
     * except on the alternate screen, which has none, where it sends that many arrow keys to the program, as xterm's
     * alternate scroll does, unless the program turned that off.
     */
    public fun scrollWheel(rows: Int) {
        if (rows == 0) return
        val bytes = lock.withLock {
            if (closed.get()) return
            if (terminal.activeScreen != GhosttyScreen.ALTERNATE) {
                terminal.scroll(GhosttyScrollViewport.Delta(rows.toLong()))
                return@withLock null
            }
            if (!terminal.isModeEnabled(GhosttyMode.ALT_SCROLL)) return@withLock null
            keyEncoder.setOptionsFrom(terminal)
            val key = if (rows < 0) TerminalKey.ARROW_UP else TerminalKey.ARROW_DOWN
            List(kotlin.math.abs(rows)) { keyEncoder.encode(KeyInput(key).toGhostty()) }.filter { it.isNotEmpty() }
        }
        bytes?.forEach(outgoing::put)
        frameRequests.trySend(Unit)
    }

    // What the terminal reports is called back while it is written to, on the reader's thread, under the lock: it only records,
    // and the title and the directory, which the terminal says may be read once the callback has returned, are read by
    // [publishAfterWrite].
    private fun installEffects() {
        terminal.onBell = { eventFlow.tryEmit(TerminalEvent.Bell) }
        terminal.onTitleChanged = { titleChanged = true }
        terminal.onPwdChanged = { workingDirectoryChanged = true }
        terminal.onReset = {
            // The terminal does not report the title and the directory that a reset clears.
            titleChanged = true
            workingDirectoryChanged = true
            progressState.value = null
        }
        terminal.onNotification = { title, body -> eventFlow.tryEmit(TerminalEvent.Notification(title, body)) }
        terminal.onProgress = { report ->
            progressState.value = when (report.state) {
                GhosttyProgressState.REMOVE -> null
                GhosttyProgressState.SET -> TerminalProgress(TerminalProgressState.SET, report.percent)
                GhosttyProgressState.ERROR -> TerminalProgress(TerminalProgressState.ERROR, report.percent)
                GhosttyProgressState.INDETERMINATE -> TerminalProgress(TerminalProgressState.INDETERMINATE, report.percent)
                GhosttyProgressState.PAUSE -> TerminalProgress(TerminalProgressState.PAUSE, report.percent)
            }
        }
        terminal.onSemanticPrompt = { step ->
            when (step.kind) {
                GhosttyPromptEventKind.PROMPT_START -> eventFlow.tryEmit(TerminalEvent.PromptStarted)
                GhosttyPromptEventKind.OUTPUT_START -> eventFlow.tryEmit(TerminalEvent.CommandStarted(step.command.ifEmpty { null }))
                GhosttyPromptEventKind.COMMAND_END -> eventFlow.tryEmit(TerminalEvent.CommandFinished(step.exitCode))
                // What follows is what the user types: nothing for the host.
                GhosttyPromptEventKind.INPUT_START -> Unit
            }
        }
        terminal.onClipboardWrite = { location, text ->
            clipboardWrite && eventFlow.tryEmit(TerminalEvent.ClipboardWrite(location.toTerminal(), text))
        }
    }

    // Under the lock, after a write to the terminal.
    private fun publishAfterWrite() {
        if (titleChanged) {
            titleChanged = false
            titleState.value = terminal.title
        }
        if (workingDirectoryChanged) {
            workingDirectoryChanged = false
            workingDirectoryState.value = decodeWorkingDirectory(terminal.pwd)
        }
    }

    /**
     * The link at [column] and [row] of the screen as the last frame shows it, or `null` when there is none. It is an address that
     * a program marked (OSC 8), or one written in the text, which is looked for in the row (one that goes on onto the next row is
     * not found).
     */
    public fun linkAt(column: Int, row: Int): TerminalLink? {
        val cells = frameState.value.rows.getOrNull(row)?.cells ?: return null
        if (column !in cells.indices) return null
        return lock.withLock {
            if (closed.get()) return null
            marked(cells, column, row) ?: written(cells, column, row)
        }
    }

    // A link that the program marked: the cells around the one that have the same address.
    private fun marked(cells: List<TerminalCell>, column: Int, row: Int): TerminalLink? {
        if (!cells[column].hyperlink) return null
        val url = terminal.hyperlinkAt(column, row) ?: return null
        fun same(c: Int) = cells[c].hyperlink && terminal.hyperlinkAt(c, row) == url
        var start = column
        while (start > 0 && same(start - 1)) start--
        var end = column
        while (end < cells.lastIndex && same(end + 1)) end++
        return TerminalLink(url, row, start..end)
    }

    // A link that is written in the text of the row.
    private fun written(cells: List<TerminalCell>, column: Int, row: Int): TerminalLink? {
        val text = StringBuilder()
        // The column of each character: the second half of a wide character has none of its own.
        val columns = ArrayList<Int>()
        for ((c, cell) in cells.withIndex()) {
            if (cell.width == TerminalCellWidth.SPACER_TAIL || cell.width == TerminalCellWidth.SPACER_HEAD) continue
            val part = cell.text.ifEmpty { " " }
            text.append(part)
            repeat(part.length) { columns += c }
        }
        val index = columns.indexOf(column).takeIf { it >= 0 } ?: return null
        val range = findUrl(text.toString(), index) ?: return null
        return TerminalLink(text.substring(range.first, range.last + 1), row, columns[range.first]..columns[range.last])
    }

    private fun scroll(to: GhosttyScrollViewport) {
        lock.withLock {
            if (closed.get()) return
            terminal.scroll(to)
        }
        frameRequests.trySend(Unit)
    }

    // What is typed brings the screen back, where the program's cursor is. Under the lock.
    private fun snapToBottom() {
        val position = terminal.scrollbar
        if (position.offset + position.len < position.total) terminal.scroll(GhosttyScrollViewport.Bottom)
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
    public fun selectionDrag(column: Float, row: Float, rectangle: Boolean = false): TerminalAutoscroll {
        val autoscroll = lock.withLock {
            if (closed.get()) return TerminalAutoscroll.NONE
            // The position is not kept inside the screen, or the gesture would not know that it is past an edge.
            val at = locate(column, row, clampPosition = false)
            val selected = selectionGesture.drag(
                at.column, at.row, at.xPx, at.yPx,
                cols = terminal.cols,
                cellWidthPx = cellWidthPx,
                screenHeightPx = terminal.rows * cellHeightPx,
                rectangle = rectangle,
            )
            if (selected) hasSelection = true
            selectionGesture.autoscroll().toTerminal()
        }
        frameRequests.trySend(Unit)
        return autoscroll
    }

    /**
     * What a drag that is past the top or the bottom does at each tick (the caller ticks every so often, with the last
     * position of the pointer): the viewport is scrolled by a row, and the selection goes with it.
     *
     * @return Which way it scrolled, [TerminalAutoscroll.NONE] when the drag is not past an edge any more or is over.
     */
    public fun selectionAutoscroll(column: Float, row: Float, rectangle: Boolean = false): TerminalAutoscroll {
        val direction = lock.withLock {
            if (closed.get()) return TerminalAutoscroll.NONE
            val direction = selectionGesture.autoscroll().toTerminal()
            if (direction == TerminalAutoscroll.NONE) return@withLock direction
            // The tick scrolls the viewport by a row itself, and extends the selection to the edge.
            val at = locate(column, row, clampPosition = false)
            val edgeRow = if (direction == TerminalAutoscroll.UP) 0 else terminal.rows - 1
            val selected = selectionGesture.tick(
                at.column, edgeRow, at.xPx, at.yPx,
                cols = terminal.cols,
                cellWidthPx = cellWidthPx,
                screenHeightPx = terminal.rows * cellHeightPx,
                rectangle = rectangle,
            )
            if (selected) hasSelection = true
            direction
        }
        frameRequests.trySend(Unit)
        return direction
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

    private fun locate(column: Float, row: Float, clampPosition: Boolean = true): Located {
        val cols = terminal.cols
        val rows = terminal.rows
        val x = column.coerceIn(0f, cols - 0.001f)
        val y = row.coerceIn(0f, rows - 0.001f)
        // The position that is not clamped is how a drag tells that it is past an edge.
        val xPx = (if (clampPosition) x else column) * cellWidthPx
        val yPx = (if (clampPosition) y else row) * cellHeightPx
        return Located(x.toInt(), y.toInt(), xPx.toDouble(), yPx.toDouble())
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
     * Pastes [text] into the program, as the modes it set ask for: in the markers of bracketed paste if it asked for them,
     * else with the newlines as carriage returns, and without the control bytes that could be taken for commands. It is
     * what is typed: the screen is brought back to the bottom, and what was selected is not any more.
     */
    public fun paste(text: String) {
        if (text.isEmpty()) return
        sendTyped { terminal.encodePaste(text) }
    }

    /** The text that is selected, for the caller to copy, or `null` when nothing is. */
    public fun selectedText(): String? = lock.withLock {
        if (closed.get()) null else terminal.selectedText()?.takeIf { it.isNotEmpty() }
    }

    /**
     * Resizes the screen, reflowing what is on it, and tells the program.
     *
     * The size of a cell in pixels is what programs get when they query the size of the terminal in pixels,
     * whether they ask the terminal (`CSI 14 t`, `CSI 16 t`) or read the size of the pty.
     */
    public fun resize(cols: Int, rows: Int, cellWidthPx: Int, cellHeightPx: Int) {
        lock.withLock {
            if (closed.get()) return
            terminal.resize(cols, rows, cellWidthPx, cellHeightPx)
            this.cellWidthPx = cellWidthPx
            this.cellHeightPx = cellHeightPx
        }
        // pty4j only passes the size in cells to the program, not the one in pixels: the session sets the whole size,
        // and leaves pty4j's way for what is not a Unix pty or what the ioctl refused.
        val fd = (process as? UnixPtyProcess)?.pty?.masterFD
        val done = fd != null && setPtyWindowSize(fd, cols, rows, cols * cellWidthPx, rows * cellHeightPx)
        if (!done) process.winSize = WinSize(cols, rows)
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
            publishAfterWrite()
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
                        publishAfterWrite()
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
        val alternate: Boolean
        val shape: GhosttyMouseShape
        val position: GhosttyScrollbar
        val placements: List<GhosttyImagePlacement>
        val fresh = HashMap<Int, GhosttyImage>()
        val cellWidth: Int
        val cellHeight: Int
        lock.withLock {
            renderState.update(terminal)
            tracking = terminal.mouseTracking
            alternate = terminal.activeScreen == GhosttyScreen.ALTERNATE
            shape = terminal.mouseShape
            position = terminal.scrollbar
            cellWidth = cellWidthPx
            cellHeight = cellHeightPx

            // Where the images are changes when the screen scrolls, so it is read for every frame, but only while there are any.
            placements = if (terminal.kittyGeneration == 0L) emptyList() else terminal.kittyPlacements()
            // The pixels are the terminal's, valid until it changes: what is new is copied here, and made into RGBA after.
            for (placement in placements) {
                if (!placement.visible || placement.isVirtual || placement.imageId in fresh) continue
                val known = imageCache[placement.imageId]
                if (known != null && known.generation == placement.imageGeneration) continue
                terminal.kittyImage(placement.imageId)?.let { fresh[placement.imageId] = it }
            }
        }
        val images = imagePlacements(placements, fresh, cellWidth, cellHeight)

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
            // Nothing to scroll when everything is on the screen: no scrollbar.
            scrollbar = if (position.total > position.len) TerminalScrollbar(position.total, position.offset, position.len) else null,
            images = images,
            alternateScreen = alternate,
        )
        renderState.clean()
        return frame
    }

    // What is shown of the images, in cells, in the order that it is drawn in.
    private fun imagePlacements(
        placements: List<GhosttyImagePlacement>,
        fresh: Map<Int, GhosttyImage>,
        cellWidth: Int,
        cellHeight: Int,
    ): List<TerminalImagePlacement> {
        imageCache.keys.retainAll(placements.mapTo(HashSet()) { it.imageId })
        for ((id, image) in fresh) {
            imageCache[id] = TerminalImage(id, image.generation, image.width, image.height, image.toRgba())
        }
        if (placements.isEmpty()) return emptyList()

        return placements
            .filter { it.visible && !it.isVirtual && it.imageId in imageCache }
            .sortedBy { it.z }
            .map {
                TerminalImagePlacement(
                    image = imageCache.getValue(it.imageId),
                    left = it.viewportCol + it.xOffset / cellWidth.toFloat(),
                    top = it.viewportRow + it.yOffset / cellHeight.toFloat(),
                    width = it.pixelWidth / cellWidth.toFloat(),
                    height = it.pixelHeight / cellHeight.toFloat(),
                    sourceX = it.sourceX,
                    sourceY = it.sourceY,
                    sourceWidth = it.sourceWidth,
                    sourceHeight = it.sourceHeight,
                    layer = when {
                        it.z < Int.MIN_VALUE / 2 -> TerminalImageLayer.BELOW_BACKGROUND
                        it.z < 0 -> TerminalImageLayer.BELOW_TEXT
                        else -> TerminalImageLayer.ABOVE_TEXT
                    },
                )
            }
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

        // How much memory the images of the Kitty graphics protocol may take: the terminal does not store any without a limit.
        // It is Ghostty's own default.
        private const val IMAGE_STORAGE_LIMIT_BYTES = 320_000_000L

        // How much memory the history may take, which is Ghostty's own default.
        private const val SCROLLBACK_MAX_BYTES = 10_000_000L

        // How many events may wait for a collector that is slow.
        private const val EVENT_BUFFER = 64

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
         * @param shellIntegration Whether to make zsh and bash report their prompts (OSC 133) and their directory (OSC 7), which they
         * do not on their own: the [workingDirectory] and the [TerminalEvent.PromptStarted], [TerminalEvent.CommandStarted] and
         * [TerminalEvent.CommandFinished] events, and [scrollToPrompt], depend on it. The user's own startup files are still read.
         * Any other shell, and anything but a login or an interactive shell, is started as it is.
         * @param clipboardWrite Whether a program may put text in the clipboard (OSC 52), which then comes as a
         * [TerminalEvent.ClipboardWrite]. It is refused when `false`.
         * @param clipboardReader What a program that reads the clipboard (OSC 52) gets: the text it returns, or a refusal for
         * `null`. Programs cannot read it without one, and it is the host's to give: what is on the clipboard may be secret. It runs on
         * the thread that reads the program's output, which waits for it: it must be quick, and must not call back into the session.
         */
        public fun start(
            cols: Int,
            rows: Int,
            command: List<String> = listOf(System.getenv("SHELL") ?: "/bin/zsh", "-l"),
            environment: Map<String, String> = emptyMap(),
            directory: String = System.getProperty("user.home"),
            cursorBlink: Boolean = true,
            theme: TerminalTheme? = null,
            clipboardWrite: Boolean = true,
            clipboardReader: ((TerminalClipboardLocation) -> String?)? = null,
            shellIntegration: Boolean = true,
        ): TerminalSession {
            val env = HashMap(System.getenv())
            env["TERM"] = TERM
            env["COLORTERM"] = "truecolor"
            env.putAll(environment)
            val argv = if (shellIntegration) ShellIntegration.apply(command, env) else command

            logger.info("Starting {} in a {}x{} terminal, in {}", argv, cols, rows, directory)
            val process = PtyProcessBuilder(argv.toTypedArray())
                .setEnvironment(env)
                .setDirectory(directory)
                .setConsole(false)
                .setInitialColumns(cols)
                .setInitialRows(rows)
                .start()

            // Whatever fails after the program started must not leave it, or the native objects, behind.
            val toClose = ArrayDeque<AutoCloseable>()
            try {
                val terminal = GhosttyTerminal(
                    cols, rows, terminfoName = TERM, defaultCursorBlink = cursorBlink, scrollbackMaxBytes = SCROLLBACK_MAX_BYTES,
                    imageStorageLimitBytes = IMAGE_STORAGE_LIMIT_BYTES,
                ).also { toClose.addFirst(it) }
                // Before the session, so that the first frame already has the colors.
                terminal.applyTheme(theme)
                // The terminal does not know how big a cell is until it is told, and an image that a program sends before the first
                // resize would be placed on cells of no size.
                terminal.resize(cols, rows, INITIAL_CELL_WIDTH_PX, INITIAL_CELL_HEIGHT_PX)
                val renderState = GhosttyRenderState().also { toClose.addFirst(it) }
                val keyEncoder = GhosttyKeyEncoder().also { toClose.addFirst(it) }
                val mouseEncoder = GhosttyMouseEncoder().also { toClose.addFirst(it) }
                val selectionGesture = GhosttySelectionGesture(terminal).also { toClose.addFirst(it) }
                terminal.setClipboardReader(clipboardReader?.let { reader -> { location -> reader(location.toTerminal()) } })
                return TerminalSession(process, terminal, renderState, keyEncoder, mouseEncoder, selectionGesture, theme, clipboardWrite)
                    .also { it.launch() }
            } catch (e: Throwable) {
                logger.error("Could not start the session", e)
                process.destroyForcibly()
                toClose.forEach { it.close() }
                throw e
            }
        }
    }
}
