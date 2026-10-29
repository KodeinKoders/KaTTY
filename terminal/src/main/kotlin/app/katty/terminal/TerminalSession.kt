package app.katty.terminal

import app.katty.ghostty.GhosttyBuildInfo
import app.katty.ghostty.GhosttyKeyEncoder
import app.katty.ghostty.GhosttyMode
import app.katty.ghostty.GhosttyMouseEncoder
import app.katty.ghostty.GhosttyProgramKind
import app.katty.ghostty.GhosttyProgramState
import app.katty.ghostty.GhosttyProgressState
import app.katty.ghostty.GhosttyPromptEventKind
import app.katty.ghostty.GhosttyPromptKind
import app.katty.ghostty.GhosttyScreen
import app.katty.ghostty.GhosttyScrollViewport
import app.katty.ghostty.GhosttySnapshotException
import app.katty.ghostty.GhosttySelectionGesture
import app.katty.ghostty.GhosttyRenderState
import app.katty.ghostty.GhosttyTerminal
import app.katty.ghostty.GhosttyTrackedRef
import com.pty4j.PtyProcess
import com.pty4j.PtyProcessBuilder
import com.pty4j.WinSize
import com.pty4j.unix.UnixPtyProcess
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.nio.file.Path
import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.withLock
import kotlin.concurrent.write
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

private val logger = LoggerFactory.getLogger(TerminalSession::class.java)

// The hosts whose directories were ignored, so that each is said once.
private val ignoredHosts = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

// A directory that a shell reports for a machine that is not this one is dropped, which is what a shell over `ssh` needs; but it is also what a
// shell does that says a name that is not the system's, and nothing else shows it: the terminal just does not follow the shell.
private fun logIgnoredDirectory(reported: String) {
    val host = reported.removePrefix("file://").substringBefore('/')
    if (ignoredHosts.add(host)) logger.info("Ignoring the directory that a shell reported for the host '{}': this machine is '{}'", host, localHostName())
}

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

    // Guards the master side of the pty, which [close] closes: see [withPty].
    private val ptyLock = ReentrantReadWriteLock()
    private var ptyClosed = false

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

    // Where the output of the last command starts, as the shell reported it: it follows its row as the terminal scrolls and prunes.
    // Under the lock.
    private var outputStart: GhosttyTrackedRef? = null

    // The colors that a frame has besides the ones libghostty resolves. Written under the lock, with the
    // colors of the terminal, and read by the producer.
    @Volatile
    private var theme: TerminalTheme? = theme

    // What goes to the program: the keys, and what the terminal answers to its queries.
    private val outgoing = LinkedBlockingQueue<ByteArray>()

    // What makes the frames, and the searches it asks for the matches of each. It makes the first frame now: what it reads of the session is
    // above.
    private val producer = FrameProducer(terminal, renderState, lock, { closed.get() }, object : FrameProducer.Session {
        override val cellWidthPx: Int get() = this@TerminalSession.cellWidthPx
        override val cellHeightPx: Int get() = this@TerminalSession.cellHeightPx
        override val theme: TerminalTheme? get() = this@TerminalSession.theme
        override fun unselect() = this@TerminalSession.unselect()
        override fun readingPassword(): Boolean = withPty(false) { fd -> isPtyReadingPassword(fd) }
    })

    private val sessionSearch: SessionSearch get() = producer.search

    // What is typed when the shell first waits for a command (see [TypedCommand]): sent once, by whichever of the prompt marks or the timer
    // gets there first. Fish has no mark of the end of the prompt, so it is sent at the start of it.
    private val pendingInput = AtomicReference<ByteArray?>()

    @Volatile
    private var pendingAtPromptStart = false

    // Without prompt marks nothing says when the shell is ready: a second is what a shell takes to start in the usual case.
    private fun typeAfterQuiet() {
        producer.scope.launch {
            delay(TYPE_WITHOUT_MARKS_DELAY)
            typePending()
        }
    }

    // The marks that the integration makes may still not come: the user's files may remove its hooks (`precmd_functions=()`), or the shell
    // may start another program in its place. Long after the start, and once the shell has said nothing for a second, what is still waiting
    // is typed, if the shell still has the terminal.
    private fun typeIfTheMarksDoNotCome(shell: String) {
        producer.scope.launch {
            delay(MARKS_EXPECTED_DELAY)
            while (pendingInput.get() != null) {
                val quiet = (System.nanoTime() - producer.lastOutputNanos).nanoseconds
                if (quiet < TYPE_WITHOUT_MARKS_DELAY) {
                    delay(TYPE_WITHOUT_MARKS_DELAY - quiet)
                    continue
                }
                val program = foregroundProcessName()
                if (program == shell) {
                    logger.warn("The shell marked no prompt: typing what was waiting for it")
                    typePending()
                } else if (pendingInput.getAndSet(null) != null) {
                    logger.warn("The shell marked no prompt, and {} has the terminal: not typing what was waiting", program)
                }
            }
        }
    }

    private fun typePending() {
        val bytes = pendingInput.getAndSet(null) ?: return
        send(bytes)
        producer.request()
    }

    // Whether the shell is reading a command line, between the marks that it makes at the end of the prompt and at the start of the output
    // (OSC 133 B and C): written under the lock, by the reader.
    private var inputActive = false

    private val exitCode = MutableStateFlow<Int?>(null)

    private val titleState = MutableStateFlow("")
    private val workingDirectoryState = MutableStateFlow<String?>(null)
    private val progressState = MutableStateFlow<TerminalProgress?>(null)
    private val programStatusStore = ProgramStatusStore()

    // Events are for whoever is collecting at the time: one that nobody collects is dropped, and a collector that is slow loses
    // the oldest. The terminal calls back on the thread that reads the output, which must never wait for a collector.
    private val eventFlow = MutableSharedFlow<TerminalEvent>(extraBufferCapacity = EVENT_BUFFER, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    // Set by the terminal's callbacks, which must not read the terminal: the title and the directory are read once the write
    // that changed them has returned (see [publishAfterWrite]). Under the lock.
    private var titleChanged = false
    private var workingDirectoryChanged = false

    private val reader = Thread(::readOutput, "katty-terminal-reader").apply { isDaemon = true }
    private val writer = Thread(::writeInput, "katty-terminal-writer").apply { isDaemon = true }

    /**
     * What the screen looks like. A new frame is published whenever it changes, at most 60 times per
     * second: what the program writes in between is merged, and a frame waits for the output to pause for a
     * moment (3 milliseconds, at most eight), so that a redraw is not shown half-way.
     */
    public val frames: StateFlow<TerminalFrame> get() = producer.frames

    /**
     * The exit code of the program, once it has exited.
     */
    public val exited: StateFlow<Int?> get() = exitCode

    /** Whether frames are produced: see [setVisible]. */
    public val visible: StateFlow<Boolean> get() = producer.visible

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
        producer.setVisible(visible)
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
    public val programStatus: StateFlow<List<TerminalProgramStatus>> get() = programStatusStore.state

    /** Removes the program status records that [id] and the ones beneath it have, all of them for an empty [id]: the host saw them. */
    public fun clearProgramStatus(id: String = "") {
        programStatusStore.report(id, true, TerminalProgramState.IDLE, TerminalProgramKind.NONE, null, "", "", "")
    }

    /** What happens besides the screen: see [TerminalEvent]. Nothing is replayed to a collector that comes late. */
    public val events: SharedFlow<TerminalEvent> get() = eventFlow

    private fun launch() {
        // What the terminal answers to the program's queries (where the cursor is, what the terminal is) is
        // written to its input like what is typed. It is called while the terminal is written to, under
        // the lock: it only enqueues.
        terminal.onWritePty = { send(it) }
        installEffects()
        // A terminal that was restored has them, and says nothing of it: nothing was listening.
        lock.withLock {
            titleState.value = terminal.title
            workingDirectoryState.value = decodeWorkingDirectory(terminal.pwd)
        }

        producer.start()
        reader.start()
        writer.start()
    }

    /** How much native memory the session holds: its history, its screens and its images. */
    public fun memoryUsage(): TerminalMemoryUsage = whileOpen(TerminalMemoryUsage(0, 0, 0)) {
        val usage = terminal.memoryUsage()
        TerminalMemoryUsage(usage.residentBytes, usage.compressedBytes, usage.imageBytes)
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
            send(bytes)
            producer.request()
        }
    }

    /** Runs [block] under the lock unless the session is closed (it then gives [closedValue]), and asks for a frame after it: what changes what is shown. */
    private inline fun <T> mutate(closedValue: T, block: () -> T): T {
        val result = lock.withLock {
            if (closed.get()) return closedValue
            block()
        }
        producer.request()
        return result
    }

    private inline fun mutate(block: () -> Unit) = mutate(Unit, block)

    /** Runs [block] under the lock unless the session is closed, when it gives [closedValue]: what reads the terminal and changes nothing that is shown. */
    private inline fun <T> whileOpen(closedValue: T, block: () -> T): T = lock.withLock {
        if (closed.get()) closedValue else block()
    }

    /** Scrolls the viewport by [rows] rows through the history: up is negative. Nothing on the alternate screen, which has none. */
    public fun scrollBy(rows: Int) {
        scroll(GhosttyScrollViewport.Delta(rows.toLong()))
    }

    /** Scrolls a page: [direction] is `-1` for up and `1` for down. A page is the screen but a row, so that what was at the edge stays in view. */
    public fun scrollPage(direction: Int) {
        val rows = whileOpen(null) { terminal.rows } ?: return
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
        producer.request()
    }

    // What the terminal reports is called back while it is written to, on the reader's thread, under the lock: it only records,
    // and the title and the directory, which the terminal says may be read once the callback has returned, are read by
    // [publishAfterWrite].
    private fun installEffects() {
        terminal.onRenderHold = { held ->
            producer.hold(held)
        }
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
            programStatusStore.report(
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
                // Another line of the same prompt (a continuation, the right side): the command line is still being typed.
                GhosttyPromptEventKind.PROMPT_START if step.promptKind != GhosttyPromptKind.PRIMARY -> Unit
                GhosttyPromptEventKind.PROMPT_START -> {
                    inputActive = false
                    // What was being worked on is over: the shell is asking for the next thing.
                    programStatusStore.promptStarted()
                    eventFlow.tryEmit(TerminalEvent.PromptStarted)
                    if (pendingAtPromptStart) typePending()
                }
                GhosttyPromptEventKind.OUTPUT_START -> {
                    inputActive = false
                    // Here, where the screen is up to date: it is where the output starts, which it would not be after the write.
                    outputStart?.close()
                    outputStart = terminal.trackCursor()
                    eventFlow.tryEmit(TerminalEvent.CommandStarted(step.command.ifEmpty { null }))
                }
                GhosttyPromptEventKind.COMMAND_END -> {
                    inputActive = false
                    eventFlow.tryEmit(TerminalEvent.CommandFinished(step.exitCode))
                }
                // What follows is what the user types, until the command runs: nothing for the host, but a click may move the cursor in it.
                GhosttyPromptEventKind.INPUT_START -> {
                    inputActive = true
                    typePending()
                }
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
            // A directory of another machine (a shell over ssh) is not this one's: the terminal keeps the one it had.
            val pwd = terminal.pwd
            if (!isRemoteDirectory(pwd)) {
                workingDirectoryState.value = decodeWorkingDirectory(pwd)
            } else {
                logIgnoredDirectory(pwd)
            }
        }
    }

    /**
     * The link at [column] and [row] of the screen as the last frame shows it, or `null` when there is none. It is an address that
     * a program marked (OSC 8), or one written in the text. One that goes on onto the next row, or comes from the one before it,
     * which its line was too long for, is found whole, on its [TerminalLink.segments], up to 16 rows from the one that was
     * asked. A marked link is the cells around the one that have the same address, since libghostty does not say which link is which
     * (its `id=` is not readable): two links of the same address that touch are one.
     */
    public fun linkAt(column: Int, row: Int): TerminalLink? {
        val rows = producer.frames.value.rows
        val cells = rows.getOrNull(row)?.cells ?: return null
        if (column !in cells.indices) return null
        return whileOpen(null) { markedLink(rows, column, row, terminal::hyperlinkAt) ?: writtenLink(rows, column, row) }
    }

    private fun scroll(to: GhosttyScrollViewport): Unit = mutate { terminal.scroll(to) }

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
    public fun selectionPress(column: Float, row: Float): Unit = mutate {
        val at = locate(column, row)
        val selected = selectionGesture.press(
            at.column, at.row, at.xPx, at.yPx,
            timeNs = System.nanoTime(),
            repeatDistancePx = REPEAT_CLICK_DISTANCE_PX,
            repeatIntervalNs = REPEAT_CLICK_INTERVAL_NS,
        )
        if (selected) hasSelection = true else unselect()
    }

    /**
     * The pointer moves with the button held, which extends the selection to the cell at [column] and [row].
     *
     * @param rectangle Whether to select a block of cells instead of a flow of text.
     */
    public fun selectionDrag(column: Float, row: Float, rectangle: Boolean = false): TerminalAutoscroll = mutate(TerminalAutoscroll.NONE) {
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

    /**
     * What a drag that is past the top or the bottom does at each tick (the caller ticks every so often, with the last
     * position of the pointer): the viewport is scrolled by a row, and the selection goes with it.
     *
     * @return Which way it scrolled, [TerminalAutoscroll.NONE] when the drag is not past an edge any more or is over.
     */
    public fun selectionAutoscroll(column: Float, row: Float, rectangle: Boolean = false): TerminalAutoscroll = mutate(TerminalAutoscroll.NONE) {
        val direction = selectionGesture.autoscroll().toTerminal()
        if (direction == TerminalAutoscroll.NONE) return@mutate direction
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

    /**
     * The button goes up, which ends the selection.
     *
     * @return The text that is selected, for the caller to copy, or `null` if nothing is selected.
     */
    public fun selectionRelease(column: Float, row: Float): String? = mutate(null) {
        val at = locate(column, row)
        selectionGesture.release(at.column, at.row)
        terminal.selectedText()?.takeIf { it.isNotEmpty() }
    }

    /** Unselects what is selected. */
    public fun clearSelection(): Unit = mutate {
        selectionGesture.reset()
        unselect()
    }

    /**
     * Selects all the text of the history and the screen. It is the terminal's own selection, as the mouse makes it: it is in the
     * frames, [selectedText] gives its text, and what is typed for the program unselects it.
     */
    public fun selectAll(): Unit = mutate {
        selectionGesture.reset()
        if (terminal.selectAll()) hasSelection = true
    }

    /**
     * Removes the history, and keeps what is on the screen: what a program does with `CSI 3 J`, which the program does not see here.
     * The selection goes with it, since the rows that it was on may be gone.
     */
    public fun clearScrollback(): Unit = mutate {
        selectionGesture.reset()
        unselect()
        terminal.vtWrite("\u001b[3J")
        publishAfterWrite()
    }

    /**
     * Clears the screen as well as the history, for the user who wants to start from a clean terminal (what ⌘K does in Terminal and iTerm2, and
     * Ghostty's `clear_screen`):
     * * at the prompt of a shell that marks its prompts (see `shellIntegration` of [start]) the screen and the history are erased, and Control-L is
     * sent so that the shell draws its prompt, and what was typed on it, again at the top;
     * * while a program runs, the history and the rows above the cursor are erased and the rest of the screen is the program's, who is not told;
     * * on the alternate screen (an editor, a pager) nothing is done: it is the program's, and it has no history.
     */
    public fun clearScreen() {
        val atPrompt = lock.withLock {
            if (closed.get() || terminal.activeScreen == GhosttyScreen.ALTERNATE) return
            selectionGesture.reset()
            unselect()
            if (inputActive) {
                terminal.vtWrite("\u001b[H\u001b[2J\u001b[3J")
            } else if (terminal.isModeEnabled(GhosttyMode.ORIGIN)) {
                // The rows are counted from the program's scrolling region, and the cursor cannot be put back without setting or resetting the
                // mode, which homes it: only the history goes.
                terminal.vtWrite("\u001b[3J")
            } else {
                // The cursor goes back where it was, where the program expects it: not with DECSC and DECRC, which would replace the cursor that
                // the program itself saved.
                val row = terminal.cursorRow
                val column = terminal.cursorColumn
                val erase = StringBuilder("\u001b[3J")
                for (above in 1..row) erase.append("\u001b[$above;1H\u001b[2K")
                erase.append("\u001b[${row + 1};${column + 1}H")
                terminal.vtWrite(erase.toString())
            }
            snapToBottom()
            publishAfterWrite()
            inputActive
        }
        producer.request()
        if (atPrompt) send(byteArrayOf(CONTROL_L))
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
        if (bytes.isNotEmpty()) send(bytes)
    }

    /**
     * Limits the memory that the history may take to [bytes], about: libghostty removes whole pages of it, and does so at once
     * when the limit is lowered, which is how a host takes memory back from a terminal that it does not use. `0` keeps no history.
     */
    public fun setScrollbackLimit(bytes: Long) {
        require(bytes >= 0) { "A limit is not negative: $bytes" }
        mutate { terminal.setScrollbackMaxBytes(bytes) }
    }

    /**
     * Limits the memory that the images of the Kitty graphics protocol may take to [bytes]. The images that no longer fit are
     * removed at once, from the screen too, and `0` removes them all and turns the images off.
     */
    public fun setImageStorageLimit(bytes: Long) {
        require(bytes >= 0) { "A limit is not negative: $bytes" }
        mutate { terminal.setImageStorageLimit(bytes) }
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
        if (bytes.isNotEmpty()) send(bytes)
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

    /**
     * Whether pasting [text] could run commands that the user did not see: it has a newline or a carriage return and the program did not ask
     * for bracketed paste, or it holds the marker that ends one. The host asks the user before [paste] for what is.
     */
    public fun isPasteUnsafe(text: String): Boolean = whileOpen(false) { terminal.isPasteUnsafe(text) }

    /** The search for a text in the history and the screen. */
    public val search: TerminalSearch = TerminalSearch(this)

    internal val searchState: StateFlow<TerminalSearchState> get() = sessionSearch.state

    internal fun setSearchQuery(query: String, regex: Boolean = false, caseSensitive: Boolean = false): Unit =
        mutate { sessionSearch.setQuery(query, regex, caseSensitive) }

    internal fun moveSearch(towardOlder: Boolean): Unit = mutate { sessionSearch.move(towardOlder) }

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
    public fun rowCount(): Long = whileOpen(0L) { terminal.totalRows }

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
    public fun readText(firstRow: Long = 0, lastRow: Long? = null, unwrap: Boolean = true): String = whileOpen("") {
        terminal.readRows(firstRow, lastRow ?: (terminal.totalRows - 1), unwrap)
    }

    /** The text of the rows that are in view. */
    public fun viewportText(unwrap: Boolean = true): String = whileOpen("") {
        val position = terminal.scrollbar
        terminal.readRows(position.offset, position.offset + position.len - 1, unwrap)
    }

    /** The text of the screen, where the program's cursor is, whatever part of the history is in view. */
    public fun screenText(unwrap: Boolean = true): String = whileOpen("") {
        val total = terminal.totalRows
        terminal.readRows(total - terminal.rows, total - 1, unwrap)
    }

    /**
     * The output of the last command that the shell started, or `null` if it started none or its output is not in the history any
     * more. It needs the shell to mark its prompts and its commands (OSC 133): see `shellIntegration` of [start]. What a command that is
     * still running has written is there too.
     */
    public fun lastCommandOutput(unwrap: Boolean = true): String? = whileOpen(null) {
        outputStart?.let { terminal.commandOutput(it, unwrap) }
    }

    /**
     * Moves the cursor of the line that the shell is reading to the cell at [column] and [row] of the screen, by sending it the arrow keys
     * that get there, as Ghostty does with a click: it is for a click on the command line. Only the shell that marks its input (OSC 133: see
     * `shellIntegration` of [start]) and the row of the cursor are moved: the line may be wider than the screen, and the shell is the one to
     * know what is on it.
     *
     * @return Whether the keys were sent, `false` when the shell is not reading, or the cell is not on the row of the cursor.
     */
    public fun moveCursorTo(column: Int, row: Int): Boolean {
        val keys = lock.withLock {
            if (closed.get() || !inputActive || terminal.activeScreen == GhosttyScreen.ALTERNATE) return false
            // Scrolled into the history, the rows of the screen are not the ones that the shell has.
            val position = terminal.scrollbar
            if (position.offset + position.len < position.total) return false
            if (row != terminal.cursorRow) return false
            val delta = charactersBetween(producer.frames.value.rows.getOrNull(row)?.cells, terminal.cursorColumn, column.coerceIn(0, terminal.cols - 1))
            if (delta == 0) return false
            keyEncoder.setOptionsFrom(terminal)
            val arrow = if (delta < 0) TerminalKey.ARROW_LEFT else TerminalKey.ARROW_RIGHT
            List(kotlin.math.abs(delta)) { keyEncoder.encode(KeyInput(arrow).toGhostty()) }.filter { it.isNotEmpty() }
        }
        keys.forEach(::send)
        return keys.isNotEmpty()
    }

    // How many presses of an arrow go from the cell [from] to the cell [to] of a row: one for each character, which a wide one is, whatever
    // the cells it covers. Negative to the left. The cells are counted one each when the row is not known.
    private fun charactersBetween(cells: List<TerminalCell>?, from: Int, to: Int): Int {
        if (cells == null) return to - from
        // The second cell of a wide character is the character.
        val target = if (cells.getOrNull(to)?.width == TerminalCellWidth.SPACER_TAIL) to - 1 else to
        val range = if (target >= from) from until target else target until from
        val characters = range.count { cells.getOrNull(it)?.width.let { width -> width != TerminalCellWidth.SPACER_TAIL && width != TerminalCellWidth.SPACER_HEAD } }
        return if (target >= from) characters else -characters
    }

    /**
     * Extends what is selected, as the keys do (the arrows, with Shift): the end of the selection that a drag moves goes as [adjustment]
     * says. It does not scroll: the host does, if the end goes out of the screen.
     *
     * @return Whether something was selected.
     */
    public fun adjustSelection(adjustment: TerminalSelectionAdjust): Boolean {
        val adjusted = lock.withLock {
            if (closed.get() || !hasSelection) return false
            selectionGesture.reset()
            terminal.adjustSelection(adjustment.toGhostty())
        }
        if (adjusted) producer.request()
        return adjusted
    }

    /**
     * Selects the output of the last command, which is then what [selectedText] gives and what the host copies, as the shell marked it
     * (OSC 133: see `shellIntegration` of [start]). Nothing is selected, and the answer is `false`, before the first command or without
     * the shell's marks.
     */
    public fun selectLastCommandOutput(): Boolean {
        val selected = lock.withLock {
            if (closed.get()) return false
            val start = outputStart ?: return false
            selectionGesture.reset()
            terminal.selectCommandOutput(start).also { if (it) hasSelection = true }
        }
        if (selected) producer.request()
        return selected
    }

    /**
     * The name of the program that has the terminal now: `emacs` while the user runs it, and the shell's own (`zsh`) at its prompt. It is what
     * the shell is waiting for, read from the pty, so it needs a shell with job control, which every interactive one has. `null` when it cannot
     * be known, and once the program has ended. It costs two system calls: ask as often as a name on the screen needs.
     */
    public fun foregroundProcessName(): String? {
        if (exitCode.value != null) return null
        return withPty(null) { fd -> app.katty.terminal.foregroundProcessName(fd) }
    }

    /**
     * The program that has the terminal now, with its command line and its directory (see [ForegroundCommand]), or `null` when it is the shell at
     * its prompt, when it cannot be known, and once the program has ended. It costs a few system calls: ask when [foregroundProcessName] changes,
     * not for each frame.
     */
    public fun foregroundCommand(): ForegroundCommand? {
        if (exitCode.value != null) return null
        return withPty(null) { fd -> app.katty.terminal.foregroundCommand(fd, process.pid().toInt()) }
    }

    /**
     * Runs [block] with the master side of the pty, or gives [default] once the session is closed. The calls on it are system calls that are not
     * made under the terminal's lock: this lock keeps [close] from closing the descriptor while one has it, which would make it act on another file
     * that took its number.
     */
    private inline fun <T> withPty(default: T, block: (Int) -> T): T = ptyLock.read {
        if (ptyClosed) return default
        val fd = (process as? UnixPtyProcess)?.pty?.masterFD?.takeIf { it >= 0 } ?: return default
        block(fd)
    }

    /** The text that is selected, for the caller to copy, as [format] says (the characters only by default), or `null` when nothing is. */
    public fun selectedText(format: TerminalTextFormat = TerminalTextFormat.PLAIN): String? = whileOpen(null) {
        terminal.selectedText(format.toGhostty())?.takeIf { it.isNotEmpty() }
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
        ptyLock.read {
            if (ptyClosed) return
            val done = withPty(false) { fd -> setPtyWindowSize(fd, cols, rows, cols * cellWidthPx, rows * cellHeightPx) }
            if (!done) process.winSize = WinSize(cols, rows)
        }
        producer.request()
    }

    /**
     * Writes [text] on the screen, as if the program had written it: a message of the application, such as
     * the one that says that the program exited. It is not sent to the program.
     *
     * Control sequences are interpreted, so a line ends with `\r\n`.
     */
    public fun writeToScreen(text: String): Unit = mutate {
        terminal.vtWrite(text)
        publishAfterWrite()
    }

    /**
     * Changes the colors: what is on the screen is drawn again with them, and what programs write from now on
     * uses them. A color that a program set itself, with an OSC sequence, stays.
     *
     * @param theme `null` goes back to libghostty's own colors.
     */
    public fun setTheme(theme: TerminalTheme?) {
        val report = lock.withLock {
            if (closed.get()) return
            terminal.applyTheme(theme)
            this.theme = theme
            val dark = isDark(theme)
            val changed = terminal.isColorSchemeDark != dark
            terminal.isColorSchemeDark = dark
            // Only the programs that asked to be told (mode 2031) are, and only when it changed.
            if (changed && terminal.isModeEnabled(GhosttyMode.COLOR_SCHEME_REPORT)) colorSchemeReport(dark) else null
        }
        if (report != null) send(report)
        // The terminal flags every row as changed, so the rows are read again, with their new colors.
        producer.request()
    }

    /**
     * Changes the cursor that a program has until it sets one of its own (DECSCUSR), and that it goes back to when it resets it (`CSI 0 q`,
     * which the shell integration sends at each prompt and before each command). A cursor that nothing has set changes at once.
     */
    public fun setDefaultCursor(shape: TerminalCursorShape, blink: Boolean) {
        require(shape != TerminalCursorShape.BLOCK_HOLLOW) { "The hollow block is not a shape that a program sets" }
        lock.withLock {
            if (closed.get()) return
            terminal.setDefaultCursorStyle(shape.toGhostty())
            terminal.setDefaultCursorBlink(blink)
        }
        producer.request()
    }

    /**
     * Whether bold text in one of the 8 normal colors is drawn in the bright one, as terminals did when they had no bold font. Off by
     * default. What is on the screen is read again, with it.
     */
    public fun setBoldIsBright(enabled: Boolean) {
        producer.setBoldIsBright(enabled)
    }

    /**
     * Ends the program if it is still running, and releases everything.
     */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        logger.debug("Closing the session")

        // Ending the program ends the reader, and unblocks the writer.
        endProgram()
        outgoing.put(STOP)

        // The producer first: it uses the render state, which is freed below.
        producer.close()

        reader.join(KILL_TIMEOUT_MS)
        writer.join(KILL_TIMEOUT_MS)
        if (reader.isAlive || writer.isAlive) logger.warn("The session threads did not end within {}ms", KILL_TIMEOUT_MS)

        // The master side of the pty, which would otherwise stay open until the garbage collector finds it: what asks the pty for the program
        // has it under the read lock.
        ptyLock.write {
            ptyClosed = true
            try {
                (process as? UnixPtyProcess)?.pty?.close()
            } catch (e: IOException) {
                logger.debug("Could not close the pty", e)
            }
        }

        // What the reader still does with the terminal is under the lock, and checks it is not closed.
        lock.withLock {
            outputStart?.close()
            outputStart = null
            sessionSearch.close()
            selectionGesture.close()
            keyEncoder.close()
            mouseEncoder.close()
            renderState.close()
            terminal.close()
        }
    }

    // A hang-up, as a terminal whose window closes sends: an interactive shell ignores SIGTERM, and on a hang-up it saves its history and hangs
    // up its jobs. What does not end soon is killed.
    private fun endProgram() {
        (process as? UnixPtyProcess)?.hangup() ?: process.destroy()
        if (!process.waitFor(HANGUP_GRACE_MS, TimeUnit.MILLISECONDS)) {
            logger.debug("The program did not end within {}ms of the hang-up, killing it", HANGUP_GRACE_MS)
            process.destroyForcibly()
            process.waitFor(KILL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        }
    }

    private fun readOutput() {
        val buffer = ByteArray(READ_BUFFER_SIZE)
        try {
            val input = process.inputStream
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                beforeWrite(read)
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
                producer.outputWritten()
            }
        } catch (e: IOException) {
            // The pty is closed, which is how the program exiting shows on some platforms.
            logger.debug("The pty closed while reading", e)
        } catch (e: Throwable) {
            // Anything else (a bug, the memory that ran out): nothing reads the program's output any more, and a program that writes would wait
            // for good. It is ended, and the terminal says so, rather than staying frozen with nothing to say why.
            logger.error("Reading the output of the program failed: it is ended", e)
            endProgram()
        }
        val code = try {
            process.waitFor()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            -1
        }
        logger.info("The program exited with code {}", code)
        programStatusStore.programExited()
        producer.request()
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
        } finally {
            // Nothing takes what is sent any more: it is not kept for nobody.
            writerGone = true
            outgoing.clear()
        }
    }

    // Sends [bytes] to the program, unless the writer has ended: the queue would grow with every key and answer until the session is closed.
    private fun send(bytes: ByteArray) {
        if (!writerGone) outgoing.put(bytes)
    }

    @Volatile
    private var writerGone = false


    public companion object {
        /** What the terminal tells programs it is, and what they look up to know what it can do. */
        private const val TERM = "xterm-256color"

        private val TYPE_WITHOUT_MARKS_DELAY = 1.seconds

        // How long a shell that marks its prompts has to mark the first one before what waits for it is typed without it.
        private val MARKS_EXPECTED_DELAY = 5.seconds

        // What a shell takes as "clear the screen and draw the prompt again".
        private const val CONTROL_L: Byte = 0x0C

        /** What the terminal calls itself: XTVERSION, and what programs find in `TERM_PROGRAM`. */
        private const val TERMINAL_NAME = "KaTTY"

        // Whether a theme is a dark one, which is what a program that adapts to it asks (CSI ? 996 n). Without a theme it is libghostty's
        // own colors, white on black.
        private fun isDark(theme: TerminalTheme?): Boolean {
            val background = theme?.background ?: return true
            return (0.299 * background.r + 0.587 * background.g + 0.114 * background.b) < 128
        }

        // What tells a program that the scheme changed: CSI ? 997 ; 1 n for dark, 2 for light.
        private fun colorSchemeReport(dark: Boolean): ByteArray = "\u001b[?997;${if (dark) 1 else 2}n".encodeToByteArray()

        // Two clicks are one double click if they are this close in space and in time. The time is the one that the
        // system gives for it, or half a second.
        private const val REPEAT_CLICK_DISTANCE_PX = 5.0
        private val REPEAT_CLICK_INTERVAL_NS = ((java.awt.Toolkit.getDefaultToolkit().getDesktopProperty("awt.multiClickInterval") as? Int) ?: 500) * 1_000_000L

        // How much memory the images of the Kitty graphics protocol may take: the terminal does not store any without a limit.
        // It is Ghostty's own default.
        private const val IMAGE_STORAGE_LIMIT_BYTES = 320_000_000L

        // How much memory the history may take, which is Ghostty's own default.
        private const val SCROLLBACK_MAX_BYTES = 10_000_000L

        // A snapshot that fails because the program is in the middle of a long sequence is tried again this many times, this far apart.
        private const val SNAPSHOT_ATTEMPTS = 5
        private const val SNAPSHOT_RETRY_MS = 20L

        // What takes the old program's state off a restored terminal, so that the new one finds what a new terminal is: the scrolling region reset (which moves the cursor home, so the cursor is saved and put back), the modes that a program sets
        // and a shell does not know about off (the mouse in every protocol and format, focus reports, bracketed paste, synchronized output,
        // application cursor keys and keypad, insert mode, the reports of the color scheme (2031) and of the size (2048), which would write
        // their answers on the shell's command line, the colors reversed (5), modifyOtherKeys, autowrap on, the cursor shown), the character sets ASCII, the Kitty
        // keyboard flags popped, the cursor style the default, the attributes cleared, and a new row to start on. Not a soft reset (DECSTR):
        // it homes the cursor, and the new program would write over what the old one showed.
        private const val RESTORE_RESET =
            "\u001b7\u001b[r\u001b8" +
                "\u001b[?9l\u001b[?1000l\u001b[?1001l\u001b[?1002l\u001b[?1003l\u001b[?1004l\u001b[?1005l\u001b[?1006l\u001b[?1015l\u001b[?1016l" +
                "\u001b[?2004l\u001b[?2026l\u001b[?1l\u001b>\u001b[?66l\u001b[4l\u001b[?2031l\u001b[?2048l\u001b[?5l\u001b[>4m" +
                "\u001b[?7h\u001b[?25h\u001b[?69l" +
                "\u001b(B\u001b)B\u000f\u001b[<99u\u001b[0 q\u001b[0m\r\n"

        // How many events may wait for a collector that is slow.
        private const val EVENT_BUFFER = 64

        // How many rows from the one that is asked a link is looked for on: a line that wraps onto thousands of rows is not read for each
        // move of the pointer.

        // What a cell is until the first resize tells it.
        private const val INITIAL_CELL_WIDTH_PX = 8
        private const val INITIAL_CELL_HEIGHT_PX = 16

        private const val READ_BUFFER_SIZE = 64 * 1024
        // Called with what the reader has just read, before it is written to the terminal: a test of a reader that fails makes it throw.
        @Volatile
        internal var beforeWrite: (Int) -> Unit = {}

        // How long a program has to end after the hang-up of [close], and how long a killed one, and the threads, have after that.
        private const val HANGUP_GRACE_MS = 300L
        private const val KILL_TIMEOUT_MS = 500L

        private val STOP = ByteArray(0)

        // Where a program may leave a temporary image: the real path, since libghostty compares it with the one of the file.
        private fun temporaryDirectory(): String? = try {
            java.nio.file.Path.of(System.getProperty("java.io.tmpdir")).toRealPath().toString()
        } catch (e: java.io.IOException) {
            null
        }

        /**
         * Starts [command] in a pseudo-terminal of [cols] by [rows] cells.
         *
         * @param command By default the user's login shell.
         * @param environment Added to the environment of the current process, and overrides it. `TERM` and
         * `COLORTERM` are set to what the terminal supports, unless it says otherwise.
         * @param directory Where the program starts: the directory of [snapshot] if it has one that is still there, else the user's home.
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
         * @param imageMediums Whether a program may give the Kitty graphics protocol an image by the path of a file (`t=f`), by a temporary
         * file of the temporary directory (`t=t`) or by shared memory (`t=s`), and not only in the escape sequence: it is what `kitten icat`
         * does on a terminal that is on the same machine, and a program is told `unsupported medium` when it is off. The terminal then reads
         * what the program names, so a program that runs in it can show any file the user can read as an image, but no more than that:
         * it never sees the bytes. `true` by default.
         * @param theme The colors, libghostty's own by default. [setTheme] changes them.
         * @param shellIntegration Whether to make zsh, bash and fish report their prompts (OSC 133) and their directory (OSC 7), which they
         * do not on their own: the [workingDirectory] and the [TerminalEvent.PromptStarted], [TerminalEvent.CommandStarted] and
         * [TerminalEvent.CommandFinished] events, and [scrollToPrompt], depend on it. The user's own startup files are still read.
         * Any other shell, and anything but a login or an interactive shell, is started as it is.
         * @param shellIntegrationDirectory Where the scripts of [shellIntegration] are kept, written again whenever they are missing or differ:
         * a directory of the application's own, which the system does not clean. A temporary directory when it is `null`.
         * @param snapshot What the terminal was ([TerminalSession.snapshot]): the new session starts with its screen and its history, the
         * title and the directory, and [command] runs below them: a **new** program, since a process cannot be saved. The size is [cols] by
         * [rows], to which the old content is reflowed, and the old program's modes are taken off, so that a snapshot of an editor gives a
         * shell with a plain mouse. Not restored: the selection, the viewport. The images of the Kitty protocol are.
         * @param boldIsBright Whether bold text in one of the 8 normal colors of the palette is drawn in the bright one, as terminals did when
         * they had no bold font. [setBoldIsBright] changes it.
         * @param typeAtPrompt A command to type in the shell when it first waits for one, as the user would (see [TypedCommand]). The shell has
         * to be [command] itself: it is typed after the mark of its first prompt, which needs [shellIntegration], or after a second without it.
         * @param clipboardWrite Whether a program may put text in the clipboard (OSC 52), which then comes as a
         * [TerminalEvent.ClipboardWrite]. It is refused when `false`.
         * @param clipboardReader What a program that reads the clipboard (OSC 52) gets: the text it returns, or a refusal for
         * `null`. Programs cannot read it without one, and it is the host's to give: what is on the clipboard may be secret. It runs on
         * the thread that reads the program's output, which waits for it, and under the session's lock, which [sendKey], [paste] and
         * the other calls from the UI thread take: it must be quick, must not call back into the session, and must not wait for the UI
         * thread (asking the user in a dialog would block both for good).
         * @throws TerminalSnapshotException If [snapshot] was made by another build of libghostty, or is damaged.
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
            imageMediums: Boolean = true,
            theme: TerminalTheme? = null,
            clipboardWrite: Boolean = true,
            clipboardReader: ((TerminalClipboardLocation) -> String?)? = null,
            shellIntegration: Boolean = true,
            snapshot: TerminalSnapshot? = null,
            boldIsBright: Boolean = false,
            typeAtPrompt: TypedCommand? = null,
            shellIntegrationDirectory: Path? = null,
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
            // Not the one of the terminal that started this application: programs that look at it to know where they run.
            env["TERM_PROGRAM"] = TERMINAL_NAME
            env.remove("TERM_PROGRAM_VERSION")
            env.putAll(environment)
            // An application opened from the Finder has no locale: the shell would read what is typed as ASCII.
            ensureUtf8Locale(env)
            val applied = if (shellIntegration) ShellIntegration.apply(command, env, shellIntegrationDirectory) else null
            val argv = applied?.argv ?: command
            val shell = ShellIntegration.shellOf(command)
            val typed = typeAtPrompt?.let { it.toInput(shell ?: "sh") ?: null.also { logger.warn("Not typing {}: it holds a control character", it) } }
            // The prompt marks are there when the shell is one that the integration knows and it is on.
            val marked = applied?.installed == true

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
                    if (imageMediums) it.setKittyImageMediums(file = true, temporaryDirectory = temporaryDirectory(), sharedMemory = true)
                    toClose.addFirst(it)
                }
                // Before the session, so that the first frame already has the colors.
                terminal.applyTheme(theme)
                terminal.isColorSchemeDark = isDark(theme)
                terminal.xtVersion = TERMINAL_NAME
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
                val renderState = GhosttyRenderState().also {
                    it.boldIsBright = boldIsBright
                    toClose.addFirst(it)
                }
                val keyEncoder = GhosttyKeyEncoder().also { toClose.addFirst(it) }
                val mouseEncoder = GhosttyMouseEncoder().also { toClose.addFirst(it) }
                val selectionGesture = GhosttySelectionGesture(terminal).also { toClose.addFirst(it) }
                terminal.setClipboardReader(clipboardReader?.let { reader -> { location -> reader(location.toTerminal()) } })
                return TerminalSession(process, terminal, renderState, keyEncoder, mouseEncoder, selectionGesture, theme, clipboardWrite)
                    .also {
                        if (typed != null) {
                            it.pendingInput.set(typed)
                            it.pendingAtPromptStart = marked && shell == "fish"
                            if (marked) it.typeIfTheMarksDoNotCome(shell!!) else it.typeAfterQuiet()
                        }
                        it.launch()
                    }
            } catch (e: Throwable) {
                logger.error("Could not start the session", e)
                process.destroyForcibly()
                toClose.forEach { it.close() }
                throw e
            }
        }
    }
}
