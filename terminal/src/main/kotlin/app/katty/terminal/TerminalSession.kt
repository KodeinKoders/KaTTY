package app.katty.terminal

import app.katty.ghostty.GhosttyKeyEncoder
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
import kotlin.time.Duration.Companion.seconds
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
) : AutoCloseable {
    // Guards the terminal, and the key encoder that reads it: the thread that feeds the terminal and the
    // ones that encode keys or resize it take turns. The render state is only ever used by the frame
    // producer, and only its update reads the terminal.
    private val lock = ReentrantLock()

    private val closed = AtomicBoolean(false)

    // What goes to the program: the keys, and what the terminal answers to its queries.
    private val outgoing = LinkedBlockingQueue<ByteArray>()

    // A conflated channel: any number of signals while a frame is being produced mean one more frame.
    private val frameRequests = Channel<Unit>(Channel.CONFLATED)

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
     * second: what the program writes in between is merged.
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
                try {
                    frameState.value = produceFrame()
                } catch (e: Throwable) {
                    if (closed.get()) break
                    logger.error("Could not produce a frame", e)
                }

                // What is requested while waiting is merged into the one next frame, so the last output is
                // always published, only later. After an idle period there is nothing to wait for: typing
                // is not slowed down.
                val remaining = MIN_FRAME_INTERVAL - started.elapsedNow()
                if (remaining.isPositive()) delay(remaining)
            }
        }
        reader.start()
        writer.start()
    }

    /**
     * Sends a key to the program, as the terminal's current modes ask for it to be encoded.
     */
    public fun sendKey(input: KeyInput) {
        val bytes = lock.withLock {
            if (closed.get()) return
            keyEncoder.setOptionsFrom(terminal)
            keyEncoder.encode(input.toGhostty())
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
            keyEncoder.close()
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
        lock.withLock { renderState.update(terminal) }

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

        val colors = renderState.colors
        val frame = TerminalFrame(
            cols = renderState.cols,
            rows = terminalRows,
            background = colors.background.toTerminal(),
            foreground = colors.foreground.toTerminal(),
            cursorColor = colors.cursor?.toTerminal(),
            cursor = renderState.cursor.toTerminal(),
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
         */
        public fun start(
            cols: Int,
            rows: Int,
            command: List<String> = listOf(System.getenv("SHELL") ?: "/bin/zsh", "-l"),
            environment: Map<String, String> = emptyMap(),
            directory: String = System.getProperty("user.home"),
            cursorBlink: Boolean = true,
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
                val renderState = GhosttyRenderState().also { toClose.addFirst(it) }
                val keyEncoder = GhosttyKeyEncoder().also { toClose.addFirst(it) }
                return TerminalSession(process, terminal, renderState, keyEncoder).also { it.launch() }
            } catch (e: Throwable) {
                logger.error("Could not start the session", e)
                process.destroyForcibly()
                toClose.forEach { it.close() }
                throw e
            }
        }
    }
}
