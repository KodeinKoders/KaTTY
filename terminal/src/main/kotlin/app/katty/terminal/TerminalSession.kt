package app.katty.terminal

import app.katty.ghostty.GhosttyBuildInfo
import app.katty.ghostty.GhosttyCompressionMode
import app.katty.ghostty.GhosttyCompressionResult
import app.katty.ghostty.GhosttyImage
import app.katty.ghostty.GhosttyImagePlacement
import app.katty.ghostty.GhosttyKeyEncoder
import app.katty.ghostty.GhosttyMode
import app.katty.ghostty.GhosttyMouseEncoder
import app.katty.ghostty.GhosttyMouseShape
import app.katty.ghostty.GhosttyProgramKind
import app.katty.ghostty.GhosttyProgramState
import app.katty.ghostty.GhosttyProgressState
import app.katty.ghostty.GhosttyPromptEventKind
import app.katty.ghostty.GhosttyScreen
import app.katty.ghostty.GhosttyScrollViewport
import app.katty.ghostty.GhosttySnapshotException
import app.katty.ghostty.GhosttySearch
import app.katty.ghostty.GhosttySearchStatus
import app.katty.ghostty.GhosttyViewportMatch
import app.katty.ghostty.GhosttyScrollbar
import app.katty.ghostty.GhosttySelectionGesture
import app.katty.ghostty.GhosttyRenderState
import app.katty.ghostty.GhosttyRowId
import app.katty.ghostty.GhosttyTerminal
import app.katty.ghostty.GhosttyTrackedRef
import com.pty4j.PtyProcess
import com.pty4j.PtyProcessBuilder
import com.pty4j.WinSize
import com.pty4j.unix.UnixPtyProcess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.io.IOException
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

    // What the program was last told about the focus, or would have been if it asked: a session starts not focused, like xterm's
    // terminal, which tells a program that turns the mode on nothing until the focus changes. Under the lock.
    private var lastFocused = false

    // The search, which is made when the first query is set, and what it was asked for. Under the lock.
    private var searcher: GhosttySearch? = null
    private var searchQuery = ""

    private val searchStateFlow = MutableStateFlow(TerminalSearchState())

    // How big a cell is and how many there are, for what the stream of the program says in cells or percent: read by the reader.
    @Volatile
    private var geometry = CellGeometry(INITIAL_CELL_WIDTH_PX, INITIAL_CELL_HEIGHT_PX, 80, 24)

    // Where the output of the last command starts, as the shell reported it: it follows its row as the terminal scrolls and prunes.
    // Under the lock.
    private var outputStart: GhosttyTrackedRef? = null

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

    // Whether frames are produced: see [setVisible].
    private val visibleState = MutableStateFlow(true)

    private val titleState = MutableStateFlow("")
    private val workingDirectoryState = MutableStateFlow<String?>(null)
    private val progressState = MutableStateFlow<TerminalProgress?>(null)
    private val programStatusStore = ProgramStatusStore()
    private val programStatusState = MutableStateFlow<List<TerminalProgramStatus>>(emptyList())

    // Events are for whoever is collecting at the time: one that nobody collects is dropped, and a collector that is slow loses
    // the oldest. The terminal calls back on the thread that reads the output, which must never wait for a collector.
    private val eventFlow = MutableSharedFlow<TerminalEvent>(extraBufferCapacity = EVENT_BUFFER, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    // Set by the terminal's callbacks, which must not read the terminal: the title and the directory are read once the write
    // that changed them has returned (see [publishAfterWrite]). Under the lock.
    private var titleChanged = false
    private var workingDirectoryChanged = false

    // One coroutine at a time, so that what only the producer touches (the render state, the caches) is never used by two, and on the threads
    // that every session shares rather than one of its own. IO and not Default: closing a session waits for the producer, and many
    // sessions that close at the same time must not wait for each other on the workers that the producers need.
    @OptIn(ExperimentalCoroutinesApi::class)
    private val producerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

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

    /** Whether frames are produced: see [setVisible]. */
    public val visible: StateFlow<Boolean> get() = visibleState

    /**
     * Tells the session whether anybody looks at it. A session that is not visible does not produce frames, which is the work that
     * a hidden terminal has no use for (up to 60 times per second, reading every row that changed), and it lets go of what it holds
     * for the next frame: [frames] is a blank frame of the same size, with no text, no cursor and no images, and so is what is derived
     * from the last frame, such as [linkAt]. Its history is compressed once it is quiet, which loses nothing: reading it decompresses it.
     *
     * Everything else goes on: the program's output is read and fed to the terminal, keys, the mouse and the answers to the
     * program's queries are sent, and [title], [workingDirectory], [progress], [events] and [exited] are published. Showing the session
     * again produces one frame at once, with everything that happened, which is read again whole: it is the cost of a change of theme.
     * It is shown when it starts.
     *
     * A host that keeps a hidden [app.katty.terminal.TerminalSession] composed sees the blank frame.
     */
    public fun setVisible(visible: Boolean) {
        if (visibleState.value == visible) return
        visibleState.value = visible
        // What happened while it was hidden is shown by the frame that this asks for, even if nothing asked for one since.
        if (visible) frameRequests.trySend(Unit)
    }

    /** The title that the program set (OSC 0 or 2), empty when it did not. */
    public val title: StateFlow<String> get() = titleState

    /**
     * The directory that the shell reported (OSC 7), as a path, `null` when it did not. A shell does not report it on its own: see
     * `shellIntegration` of [start].
     */
    public val workingDirectory: StateFlow<String?> get() = workingDirectoryState

    /** What the program reports of its progress (OSC 9;4), `null` when it does not. */
    public val progress: StateFlow<TerminalProgress?> get() = progressState

    /**
     * What programs report of what they do (OSC 7501), by id. It is what they said: the title and the message are theirs. A shell that
     * starts a new prompt, or a program that exits, ends what was `working` or `blocked`; what is `done` or `error` stays until the program
     * or the host (see [clearProgramStatus]) removes it.
     */
    public val programStatus: StateFlow<List<TerminalProgramStatus>> get() = programStatusState

    /** Removes the program status records that [id] and the ones beneath it have, all of them for an empty [id]: the host saw them. */
    public fun clearProgramStatus(id: String = "") {
        if (programStatusStore.report(id, true, TerminalProgramState.IDLE, TerminalProgramKind.NONE, null, "", "", "")) {
            programStatusState.value = programStatusStore.snapshot()
        }
    }

    /** What happens besides the screen: see [TerminalEvent]. Nothing is replayed to a collector that comes late. */
    public val events: SharedFlow<TerminalEvent> get() = eventFlow

    private fun launch() {
        // What the terminal answers to the program's queries (where the cursor is, what the terminal is) is
        // written to its input like what is typed. It is called while the terminal is written to, under
        // the lock: it only enqueues.
        terminal.onWritePty = { outgoing.put(it) }
        installEffects()
        // A terminal that was restored has them, and says nothing of it: nothing was listening.
        lock.withLock {
            geometry = CellGeometry(cellWidthPx, cellHeightPx, terminal.cols, terminal.rows)
            titleState.value = terminal.title
            workingDirectoryState.value = decodeWorkingDirectory(terminal.pwd)
        }

        producerScope.launch {
            for (request in frameRequests) {
                // A request that comes while the session is hidden waits here, and the ones after it merge into the next one: showing
                // it again makes one frame, which is not followed by another for the request that was already served.
                if (!visibleState.value) {
                    visibleState.first { it }
                    frameRequests.tryReceive()
                }
                val started = TimeSource.Monotonic.markNow()
                settle(started)
                // Hidden while it waited: the frame is for when it is shown.
                if (!visibleState.value) {
                    visibleState.first { it }
                    frameRequests.tryReceive()
                }
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
        // What a hidden session lets go of. It cannot be in the loop above, which waits for a request: this one acts as soon as the session is
        // hidden. Both run one at a time on the producer's dispatcher, so neither interrupts the other half way.
        producerScope.launch {
            visibleState.collectLatest { visible -> if (!visible) releaseWhileHidden() }
        }
        reader.start()
        writer.start()
    }

    // What the session does while it is hidden: the frame and what is kept for the next one are dropped, since nobody looks, and
    // the history is compressed, which is lost to nobody: reading it decompresses it. It ends when the session is shown.
    private suspend fun releaseWhileHidden() {
        frameState.value = blankFrame(frameState.value)
        rowCache = emptyMap()
        imageCache.clear()

        var seen = compressionActivity() ?: return
        delay(HIDDEN_IDLE)
        while (true) {
            // Something was written: it is not an idle moment, which is when compressing is for.
            val now = compressionActivity() ?: return
            if (now != seen) {
                seen = now
                delay(HIDDEN_IDLE)
                continue
            }
            when (compressOnce() ?: return) {
                GhosttyCompressionResult.UNSUPPORTED -> return
                GhosttyCompressionResult.PENDING -> delay(COMPRESSION_STEP_PAUSE)
                GhosttyCompressionResult.COMPLETE -> {
                    // Nothing to do until the terminal changes: it is looked at now and then.
                    seen = compressionActivity() ?: return
                    while (compressionActivity() == seen) delay(HIDDEN_POLL)
                    seen = compressionActivity() ?: return
                    delay(HIDDEN_IDLE)
                }
            }
        }
    }

    private fun compressionActivity(): Long? = lock.withLock { if (closed.get()) null else terminal.compressionActivity() }

    // A bounded amount of compression, under the lock for that long.
    private fun compressOnce(): GhosttyCompressionResult? =
        lock.withLock { if (closed.get()) null else terminal.compress(GhosttyCompressionMode.INCREMENTAL) }

    // What a terminal that nobody sees is: the colors and the size, nothing on it.
    private fun blankFrame(last: TerminalFrame): TerminalFrame {
        val blank = TerminalRow(TerminalRowId(0, 0), emptyList())
        return TerminalFrame(
            cols = last.cols,
            rows = List(last.rows.size) { blank },
            background = last.background,
            foreground = last.foreground,
            cursorColor = last.cursorColor,
            cursor = TerminalCursor(x = null, y = null, visible = false, blinking = false, shape = last.cursor.shape),
            cursorTextColor = last.cursorTextColor,
            boldColor = last.boldColor,
            alternateScreen = last.alternateScreen,
        )
    }

    /** How much native memory the session holds: its history, its screens and its images. */
    public fun memoryUsage(): TerminalMemoryUsage = lock.withLock {
        if (closed.get()) return TerminalMemoryUsage(0, 0, 0)
        val usage = terminal.memoryUsage()
        TerminalMemoryUsage(usage.residentBytes, usage.compressedBytes, usage.imageBytes)
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
        terminal.onProgramStatus = { report ->
            val changed = programStatusStore.report(
                id = report.id,
                clear = report.state == GhosttyProgramState.CLEAR,
                state = when (report.state) {
                    GhosttyProgramState.WORKING -> TerminalProgramState.WORKING
                    GhosttyProgramState.DONE -> TerminalProgramState.DONE
                    GhosttyProgramState.BLOCKED -> TerminalProgramState.BLOCKED
                    GhosttyProgramState.ERROR -> TerminalProgramState.ERROR
                    else -> TerminalProgramState.IDLE
                },
                kind = when (report.kind) {
                    GhosttyProgramKind.PERMISSION -> TerminalProgramKind.PERMISSION
                    GhosttyProgramKind.QUESTION -> TerminalProgramKind.QUESTION
                    GhosttyProgramKind.AUTH -> TerminalProgramKind.AUTH
                    GhosttyProgramKind.NONE -> TerminalProgramKind.NONE
                },
                progress = report.progress,
                app = report.app,
                title = report.title,
                message = report.message,
            )
            if (changed) programStatusState.value = programStatusStore.snapshot()
        }
        terminal.onResizeRequest = { eventFlow.tryEmit(TerminalEvent.ResizeRequested(it.columns, it.rows)) }
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
                GhosttyPromptEventKind.PROMPT_START -> {
                    // What was being worked on is over: the shell is asking for the next thing.
                    if (programStatusStore.promptStarted()) programStatusState.value = programStatusStore.snapshot()
                    eventFlow.tryEmit(TerminalEvent.PromptStarted)
                }
                GhosttyPromptEventKind.OUTPUT_START -> {
                    // Here, where the screen is up to date: it is where the output starts, which it would not be after the write.
                    outputStart?.close()
                    outputStart = terminal.trackCursor()
                    eventFlow.tryEmit(TerminalEvent.CommandStarted(step.command.ifEmpty { null }))
                }
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
     * a program marked (OSC 8), or one written in the text. One that goes on onto the next row, or comes from the one before it,
     * which its line was too long for, is found whole, on its [TerminalLink.segments], up to [LINK_ROWS] rows from the one that was
     * asked. A marked link is the cells around the one that have the same address, since libghostty does not say which link is which
     * (its `id=` is not readable): two links of the same address that touch are one.
     */
    public fun linkAt(column: Int, row: Int): TerminalLink? {
        val rows = frameState.value.rows
        val cells = rows.getOrNull(row)?.cells ?: return null
        if (column !in cells.indices) return null
        return lock.withLock {
            if (closed.get()) return null
            marked(rows, column, row) ?: written(rows, column, row)
        }
    }

    // The last cell of a row that is a character of it: a wide character that did not fit leaves a blank one at the end.
    private fun lastColumn(cells: List<TerminalCell>) =
        if (cells.last().width == TerminalCellWidth.SPACER_HEAD) cells.lastIndex - 1 else cells.lastIndex

    // A link that the program marked: the cells around the one that have the same address, and the rows that it goes on onto.
    private fun marked(rows: List<TerminalRow>, column: Int, row: Int): TerminalLink? {
        if (!rows[row].cells[column].hyperlink) return null
        val url = terminal.hyperlinkAt(column, row) ?: return null
        fun same(r: Int, c: Int) = rows[r].cells[c].hyperlink && terminal.hyperlinkAt(c, r) == url
        fun left(r: Int, from: Int): Int {
            var start = from
            while (start > 0 && same(r, start - 1)) start--
            return start
        }
        fun right(r: Int, from: Int): Int {
            val last = lastColumn(rows[r].cells)
            var end = from
            while (end < last && same(r, end + 1)) end++
            return end
        }

        var start = left(row, column)
        var end = right(row, column)
        val segments = ArrayDeque<TerminalLinkSegment>()
        segments += TerminalLinkSegment(row, start..end)
        // It starts at the left edge, and the row above goes on into this one, and ends in the link: the link is there too.
        var top = row
        while (start == 0 && top > 0 && top > row - LINK_ROWS && rows[top - 1].wrapped) {
            val above = top - 1
            val last = lastColumn(rows[above].cells)
            if (!same(above, last)) break
            start = left(above, last)
            segments.addFirst(TerminalLinkSegment(above, start..last))
            top = above
        }
        // And the same the other way.
        var bottom = row
        while (end == lastColumn(rows[bottom].cells) && rows[bottom].wrapped && bottom + 1 < rows.size && bottom < row + LINK_ROWS) {
            val below = bottom + 1
            if (!same(below, 0)) break
            end = right(below, 0)
            segments += TerminalLinkSegment(below, 0..end)
            bottom = below
        }
        return TerminalLink(url, segments.toList())
    }

    // A link that is written in the text: of the row, and of the rows that it wraps onto, which are one line.
    private fun written(rows: List<TerminalRow>, column: Int, row: Int): TerminalLink? {
        var first = row
        while (first > 0 && first > row - LINK_ROWS && rows[first - 1].wrapped) first--
        var last = row
        while (last + 1 < rows.size && last < row + LINK_ROWS && rows[last].wrapped) last++

        val text = StringBuilder()
        // The row and the column of each character: the second half of a wide character has none of its own.
        val rowOf = ArrayList<Int>()
        val columnOf = ArrayList<Int>()
        for (r in first..last) {
            for ((c, cell) in rows[r].cells.withIndex()) {
                if (cell.width == TerminalCellWidth.SPACER_TAIL || cell.width == TerminalCellWidth.SPACER_HEAD) continue
                val part = cell.text.ifEmpty { " " }
                text.append(part)
                repeat(part.length) {
                    rowOf += r
                    columnOf += c
                }
            }
        }
        var index = -1
        for (i in rowOf.indices) if (rowOf[i] == row && columnOf[i] == column) { index = i; break }
        if (index < 0) return null
        val range = findUrl(text.toString(), index) ?: return null

        // The cells of each row that the characters of the address are on.
        val segments = ArrayList<TerminalLinkSegment>()
        var i = range.first
        while (i <= range.last) {
            val segmentRow = rowOf[i]
            val startColumn = columnOf[i]
            var endColumn = startColumn
            while (i <= range.last && rowOf[i] == segmentRow) {
                endColumn = columnOf[i]
                i++
            }
            segments += TerminalLinkSegment(segmentRow, startColumn..endColumn)
        }
        return TerminalLink(text.substring(range.first, range.last + 1), segments)
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

    /**
     * Selects all the text of the history and the screen. It is the terminal's own selection, as the mouse makes it: it is in the
     * frames, [selectedText] gives its text, and what is typed for the program unselects it.
     */
    public fun selectAll() {
        lock.withLock {
            if (closed.get()) return
            selectionGesture.reset()
            if (terminal.selectAll()) hasSelection = true
        }
        frameRequests.trySend(Unit)
    }

    /**
     * Removes the history, and keeps what is on the screen: what a program does with `CSI 3 J`, which the program does not see here.
     * The selection goes with it, since the rows that it was on may be gone.
     */
    public fun clearScrollback() {
        lock.withLock {
            if (closed.get()) return
            selectionGesture.reset()
            unselect()
            terminal.vtWrite("\u001b[3J")
            publishAfterWrite()
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
     * Limits the memory that the history may take to [bytes], about: libghostty removes whole pages of it, and does so at once
     * when the limit is lowered, which is how a host takes memory back from a terminal that it does not use. `0` keeps no history.
     */
    public fun setScrollbackLimit(bytes: Long) {
        require(bytes >= 0) { "A limit is not negative: $bytes" }
        lock.withLock {
            if (closed.get()) return
            terminal.setScrollbackMaxBytes(bytes)
        }
        frameRequests.trySend(Unit)
    }

    /**
     * Limits the memory that the images of the Kitty graphics protocol may take to [bytes]. The images that no longer fit are
     * removed at once, from the screen too, and `0` removes them all and turns the images off.
     */
    public fun setImageStorageLimit(bytes: Long) {
        require(bytes >= 0) { "A limit is not negative: $bytes" }
        lock.withLock {
            if (closed.get()) return
            terminal.setImageStorageLimit(bytes)
        }
        frameRequests.trySend(Unit)
    }

    /**
     * Tells the session whether the keys that are typed go to this terminal: a program that asked to know (mode 1004: vim, tmux)
     * is sent `ESC [ I` when it gains the focus and `ESC [ O` when it loses it. It is told when it changes, and only if it asked.
     * What is typed or selected is not touched.
     */
    public fun setFocused(focused: Boolean) {
        val bytes = lock.withLock {
            if (closed.get() || focused == lastFocused) return
            lastFocused = focused
            terminal.encodeFocus(focused)
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

    /** The search for a text in the history and the screen. */
    public val search: TerminalSearch = TerminalSearch(this)

    internal val searchState: StateFlow<TerminalSearchState> get() = searchStateFlow

    internal fun setSearchQuery(query: String) {
        lock.withLock {
            if (closed.get()) return
            searchQuery = query
            if (query.isEmpty()) {
                searcher?.setNeedle("")
                searchStateFlow.value = TerminalSearchState()
            } else {
                val active = searcher ?: GhosttySearch(terminal).also { searcher = it }
                active.setNeedle(query)
                searchStateFlow.value = TerminalSearchState(query = query, searching = true)
            }
        }
        frameRequests.trySend(Unit)
    }

    // Toward older content is libghostty's next: up the screen.
    internal fun moveSearch(towardOlder: Boolean) {
        lock.withLock {
            if (closed.get() || searchQuery.isEmpty()) return
            val active = searcher ?: return
            if (towardOlder) active.selectNext() else active.selectPrevious()
        }
        frameRequests.trySend(Unit)
    }

    /**
     * What the terminal is now, to be restored as a new session ([start] with `snapshot`): its screens and its history, the cursor, the modes,
     * the title and the directory. See [TerminalSnapshot] for what is not in it.
     *
     * It blocks the program's output for as long as it takes to encode the history, which is as long as the history is: call it off the UI
     * thread. A session that is hidden has its history compressed, which makes it cheaper. If the program happens to be in the middle of a
     * very long escape sequence (more than 64 KiB: the transfer of an image) it tries again a few times.
     *
     * @throws TerminalSnapshotException If it could not be done.
     */
    public fun snapshot(): TerminalSnapshot {
        var attempt = 0
        while (true) {
            try {
                return lock.withLock {
                    if (closed.get()) throw TerminalSnapshotException("The session is closed")
                    TerminalSnapshot(
                        cols = terminal.cols,
                        rows = terminal.rows,
                        title = titleState.value,
                        workingDirectory = workingDirectoryState.value,
                        createdAt = System.currentTimeMillis(),
                        fingerprint = GhosttyBuildInfo.fingerprint(),
                        data = terminal.snapshot(),
                    )
                }
            } catch (e: GhosttySnapshotException) {
                if (++attempt >= SNAPSHOT_ATTEMPTS) throw TerminalSnapshotException("Could not take a snapshot: ${e.message}", e)
                Thread.sleep(SNAPSHOT_RETRY_MS)
            }
        }
    }

    /** How many rows there are in the history and the screen. */
    public fun rowCount(): Long = lock.withLock {
        if (closed.get()) 0 else terminal.totalRows
    }

    /**
     * The text of the rows [firstRow] to [lastRow] (the last one by default), both included, in the history and the screen: row 0 is the
     * top of the history, in the space of [TerminalScrollbar.offset] and [scrollToRow]. The viewport does not move. A line that wraps is
     * joined when [unwrap], and the blanks that end a line are dropped.
     *
     * It is read under the lock that the program's output is written under, and finding a row walks the history: for a very large
     * one, read it in pieces rather than all at once, or the output waits.
     *
     * @throws IllegalArgumentException If the rows are not there.
     */
    public fun readText(firstRow: Long = 0, lastRow: Long? = null, unwrap: Boolean = true): String = lock.withLock {
        if (closed.get()) return ""
        terminal.readRows(firstRow, lastRow ?: (terminal.totalRows - 1), unwrap)
    }

    /** The text of the rows that are in view. */
    public fun viewportText(unwrap: Boolean = true): String = lock.withLock {
        if (closed.get()) return ""
        val position = terminal.scrollbar
        terminal.readRows(position.offset, position.offset + position.len - 1, unwrap)
    }

    /** The text of the screen, where the program's cursor is, whatever part of the history is in view. */
    public fun screenText(unwrap: Boolean = true): String = lock.withLock {
        if (closed.get()) return ""
        val total = terminal.totalRows
        terminal.readRows(total - terminal.rows, total - 1, unwrap)
    }

    /**
     * The output of the last command that the shell started, or `null` if it started none or its output is not in the history any
     * more. It needs the shell to mark its prompts and its commands (OSC 133): see `shellIntegration` of [start]. What a command that is
     * still running has written is there too.
     */
    public fun lastCommandOutput(unwrap: Boolean = true): String? = lock.withLock {
        if (closed.get()) return null
        outputStart?.let { terminal.commandOutput(it, unwrap) }
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
            geometry = CellGeometry(cellWidthPx, cellHeightPx, cols, rows)
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

        reader.join(DESTROY_TIMEOUT_MS)
        writer.join(DESTROY_TIMEOUT_MS)
        if (reader.isAlive || writer.isAlive) logger.warn("The session threads did not end within {}ms", DESTROY_TIMEOUT_MS)

        // What the reader still does with the terminal is under the lock, and checks it is not closed.
        lock.withLock {
            outputStart?.close()
            outputStart = null
            searcher?.close()
            searcher = null
            selectionGesture.close()
            keyEncoder.close()
            mouseEncoder.close()
            renderState.close()
            terminal.close()
        }
    }

    private fun readOutput() {
        val buffer = ByteArray(READ_BUFFER_SIZE)
        // Only this thread uses it.
        val translator = OutputTranslator(geometry = { geometry })
        try {
            val input = process.inputStream
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                // What the program says about the blinking of the cursor is not for the terminal: see OutputTranslator.
                val length = translator.filter(buffer, read)
                val fed = lock.withLock {
                    if (closed.get()) {
                        false
                    } else {
                        if (length > 0) terminal.vtWrite(translator.output, 0, length)
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
        if (programStatusStore.programExited()) programStatusState.value = programStatusStore.snapshot()
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
        var found: SearchFound? = null
        lock.withLock {
            renderState.update(terminal)
            // As of this moment, with the rows that are read for it: what is found is where it is on this frame.
            found = searchOnFrame()
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
                // The image of a virtual placement is drawn where placeholder cells are, so it is needed whether or not it is visible.
                if ((!placement.visible && !placement.isVirtual) || placement.imageId in fresh) continue
                val known = imageCache[placement.imageId]
                if (known != null && known.generation == placement.imageGeneration) continue
                terminal.kittyImage(placement.imageId)?.let { fresh[placement.imageId] = it }
            }
        }
        val realImages = imagePlacements(placements, fresh, cellWidth, cellHeight)

        val previous = rowCache
        val rows = renderState.readRows { id, dirty -> dirty || id !in previous }
        val terminalRows = rows.map { row ->
            val cells = row.cells ?: return@map previous.getValue(row.id)
            val rebuilt = cells.map { it.toTerminal() }
            // The dirty flag is conservative: libghostty also flags the row that the cursor leaves. A row
            // that did not change keeps its identity, which is what a renderer's cache relies on.
            val old = previous[row.id]
            if (old != null && old.cells == rebuilt && old.wrapped == row.wrap && old.hasPlaceholder == row.hasPlaceholder) {
                old
            } else {
                TerminalRow(TerminalRowId(row.id.bits0, row.id.bits1), rebuilt, row.wrap, row.hasPlaceholder)
            }
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

        // What the placeholder cells show, under the text; with the images that programs placed, by layer.
        val images = if (terminalRows.any { it.hasPlaceholder }) {
            (realImages + placeholderPlacements(terminalRows, placements, cellWidth, cellHeight)).sortedBy { it.layer.ordinal }
        } else {
            realImages
        }

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
            searchMatches = found?.let { searchMatches(it, renderState.cols) } ?: emptyList(),
        )
        renderState.clean()
        found?.let { result ->
            searchStateFlow.value = result.state
            // A search goes through the history a bounded amount at a time.
            if (result.status != GhosttySearchStatus.COMPLETE) frameRequests.trySend(Unit)
        }
        return frame
    }

    // What the search has for one frame.
    private class SearchFound(
        val state: TerminalSearchState,
        val status: GhosttySearchStatus,
        val matches: List<GhosttyViewportMatch>,
        val selected: GhosttyViewportMatch?,
    )

    // Under the lock.
    private fun searchOnFrame(): SearchFound? {
        val active = searcher ?: return null
        if (searchQuery.isEmpty()) return null
        active.feed()
        val status = active.tick()
        val total = active.totalMatches
        val index = active.selectedIndex
        return SearchFound(
            // The newest is 0 for libghostty, and the last for a person.
            state = TerminalSearchState(searchQuery, total, index?.let { total - it }, status != GhosttySearchStatus.COMPLETE),
            status = status,
            matches = active.viewportMatches(),
            selected = active.selectedMatch(),
        )
    }

    // A match that wraps is one for each of its rows.
    private fun searchMatches(found: SearchFound, cols: Int): List<TerminalSearchMatch> =
        found.matches.flatMap { match ->
            (match.startRow..match.endRow).map { row ->
                val first = if (row == match.startRow) match.startColumn else 0
                val last = if (row == match.endRow) match.endColumn else cols - 1
                TerminalSearchMatch(row, first..last, current = match == found.selected)
            }
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

    // The parts of images that unicode placeholders show: one image for each run of them, where the cells are. The grid that an image is fitted
    // in is the one of its virtual placement.
    private fun placeholderPlacements(
        rows: List<TerminalRow>,
        placements: List<GhosttyImagePlacement>,
        cellWidth: Int,
        cellHeight: Int,
    ): List<TerminalImagePlacement> {
        val virtual = placements.filter { it.isVirtual }
        return placeholderRuns(rows).mapNotNull { run ->
            val image = imageCache[run.imageId] ?: return@mapNotNull null
            // The exact placement if the cells name one, else the image's own, which is the lowest of its placements.
            val target = if (run.placementId > 0) {
                virtual.firstOrNull { it.imageId == run.imageId && it.placementId == run.placementId }
            } else {
                virtual.filter { it.imageId == run.imageId }.minByOrNull { it.placementId }
            } ?: return@mapNotNull null
            val gridRows = if (target.rows > 0) target.rows else (image.height + cellHeight - 1) / cellHeight
            val gridColumns = if (target.columns > 0) target.columns else (image.width + cellWidth - 1) / cellWidth
            if (gridRows <= 0 || gridColumns <= 0) return@mapNotNull null
            val place = placeholderPlacement(run, image.width, image.height, gridColumns, gridRows, cellWidth, cellHeight) ?: return@mapNotNull null
            TerminalImagePlacement(
                image = image,
                left = run.startColumn + (place.offsetXPx / cellWidth).toFloat(),
                top = run.row + (place.offsetYPx / cellHeight).toFloat(),
                width = (place.widthPx / cellWidth).toFloat(),
                height = (place.heightPx / cellHeight).toFloat(),
                sourceX = place.sourceX,
                sourceY = place.sourceY,
                sourceWidth = place.sourceWidth,
                sourceHeight = place.sourceHeight,
                // Ghostty draws them under the text.
                layer = TerminalImageLayer.BELOW_TEXT,
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

        // A hidden session starts to compress its history after this long without output, looks at whether it has changed this
        // often once it has compressed what it could, and pauses between the steps of a pass.
        private val HIDDEN_IDLE = 1.seconds
        private val HIDDEN_POLL = 1.seconds
        private val COMPRESSION_STEP_PAUSE = 5.milliseconds

        // A snapshot that fails because the program is in the middle of a long sequence is tried again this many times, this far apart.
        private const val SNAPSHOT_ATTEMPTS = 5
        private const val SNAPSHOT_RETRY_MS = 20L

        // What takes the old program's state off a restored terminal, so that the new one finds what a new terminal is: the scrolling region reset (which moves the cursor home, so the cursor is saved and put back), the modes that a program sets
        // and a shell does not know about off (the mouse in every protocol and format, focus reports, bracketed paste, synchronized output,
        // application cursor keys and keypad, insert mode, autowrap on, the cursor shown), the character sets ASCII, the Kitty
        // keyboard flags popped, the cursor style the default, the attributes cleared, and a new row to start on. Not a soft reset (DECSTR):
        // it homes the cursor, and the new program would write over what the old one showed.
        private const val RESTORE_RESET =
            "\u001b7\u001b[r\u001b8" +
                "\u001b[?9l\u001b[?1000l\u001b[?1001l\u001b[?1002l\u001b[?1003l\u001b[?1004l\u001b[?1005l\u001b[?1006l\u001b[?1015l\u001b[?1016l" +
                "\u001b[?2004l\u001b[?2026l\u001b[?1l\u001b>\u001b[?66l\u001b[4l\u001b[?7h\u001b[?25h\u001b[?69l" +
                "\u001b(B\u001b)B\u000f\u001b[<99u\u001b[0 q\u001b[0m\r\n"

        // How many events may wait for a collector that is slow.
        private const val EVENT_BUFFER = 64

        // How many rows from the one that is asked a link is looked for on: a line that wraps onto thousands of rows is not read for each
        // move of the pointer.
        private const val LINK_ROWS = 16

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
         * a shell included, and a terminal's cursor is expected to blink: without this it would not. A program says otherwise
         * with DECSCUSR (`CSI Ps SP q`); **DEC mode 12 is ignored**, as Ghostty does when the blink is set: Emacs sets it when it
         * exits, and the cursor of the shell would stay as it left it.
         * @param cursorShape The shape of the cursor until the program sets one (DECSCUSR), and when it resets it: a block by default.
         * Only [TerminalCursorShape.BAR], [TerminalCursorShape.BLOCK] and [TerminalCursorShape.UNDERLINE]: the hollow block is how an
         * unfocused block is drawn.
         * @param scrollbackMaxBytes About how much memory the history may take, 10 MB by default (Ghostty's own). `0` keeps none.
         * [setScrollbackLimit] changes it.
         * @param imageStorageLimitBytes How much memory the images of the Kitty graphics protocol may take, 320 MB by default
         * (Ghostty's own). `0` turns the images off. [setImageStorageLimit] changes it.
         * @param theme The colors, libghostty's own by default. [setTheme] changes them.
         * @param shellIntegration Whether to make zsh and bash report their prompts (OSC 133) and their directory (OSC 7), which they
         * do not on their own: the [workingDirectory] and the [TerminalEvent.PromptStarted], [TerminalEvent.CommandStarted] and
         * [TerminalEvent.CommandFinished] events, and [scrollToPrompt], depend on it. The user's own startup files are still read.
         * Any other shell, and anything but a login or an interactive shell, is started as it is.
         * @param directory Where the program starts: the directory of [snapshot] if it has one that is still there, else the user's home.
         * @param snapshot What the terminal was ([TerminalSession.snapshot]): the new session starts with its screen and its history, the
         * title and the directory, and [command] runs below them: a **new** program, since a process cannot be saved. The size is [cols] by
         * [rows], to which the old content is reflowed, and the old program's modes are taken off, so that a snapshot of an editor gives a
         * shell with a plain mouse. Not restored: the images of the Kitty protocol, the selection, the viewport.
         * @throws TerminalSnapshotException If [snapshot] was made by another build of libghostty, or is damaged.
         * @param advertiseSixel Whether the answer to a device attributes query says that the terminal draws Sixel images, which is how
         * programs that probe (chafa, img2sixel) choose it by themselves.
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
            directory: String? = null,
            cursorBlink: Boolean = true,
            cursorShape: TerminalCursorShape = TerminalCursorShape.BLOCK,
            scrollbackMaxBytes: Long = SCROLLBACK_MAX_BYTES,
            imageStorageLimitBytes: Long = IMAGE_STORAGE_LIMIT_BYTES,
            theme: TerminalTheme? = null,
            clipboardWrite: Boolean = true,
            clipboardReader: ((TerminalClipboardLocation) -> String?)? = null,
            shellIntegration: Boolean = true,
            snapshot: TerminalSnapshot? = null,
            advertiseSixel: Boolean = true,
        ): TerminalSession {
            if (snapshot != null && !snapshot.isRestorable) {
                throw TerminalSnapshotException("The snapshot was made by another build of libghostty, which cannot read it: start a new session")
            }
            val startIn = directory ?: snapshot?.workingDirectory?.takeIf { java.io.File(it).isDirectory } ?: System.getProperty("user.home")
            require(cursorShape != TerminalCursorShape.BLOCK_HOLLOW) { "The hollow block is not a shape that a program sets" }
            require(scrollbackMaxBytes >= 0) { "A limit is not negative: $scrollbackMaxBytes" }
            require(imageStorageLimitBytes >= 0) { "A limit is not negative: $imageStorageLimitBytes" }
            val env = HashMap(System.getenv())
            env["TERM"] = TERM
            env["COLORTERM"] = "truecolor"
            env.putAll(environment)
            val argv = if (shellIntegration) ShellIntegration.apply(command, env) else command

            logger.info("Starting {} in a {}x{} terminal, in {}{}", argv, cols, rows, startIn, if (snapshot != null) ", from a snapshot" else "")
            val process = PtyProcessBuilder(argv.toTypedArray())
                .setEnvironment(env)
                .setDirectory(startIn)
                .setConsole(false)
                .setInitialColumns(cols)
                .setInitialRows(rows)
                .start()

            // Whatever fails after the program started must not leave it, or the native objects, behind.
            val toClose = ArrayDeque<AutoCloseable>()
            try {
                val terminal = if (snapshot == null) {
                    GhosttyTerminal(
                        cols, rows, terminfoName = TERM, defaultCursorBlink = cursorBlink, scrollbackMaxBytes = scrollbackMaxBytes,
                        imageStorageLimitBytes = imageStorageLimitBytes, defaultCursorStyle = cursorShape.toGhostty(),
                    )
                } else {
                    try {
                        GhosttyTerminal.restore(
                            snapshot.data, terminfoName = TERM, defaultCursorBlink = cursorBlink, scrollbackMaxBytes = scrollbackMaxBytes,
                            imageStorageLimitBytes = imageStorageLimitBytes, defaultCursorStyle = cursorShape.toGhostty(),
                        )
                    } catch (e: GhosttySnapshotException) {
                        throw TerminalSnapshotException("Could not restore the snapshot: ${e.message}", e)
                    }
                }.also {
                    it.advertiseSixel = advertiseSixel
                    toClose.addFirst(it)
                }
                // Before the session, so that the first frame already has the colors.
                terminal.applyTheme(theme)
                // The terminal does not know how big a cell is until it is told, and an image that a program sends before the first
                // resize would be placed on cells of no size.
                terminal.resize(cols, rows, INITIAL_CELL_WIDTH_PX, INITIAL_CELL_HEIGHT_PX)
                // The size is the host's, and the content was reflowed to it just now. The old program is gone, and what it had set with it.
                if (snapshot != null) {
                    // Only if it is there: leaving the alternate screen from the primary one puts the cursor back where it never was saved, at home.
                    if (terminal.activeScreen == GhosttyScreen.ALTERNATE) terminal.vtWrite("\u001b[?1049l")
                    // Origin mode is only reset if it is set, and the cursor put back: resetting it moves the cursor home.
                    if (terminal.isModeEnabled(GhosttyMode.ORIGIN)) {
                        val column = terminal.cursorColumn
                        val row = terminal.cursorRow
                        terminal.vtWrite("\u001b[?6l\u001b[${row + 1};${column + 1}H")
                    }
                    terminal.vtWrite(RESTORE_RESET)
                }
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
