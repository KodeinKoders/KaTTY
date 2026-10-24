package app.katty.terminal

import app.katty.ghostty.GhosttyCompressionMode
import app.katty.ghostty.GhosttyCompressionResult
import app.katty.ghostty.GhosttyDirty
import app.katty.ghostty.GhosttyImage
import app.katty.ghostty.GhosttyImagePlacement
import app.katty.ghostty.GhosttyMouseShape
import app.katty.ghostty.GhosttyRenderState
import app.katty.ghostty.GhosttyRowId
import app.katty.ghostty.GhosttyScreen
import app.katty.ghostty.GhosttyScrollbar
import app.katty.ghostty.GhosttyTerminal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

private val logger = LoggerFactory.getLogger(FrameProducer::class.java)

// A program that floods the output must not keep the producer, and the lock it takes to read the
// terminal, busy: more frames than a display shows are of no use.
private val MIN_FRAME_INTERVAL = 1.seconds / 60
// A frame is made once the program has been silent for this long, or after SETTLE_MAX anyway. The writes
// of a redraw are 0.02 to 0.7 ms apart when zsh draws its completion menu, and the margin is for the
// reader's thread being late. This is up to 3 ms of latency, which is what shows a redraw whole. They are
// whole milliseconds, which is the resolution of delay on the producer's dispatcher.
private val SETTLE_QUIET = 3.milliseconds
private val SETTLE_MAX = 8.milliseconds

// How long a program may hold the screen (synchronized output) before it is shown anyway: Ghostty's own limit.
private val HOLD_MAX = 1.seconds

// A hidden session starts to compress its history after this long without output, looks at whether it has changed this
// often once it has compressed what it could, and pauses between the steps of a pass.
private val HIDDEN_IDLE = 1.seconds
private val HIDDEN_POLL = 1.seconds
private val COMPRESSION_STEP_PAUSE = 5.milliseconds

/**
 * What makes the frames of a session ([TerminalSession.frames]): it reads the terminal through the render state, which is its own, at most 60
 * times per second once the output pauses, keeps the rows and the images that did not change, and lets go of them while the session is not
 * visible. Only the update of the render state reads the terminal, under the session's [lock]: reading the rows does not.
 *
 * It makes the first frame when it is made, with the session; [start] starts its coroutines, and [close] stops them.
 *
 * @param isClosed Whether the session is closed, which the calls that take the lock look at.
 * @param session What it reads of the session besides the terminal.
 */
internal class FrameProducer(
    private val terminal: GhosttyTerminal,
    private val renderState: GhosttyRenderState,
    private val lock: ReentrantLock,
    private val isClosed: () -> Boolean,
    private val session: Session,
) {
    /** What the producer reads of its session. */
    interface Session {
        /** The size of a cell in pixels that the terminal was given. Read under the lock. */
        val cellWidthPx: Int
        val cellHeightPx: Int

        /** The colors that a frame has besides the ones libghostty resolves. */
        val theme: TerminalTheme?

        /** Takes the selection off, since the program wrote over it. Called under the lock. */
        fun unselect()

        /** Whether the program turned the echo off to read a line: a password. */
        fun readingPassword(): Boolean
    }

    // One coroutine at a time, so that what only the producer touches (the render state, the caches) is never used by two, and on the threads
    // that every session shares rather than one of its own. IO and not Default: closing a session waits for the producer, and many
    // sessions that close at the same time must not wait for each other on the workers that the producers need.
    @OptIn(ExperimentalCoroutinesApi::class)
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    // A conflated channel: any number of signals while a frame is being produced mean one more frame.
    private val requests = Channel<Unit>(Channel.CONFLATED)

    /** Asks for a frame, which is made once the output pauses, with the others that are asked for meanwhile. */
    fun request() {
        requests.trySend(Unit)
    }

    /** When the reader last fed the terminal, in System.nanoTime() terms: what the producer waits on. */
    @Volatile
    var lastOutputNanos: Long = System.nanoTime() - 1_000_000_000L
        private set

    /** The reader fed the terminal: a frame is asked for, which waits for the output to pause. */
    fun outputWritten() {
        lastOutputNanos = System.nanoTime()
        request()
    }

    // When the program began to hold the screen (synchronized output), in System.nanoTime() terms, 0 when it does not: the producer
    // makes no frame meanwhile.
    @Volatile
    private var holdSinceNanos = 0L

    /** The program began (`true`) or ended a hold of the screen: the frame that is shown stays until it ends, and what was kept back is shown then. */
    fun hold(held: Boolean) {
        holdSinceNanos = if (held) System.nanoTime() else 0L
        if (!held) request()
    }

    // The rows of the last frame, to hand the same ones out again when they did not change. Only used by
    // the producer, once it runs.
    private var rowCache: Map<GhosttyRowId, TerminalRow> = emptyMap()

    // Set when what the rows are drawn from changed, with no change of the rows: the producer forgets what it kept.
    @Volatile
    private var rowCacheStale = false

    // A change of [setBoldIsBright] that the producer has not made yet.
    @Volatile
    private var boldIsBrightRequest: Boolean? = null

    /** Whether bold text in one of the 8 normal colors is drawn in the bright one: the render state is the producer's, which takes it at its next frame. */
    fun setBoldIsBright(enabled: Boolean) {
        boldIsBrightRequest = enabled
        request()
    }

    // The images of the last frames, by id, which are only the ones whose placements exist: what is deleted frees its memory.
    // Only the frame producer uses it, once it runs. It is before the first frame, which uses it.
    private val imageCache = HashMap<Int, TerminalImage>()

    /** Both searches, and what they keep. Before the first frame, which asks it for the matches. */
    val search: SessionSearch = SessionSearch(terminal, lock, isClosed, ::request, scope)

    private val frameState = MutableStateFlow(produceFrame())

    // Whether frames are produced: see [setVisible].
    private val visibleState = MutableStateFlow(true)

    val frames: StateFlow<TerminalFrame> get() = frameState

    val visible: StateFlow<Boolean> get() = visibleState

    /** Whether anybody looks at the session: see [TerminalSession.setVisible]. */
    fun setVisible(visible: Boolean) {
        if (visibleState.value == visible) return
        visibleState.value = visible
        // What happened while it was hidden is shown by the frame that this asks for, even if nothing asked for one since.
        if (visible) request()
    }

    /** Starts making the frames that are asked for, and letting go of what is kept while the session is hidden. */
    fun start() {
    scope.launch {
        for (request in requests) {
            // A request that comes while the session is hidden waits here, and the ones after it merge into the next one: showing
            // it again makes one frame, which is not followed by another for the request that was already served.
            if (!visibleState.value) {
                visibleState.first { it }
                requests.tryReceive()
            }
            val started = TimeSource.Monotonic.markNow()
            settle(started)
            awaitHold()
            // Hidden while it waited: the frame is for when it is shown.
            if (!visibleState.value) {
                visibleState.first { it }
                requests.tryReceive()
            }
            try {
                frameState.value = produceFrame()
            } catch (e: Throwable) {
                if (isClosed()) break
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
    scope.launch {
        visibleState.collectLatest { visible -> if (!visible) releaseWhileHidden() }
    }
    }

    /** Stops: no frame is made after it returns, so that what the frames are made from can be freed. */
    fun close() {
        requests.close()
        runBlocking { scope.coroutineContext.job.cancelAndJoin() }
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

    private fun compressionActivity(): Long? = lock.withLock { if (isClosed()) null else terminal.compressionActivity() }

    // A bounded amount of compression, under the lock for that long.
    private fun compressOnce(): GhosttyCompressionResult? =
        lock.withLock { if (isClosed()) null else terminal.compress(GhosttyCompressionMode.INCREMENTAL) }

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
     * Waits while the program holds the screen (synchronized output, mode 2026): it is drawing a frame, and the one that is shown stays
     * until it has finished. A program that does not finish is shown after [HOLD_MAX], as Ghostty does, so that it cannot freeze the screen.
     * The frame that is kept is the last one made, which is made as before: this only keeps the next one from being made.
     */
    private suspend fun awaitHold() {
        while (true) {
            val since = holdSinceNanos
            if (since == 0L || (System.nanoTime() - since).nanoseconds >= HOLD_MAX) return
            delay(1)
        }
    }

    // Only the update reads the terminal, so only it is under the lock: reading the rows, which is the
    // costly part, does not hold up the thread that feeds the terminal.
    private fun produceFrame(): TerminalFrame {
        boldIsBrightRequest?.let { enabled ->
            boldIsBrightRequest = null
            if (renderState.boldIsBright != enabled) {
                renderState.boldIsBright = enabled
                // The rows are only read again if they changed, or if they are not known.
                rowCacheStale = true
            }
        }
        val tracking: Boolean
        val alternate: Boolean
        val shape: GhosttyMouseShape
        val position: GhosttyScrollbar
        val placements: List<GhosttyImagePlacement>
        val fresh = HashMap<Int, GhosttyImage>()
        val cellWidth: Int
        val cellHeight: Int
        val searchLook: SessionSearch.Look
        lock.withLock {
            renderState.update(terminal)
            // As of this moment, with the rows that are read for it: what is found is where it is on this frame.
            searchLook = search.look(renderState.dirty != GhosttyDirty.CLEAN)
            tracking = terminal.mouseTracking
            alternate = terminal.activeScreen == GhosttyScreen.ALTERNATE
            shape = terminal.mouseShape
            position = terminal.scrollbar
            cellWidth = session.cellWidthPx
            cellHeight = session.cellHeightPx

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

        if (rowCacheStale) {
            rowCacheStale = false
            rowCache = emptyMap()
        }
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
            lock.withLock { session.unselect() }
            request()
        }
        val selection = if (written || rows.all { it.selection == null }) emptyList() else rows.map { it.selection }

        // What the placeholder cells show, under the text; with the images that programs placed, by layer.
        val images = if (terminalRows.any { it.hasPlaceholder }) {
            (realImages + placeholderPlacements(terminalRows, placements, cellWidth, cellHeight)).sortedBy { it.layer.ordinal }
        } else {
            realImages
        }

        val colors = renderState.colors
        val theme = session.theme
        val frame = TerminalFrame(
            cols = renderState.cols,
            rows = terminalRows,
            background = colors.background.toTerminal(),
            foreground = colors.foreground.toTerminal(),
            cursorColor = colors.cursor?.toTerminal(),
            cursor = renderState.cursor.toTerminal().let { cursor ->
                // The terminal cannot tell that the program reads a password: the pty can.
                if (cursor.passwordInput || !session.readingPassword()) cursor else cursor.copy(passwordInput = true)
            },
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
            searchMatches = search.matches(searchLook, terminalRows, position, renderState.cols),
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
}
