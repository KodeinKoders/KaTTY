package app.katty.ghostty

import app.katty.ghostty.ffm.GhosttyClipboardContent
import app.katty.ghostty.ffm.GhosttyClipboardRead
import app.katty.ghostty.ffm.GhosttyClipboardReadReply
import app.katty.ghostty.ffm.GhosttyClipboardReadReplyFn
import app.katty.ghostty.ffm.GhosttyClipboardWrite
import app.katty.ghostty.ffm.GhosttyClipboardWriteReply
import app.katty.ghostty.ffm.GhosttyClipboardWriteReplyFn
import app.katty.ghostty.ffm.GhosttyDeviceAttributes
import app.katty.ghostty.ffm.GhosttyDeviceAttributesPrimary
import app.katty.ghostty.ffm.GhosttyDeviceAttributesSecondary
import app.katty.ghostty.ffm.GhosttyDeviceAttributesTertiary
import app.katty.ghostty.ffm.GhosttyColorRgb
import app.katty.ghostty.ffm.GhosttyGridRef
import app.katty.ghostty.ffm.GhosttyKittyGraphicsPlacementRenderInfo
import app.katty.ghostty.ffm.GhosttyPoint
import app.katty.ghostty.ffm.GhosttyPointCoordinate
import app.katty.ghostty.ffm.GhosttyPointValue
import app.katty.ghostty.ffm.GhosttySelection
import app.katty.ghostty.ffm.GhosttySizeReportSize
import app.katty.ghostty.ffm.GhosttyString
import app.katty.ghostty.ffm.GhosttyTerminalBellFn
import app.katty.ghostty.ffm.GhosttyTerminalClipboardReadFn
import app.katty.ghostty.ffm.GhosttyTerminalClipboardWriteFn
import app.katty.ghostty.ffm.GhosttyTerminalColorSchemeFn
import app.katty.ghostty.ffm.GhosttyTerminalRenderHoldFn
import app.katty.ghostty.ffm.GhosttyTerminalXtversionFn
import app.katty.ghostty.ffm.GhosttyTerminalDesktopNotification
import app.katty.ghostty.ffm.GhosttyTerminalDesktopNotificationFn
import app.katty.ghostty.ffm.GhosttyTerminalDeviceAttributesFn
import app.katty.ghostty.ffm.GhosttyTerminalMemoryUsage
import app.katty.ghostty.ffm.GhosttyTerminalModeConfig
import app.katty.ghostty.ffm.GhosttyTerminalProgressReport
import app.katty.ghostty.ffm.GhosttyTerminalProgramStatus
import app.katty.ghostty.ffm.GhosttyTerminalProgramStatusFn
import app.katty.ghostty.ffm.GhosttyTerminalResizeWindow
import app.katty.ghostty.ffm.GhosttyTerminalResizeWindowFn
import app.katty.ghostty.ffm.GhosttyTerminalResizeWindowGrid
import app.katty.ghostty.ffm.GhosttyTerminalResizeWindowValue
import app.katty.ghostty.ffm.GhosttyTerminalProgressReportFn
import app.katty.ghostty.ffm.GhosttyTerminalPwdChangedFn
import app.katty.ghostty.ffm.GhosttyTerminalResetFn
import app.katty.ghostty.ffm.GhosttyTerminalScrollViewport
import app.katty.ghostty.ffm.GhosttyTerminalScrollViewportValue
import app.katty.ghostty.ffm.GhosttyTerminalScrollbar
import app.katty.ghostty.ffm.GhosttyTerminalSelectionFormatOptions
import app.katty.ghostty.ffm.GhosttyTerminalSemanticPrompt
import app.katty.ghostty.ffm.GhosttyTerminalSemanticPromptFn
import app.katty.ghostty.ffm.GhosttyTerminalSizeFn
import app.katty.ghostty.ffm.GhosttyTerminalTitleChangedFn
import app.katty.ghostty.ffm.GhosttyTerminalWritePtyFn
import app.katty.ghostty.ffm.GhosttyVt
import org.slf4j.LoggerFactory
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.util.Objects

private val logger = LoggerFactory.getLogger(GhosttyTerminal::class.java)

// The memory that a getter answers in: the largest of their structs (the scrollbar's) is 24 bytes.
private const val SCRATCH_BYTES = 64L

// How much of a write is copied at once: a read of the PTY is smaller, an image translated for the Kitty protocol is written in pieces.
private const val WRITE_BUFFER_BYTES = 64L * 1024

/**
 * A virtual terminal: it consumes the bytes a program writes, and maintains the screen, the cursor,
 * the scrollback and the modes that result.
 *
 * **A terminal is not thread-safe.** The caller must serialize every call on a terminal, and on the
 * [GhosttyRenderState] and [GhosttyKeyEncoder] that read from it, for instance with one lock shared by
 * the thread feeding it and the thread rendering it.
 *
 * @param terminfoName The terminfo entry this terminal runs as (for example `xterm-256color`), reported
 * when a program asks for it. Nothing is reported when it is `null`.
 * @param defaultCursorBlink Whether the cursor blinks unless the program says otherwise: libghostty's own
 * default is that it does not, and most programs, shells included, never ask for it.
 * @param scrollbackMaxBytes How much memory the history may take, about: libghostty prunes by pages, so it is
 * somewhat more. `null` keeps libghostty's own limit.
 * @param imageStorageLimitBytes How much memory the images of the Kitty graphics protocol may take. Without a limit
 * the terminal does not store images: the protocol is off. `null` leaves it off.
 * @param defaultCursorStyle The shape of the cursor until a program sets one (DECSCUSR), and when it resets it: libghostty's own is
 * the block. Not [GhosttyCursorStyle.BLOCK_HOLLOW], which is how an unfocused block is drawn and not a style that a program sets.
 */
public class GhosttyTerminal private constructor(
    // A terminal that was decoded from a snapshot, which is set up as a new one is, or `null` to make one.
    adopted: MemorySegment?,
    cols: Int,
    rows: Int,
    terminfoName: String?,
    defaultCursorBlink: Boolean,
    scrollbackMaxBytes: Long?,
    imageStorageLimitBytes: Long?,
    defaultCursorStyle: GhosttyCursorStyle?,
) : AutoCloseable {
    public constructor(
        cols: Int,
        rows: Int,
        terminfoName: String? = null,
        defaultCursorBlink: Boolean = false,
        scrollbackMaxBytes: Long? = null,
        imageStorageLimitBytes: Long? = null,
        defaultCursorStyle: GhosttyCursorStyle? = null,
    ) : this(null, cols, rows, terminfoName, defaultCursorBlink, scrollbackMaxBytes, imageStorageLimitBytes, defaultCursorStyle)

    internal val handle: MemorySegment

    // Holds the upcall stubs: native code calls them for as long as the terminal lives. And the memory that the calls reuse, which goes
    // with it.
    private val callbacks = Arena.ofShared()

    // What a getter is answered in, instead of an arena of its own for each call: the terminal is not thread-safe, so its calls never
    // overlap, and a getter that a callback calls during a write does not use the write's buffer. Zeroed for each use.
    private val scratch = callbacks.allocate(SCRATCH_BYTES, 8)

    private fun scratch(bytes: Long): MemorySegment {
        check(bytes <= SCRATCH_BYTES) { "$bytes bytes do not fit in the scratch memory" }
        return scratch.asSlice(0, bytes).fill(0)
    }

    // What a write is copied to: a piece that is bigger is written in pieces of this size, which the parser reads as one stream.
    private val writeBuffer = callbacks.allocate(WRITE_BUFFER_BYTES)

    private var closed = false

    // What the size queries (XTWINOPS) are answered with. The answer is given while the terminal is being
    // written to, when it must not be read: so the size is kept here, as it was given to the last resize.
    // The size of a cell is 0 until a resize gives it: there is no answer before.
    private var reportedCols = cols
    private var reportedRows = rows
    private var reportedCellWidthPx = 0
    private var reportedCellHeightPx = 0

    /**
     * Receives what the terminal answers to the program: replies to queries such as the cursor position
     * or the device attributes. They have to be written to the program's input.
     *
     * It is called on the thread that called [vtWrite], while it runs, so it must not write to this terminal ([vtWrite]): libghostty
     * forbids that one reentrance, and reading the terminal is allowed. An exception it throws is logged, since it cannot cross into the
     * native code.
     */
    @Volatile
    public var onWritePty: ((ByteArray) -> Unit)? = null

    // What the terminal reports besides the screen. Each is called on the thread that called [vtWrite], while it runs, like
    // [onWritePty]: it must not write to this terminal (libghostty forbids [vtWrite] in a callback, and allows the rest, such as
    // [trackCursor], which the session calls at OSC 133;C), and should only record or enqueue, since the program's output waits for it. An
    // exception that it throws is logged, since it cannot cross into the native code. The strings that come with them are copies.

    /** A BEL character was written. */
    @Volatile
    public var onBell: (() -> Unit)? = null

    /** The title changed (OSC 0 or 2): [title] has it, once the callback has returned. */
    @Volatile
    public var onTitleChanged: (() -> Unit)? = null

    /** The working directory changed (OSC 7 and others), or was cleared: [pwd] has it. */
    @Volatile
    public var onPwdChanged: (() -> Unit)? = null

    /** The terminal was reset (RIS). It did not call [onTitleChanged] nor [onPwdChanged] for what it cleared. */
    @Volatile
    public var onReset: (() -> Unit)? = null

    /** A program asked for a desktop notification (OSC 9 or 777), with an empty title when it had none. */
    @Volatile
    public var onNotification: ((title: String, body: String) -> Unit)? = null

    /** A program reported its progress (OSC 9;4). */
    @Volatile
    public var onProgress: ((GhosttyProgress) -> Unit)? = null

    /**
     * A program reported what it is doing (OSC 7501). The terminal keeps no record of them: the application does. With a handler the
     * terminal also answers the program's query of its status, and it reports a reset (RIS) as a [GhosttyProgramState.CLEAR] with no id.
     */
    @Volatile
    public var onProgramStatus: ((GhosttyProgramStatus) -> Unit)? = null

    /** A program asked to resize the window (CSI 8 t). The terminal is not resized: the application decides. */
    @Volatile
    public var onResizeRequest: ((GhosttyResizeRequest) -> Unit)? = null

    /** The shell reported a step of the prompt (OSC 133). */
    @Volatile
    public var onSemanticPrompt: ((GhosttySemanticPrompt) -> Unit)? = null

    /**
     * A program asked to put [String] in the clipboard (OSC 52): the text, empty to clear it. Return whether it is allowed.
     * Without a handler, writes are refused.
     */
    @Volatile
    public var onClipboardWrite: ((GhosttyClipboardLocation, String) -> Boolean)? = null

    /**
     * A program began (`true`) or ended (`false`) a hold of the screen: it asks for the screen not to be updated while it draws a frame
     * (synchronized output, mode 2026). The terminal also ends it by itself when it is resized or reset. While it is held the render state
     * should not be updated. Nothing ends a hold that lasts: the host stops honoring it after a while (the session does after a second), so
     * that a program cannot freeze the screen.
     */
    @Volatile
    public var onRenderHold: ((held: Boolean) -> Unit)? = null

    /**
     * Whether the color scheme is dark, as the answer to a program's query (CSI ? 996 n), `null` for no answer. Read when the query is
     * answered. A change is told to the programs that asked for it (mode 2031) by the host, which writes the report to the program.
     */
    @Volatile
    public var isColorSchemeDark: Boolean? = null

    /**
     * What XTVERSION (CSI > q) is answered with, such as `KaTTY 1.0`. `null` is the library's own name. Read when the query is answered.
     */
    @Volatile
    public var xtVersion: String? = null

    // Not installed until a reader is set: without one, a read is refused. Having one also turns on the Kitty paste events (mode 5522), but
    // only for what is pasted with `ghostty_terminal_paste`, which is not bound: a paste here is `ghostty_paste_encode`, which sends the text.
    private var clipboardReader: ((GhosttyClipboardLocation) -> String?)? = null
    private var clipboardReadStub: MemorySegment? = null

    init {
        NativeLibrary.ensureLoaded()

        handle = adopted ?: Arena.ofConfined().use { arena ->
            val out = arena.allocate(ValueLayout.ADDRESS)
            checkResult(
                GhosttyVt.ghostty_terminal_new(
                    MemorySegment.NULL,
                    out,
                    cols.toUInt16("cols"),
                    rows.toUInt16("rows"),
                ),
                "ghostty_terminal_new",
            )
            out.get(ValueLayout.ADDRESS, 0)
        }

        try {
            val writePty = GhosttyTerminalWritePtyFn.allocate(
                GhosttyTerminalWritePtyFn.Function { _, _, data, len ->
                    // An exception must never cross into native code: it would take the JVM down.
                    try {
                        onWritePty?.invoke(data.reinterpret(len).toArray(ValueLayout.JAVA_BYTE))
                    } catch (e: Throwable) {
                        logger.error("onWritePty failed", e)
                    }
                },
                callbacks,
            )
            // Callbacks are passed as is, not through a pointer to them.
            checkResult(
                GhosttyVt.ghostty_terminal_set(handle, GhosttyVt.GHOSTTY_TERMINAL_OPT_WRITE_PTY(), writePty),
                "ghostty_terminal_set(WRITE_PTY)",
            )

            val size = GhosttyTerminalSizeFn.allocate(
                GhosttyTerminalSizeFn.Function { _, _, out ->
                    // An exception must never cross into native code: it would take the JVM down.
                    try {
                        if (reportedCellWidthPx <= 0 || reportedCellHeightPx <= 0) {
                            false
                        } else {
                            val struct = out.reinterpret(GhosttySizeReportSize.layout().byteSize())
                            GhosttySizeReportSize.rows(struct, reportedRows.toShort())
                            GhosttySizeReportSize.columns(struct, reportedCols.toShort())
                            GhosttySizeReportSize.cell_width(struct, reportedCellWidthPx)
                            GhosttySizeReportSize.cell_height(struct, reportedCellHeightPx)
                            true
                        }
                    } catch (e: Throwable) {
                        logger.error("The size report failed", e)
                        false
                    }
                },
                callbacks,
            )
            checkResult(
                GhosttyVt.ghostty_terminal_set(handle, GhosttyVt.GHOSTTY_TERMINAL_OPT_SIZE(), size),
                "ghostty_terminal_set(SIZE)",
            )

            installEffects()

            setDefaultCursorBlink(defaultCursorBlink)

            if (scrollbackMaxBytes != null) setLimit(GhosttyVt.GHOSTTY_TERMINAL_OPT_SCROLLBACK_MAX_BYTES(), scrollbackMaxBytes, "SCROLLBACK_MAX_BYTES")
            if (imageStorageLimitBytes != null) {
                setLimit(GhosttyVt.GHOSTTY_TERMINAL_OPT_KITTY_IMAGE_STORAGE_LIMIT(), imageStorageLimitBytes, "KITTY_IMAGE_STORAGE_LIMIT")
            }
            // So that a snapshot can be taken in the middle of an escape sequence: what is unfinished is kept, up to this much, and past it the
            // terminal goes on as it did and only a snapshot is refused, until the sequence ends.
            setContinuationMaxBytes(CONTINUATION_MAX_BYTES)
            if (defaultCursorStyle != null) setDefaultCursorStyle(defaultCursorStyle)

            if (terminfoName != null) {
                Arena.ofConfined().use { arena ->
                    // The string is copied by the terminal.
                    val bytes = arena.allocateFrom(terminfoName)
                    val value = arena.allocate(GhosttyString.layout())
                    GhosttyString.ptr(value, bytes)
                    GhosttyString.len(value, bytes.byteSize() - 1)
                    checkResult(
                        GhosttyVt.ghostty_terminal_set(
                            handle,
                            GhosttyVt.GHOSTTY_TERMINAL_OPT_TERMINFO_NAME(),
                            value,
                        ),
                        "ghostty_terminal_set(TERMINFO_NAME)",
                    )
                }
            }
        } catch (e: Throwable) {
            close()
            throw e
        }
    }

    /** Width in cells. */
    public val cols: Int get() = getUInt16(GhosttyVt.GHOSTTY_TERMINAL_DATA_COLS())

    /** Height in cells. */
    public val rows: Int get() = getUInt16(GhosttyVt.GHOSTTY_TERMINAL_DATA_ROWS())

    /** The Kitty keyboard protocol flags that the program running in the terminal asked for. */
    internal val kittyKeyboardFlags: Int
        get() {
            requireOpen()
            return run {
                val out = scratch(ValueLayout.JAVA_BYTE.byteSize())
                checkResult(
                    GhosttyVt.ghostty_terminal_get(handle, GhosttyVt.GHOSTTY_TERMINAL_DATA_KITTY_KEYBOARD_FLAGS(), out),
                    "ghostty_terminal_get(KITTY_KEYBOARD_FLAGS)",
                )
                out.get(ValueLayout.JAVA_BYTE, 0).toInt() and 0xFF
            }
        }

    /** Moves the viewport, which shows a part of the history and of the screen. Not past either end. */
    public fun scroll(to: GhosttyScrollViewport) {
        requireOpen()
        Arena.ofConfined().use { arena ->
            val behavior = arena.allocate(GhosttyTerminalScrollViewport.layout())
            val value = GhosttyTerminalScrollViewport.value(behavior)
            when (to) {
                GhosttyScrollViewport.Top -> GhosttyTerminalScrollViewport.tag(behavior, GhosttyVt.GHOSTTY_SCROLL_VIEWPORT_TOP())
                GhosttyScrollViewport.Bottom -> GhosttyTerminalScrollViewport.tag(behavior, GhosttyVt.GHOSTTY_SCROLL_VIEWPORT_BOTTOM())
                is GhosttyScrollViewport.Delta -> {
                    GhosttyTerminalScrollViewport.tag(behavior, GhosttyVt.GHOSTTY_SCROLL_VIEWPORT_DELTA())
                    GhosttyTerminalScrollViewportValue.delta(value, to.rows)
                }
                is GhosttyScrollViewport.DeltaPrompt -> {
                    GhosttyTerminalScrollViewport.tag(behavior, GhosttyVt.GHOSTTY_SCROLL_VIEWPORT_DELTA_PROMPT())
                    GhosttyTerminalScrollViewportValue.delta_prompt(value, to.prompts)
                }
                is GhosttyScrollViewport.Row -> {
                    GhosttyTerminalScrollViewport.tag(behavior, GhosttyVt.GHOSTTY_SCROLL_VIEWPORT_ROW())
                    GhosttyTerminalScrollViewportValue.row(value, to.row)
                }
            }
            GhosttyVt.ghostty_terminal_scroll_viewport(handle, behavior)
        }
    }

    /** Where the viewport is in the history and the screen. */
    public val scrollbar: GhosttyScrollbar
        get() {
            requireOpen()
            return run {
                val out = scratch(GhosttyTerminalScrollbar.layout().byteSize())
                checkResult(
                    GhosttyVt.ghostty_terminal_get(handle, GhosttyVt.GHOSTTY_TERMINAL_DATA_SCROLLBAR(), out),
                    "ghostty_terminal_get(SCROLLBAR)",
                )
                GhosttyScrollbar(
                    total = GhosttyTerminalScrollbar.total(out),
                    offset = GhosttyTerminalScrollbar.offset(out),
                    len = GhosttyTerminalScrollbar.len(out),
                )
            }
        }

    /** The screen that the program is on: the alternate one, of vim or less, has no history. */
    public val activeScreen: GhosttyScreen
        get() {
            requireOpen()
            return run {
                val out = scratch(ValueLayout.JAVA_INT.byteSize())
                checkResult(
                    GhosttyVt.ghostty_terminal_get(handle, GhosttyVt.GHOSTTY_TERMINAL_DATA_ACTIVE_SCREEN(), out),
                    "ghostty_terminal_get(ACTIVE_SCREEN)",
                )
                if (out.get(ValueLayout.JAVA_INT, 0) == GhosttyVt.GHOSTTY_TERMINAL_SCREEN_ALTERNATE()) GhosttyScreen.ALTERNATE else GhosttyScreen.PRIMARY
            }
        }

    // The storage of the images of the active screen, which is borrowed from the terminal: it is valid until the next call that
    // changes the terminal. `null` when the protocol is off.
    private fun kittyGraphics(): MemorySegment? {
        requireOpen()
        return run {
            val out = scratch(ValueLayout.ADDRESS.byteSize())
            val result = GhosttyVt.ghostty_terminal_get(handle, GhosttyVt.GHOSTTY_TERMINAL_DATA_KITTY_GRAPHICS(), out)
            if (result == GhosttyVt.GHOSTTY_NO_VALUE()) return@run null
            checkResult(result, "ghostty_terminal_get(KITTY_GRAPHICS)")
            out.get(ValueLayout.ADDRESS, 0).takeIf { it != MemorySegment.NULL }
        }
    }

    /**
     * Which version of the images the terminal has, which changes whenever an image is sent, placed or deleted: `0` if
     * none ever was. Where the images are shown also changes when the screen scrolls, with no change of this.
     */
    public val kittyGeneration: Long
        get() {
            val graphics = kittyGraphics() ?: return 0
            return run {
                val out = scratch(ValueLayout.JAVA_LONG.byteSize())
                val result = GhosttyVt.ghostty_kitty_graphics_get(graphics, GhosttyVt.GHOSTTY_KITTY_GRAPHICS_DATA_GENERATION(), out)
                if (result == GhosttyVt.GHOSTTY_NO_VALUE()) return@run 0L
                checkResult(result, "ghostty_kitty_graphics_get(GENERATION)")
                out.get(ValueLayout.JAVA_LONG, 0)
            }
        }

    /**
     * Where the images are shown on the active screen, with the geometry that the terminal worked out for the size of a
     * cell it was given. The virtual ones (placed by characters) and the ones that are not in the viewport are in it,
     * flagged: it is for the caller to skip them.
     */
    public fun kittyPlacements(): List<GhosttyImagePlacement> {
        val graphics = kittyGraphics() ?: return emptyList()
        return Arena.ofConfined().use { arena ->
            val slot = arena.allocate(ValueLayout.ADDRESS)
            checkResult(GhosttyVt.ghostty_kitty_graphics_placement_iterator_new(MemorySegment.NULL, slot), "ghostty_kitty_graphics_placement_iterator_new")
            val iterator = slot.get(ValueLayout.ADDRESS, 0)
            try {
                val result = GhosttyVt.ghostty_kitty_graphics_get(graphics, GhosttyVt.GHOSTTY_KITTY_GRAPHICS_DATA_PLACEMENT_ITERATOR(), slot)
                if (result == GhosttyVt.GHOSTTY_NO_VALUE()) return@use emptyList()
                checkResult(result, "ghostty_kitty_graphics_get(PLACEMENT_ITERATOR)")

                val placements = ArrayList<GhosttyImagePlacement>()
                while (GhosttyVt.ghostty_kitty_graphics_placement_next(iterator)) {
                    readPlacement(arena, graphics, iterator)?.let { placements += it }
                }
                placements
            } finally {
                GhosttyVt.ghostty_kitty_graphics_placement_iterator_free(iterator)
            }
        }
    }

    private fun readPlacement(arena: Arena, graphics: MemorySegment, iterator: MemorySegment): GhosttyImagePlacement? {
        fun int(data: Int): Int {
            val out = arena.allocate(ValueLayout.JAVA_INT)
            checkResult(GhosttyVt.ghostty_kitty_graphics_placement_get(iterator, data, out), "ghostty_kitty_graphics_placement_get($data)")
            return out.get(ValueLayout.JAVA_INT, 0)
        }

        val imageId = int(GhosttyVt.GHOSTTY_KITTY_GRAPHICS_PLACEMENT_DATA_IMAGE_ID())
        val image = GhosttyVt.ghostty_kitty_graphics_image(graphics, imageId)
        // Its image is gone, or still arriving: there is nothing to show.
        if (image == MemorySegment.NULL) return null

        val virtual = arena.allocate(ValueLayout.JAVA_BOOLEAN)
        checkResult(
            GhosttyVt.ghostty_kitty_graphics_placement_get(iterator, GhosttyVt.GHOSTTY_KITTY_GRAPHICS_PLACEMENT_DATA_IS_VIRTUAL(), virtual),
            "ghostty_kitty_graphics_placement_get(IS_VIRTUAL)",
        )
        val info = arena.allocate(GhosttyKittyGraphicsPlacementRenderInfo.layout())
        GhosttyKittyGraphicsPlacementRenderInfo.size(info, GhosttyKittyGraphicsPlacementRenderInfo.layout().byteSize())
        val isVirtual = virtual.get(ValueLayout.JAVA_BOOLEAN, 0)
        // A virtual placement has no position to give.
        val hasInfo = !isVirtual &&
            GhosttyVt.ghostty_kitty_graphics_placement_render_info(iterator, image, handle, info) == GhosttyVt.GHOSTTY_SUCCESS()

        return GhosttyImagePlacement(
            imageId = imageId,
            placementId = int(GhosttyVt.GHOSTTY_KITTY_GRAPHICS_PLACEMENT_DATA_PLACEMENT_ID()),
            z = int(GhosttyVt.GHOSTTY_KITTY_GRAPHICS_PLACEMENT_DATA_Z()),
            isVirtual = isVirtual,
            imageGeneration = imageGeneration(arena, image),
            viewportCol = if (hasInfo) GhosttyKittyGraphicsPlacementRenderInfo.viewport_col(info) else 0,
            viewportRow = if (hasInfo) GhosttyKittyGraphicsPlacementRenderInfo.viewport_row(info) else 0,
            visible = hasInfo && GhosttyKittyGraphicsPlacementRenderInfo.viewport_visible(info),
            xOffset = int(GhosttyVt.GHOSTTY_KITTY_GRAPHICS_PLACEMENT_DATA_X_OFFSET()),
            yOffset = int(GhosttyVt.GHOSTTY_KITTY_GRAPHICS_PLACEMENT_DATA_Y_OFFSET()),
            pixelWidth = if (hasInfo) GhosttyKittyGraphicsPlacementRenderInfo.pixel_width(info) else 0,
            pixelHeight = if (hasInfo) GhosttyKittyGraphicsPlacementRenderInfo.pixel_height(info) else 0,
            gridCols = if (hasInfo) GhosttyKittyGraphicsPlacementRenderInfo.grid_cols(info) else 0,
            gridRows = if (hasInfo) GhosttyKittyGraphicsPlacementRenderInfo.grid_rows(info) else 0,
            sourceX = if (hasInfo) GhosttyKittyGraphicsPlacementRenderInfo.source_x(info) else 0,
            sourceY = if (hasInfo) GhosttyKittyGraphicsPlacementRenderInfo.source_y(info) else 0,
            sourceWidth = if (hasInfo) GhosttyKittyGraphicsPlacementRenderInfo.source_width(info) else 0,
            sourceHeight = if (hasInfo) GhosttyKittyGraphicsPlacementRenderInfo.source_height(info) else 0,
            columns = int(GhosttyVt.GHOSTTY_KITTY_GRAPHICS_PLACEMENT_DATA_COLUMNS()),
            rows = int(GhosttyVt.GHOSTTY_KITTY_GRAPHICS_PLACEMENT_DATA_ROWS()),
        )
    }

    private fun imageGeneration(arena: Arena, image: MemorySegment): Long {
        val out = arena.allocate(ValueLayout.JAVA_LONG)
        checkResult(
            GhosttyVt.ghostty_kitty_graphics_image_get(image, GhosttyVt.GHOSTTY_KITTY_IMAGE_DATA_GENERATION(), out),
            "ghostty_kitty_graphics_image_get(GENERATION)",
        )
        return out.get(ValueLayout.JAVA_LONG, 0)
    }

    /**
     * A copy of the image with the id [id], or `null` if there is none or its pixels have not all arrived: the terminal's
     * own copy is only valid until it changes.
     */
    public fun kittyImage(id: Int): GhosttyImage? {
        val graphics = kittyGraphics() ?: return null
        return Arena.ofConfined().use { arena ->
            val image = GhosttyVt.ghostty_kitty_graphics_image(graphics, id)
            if (image == MemorySegment.NULL) return@use null

            fun int(data: Int): Int {
                val out = arena.allocate(ValueLayout.JAVA_INT)
                checkResult(GhosttyVt.ghostty_kitty_graphics_image_get(image, data, out), "ghostty_kitty_graphics_image_get($data)")
                return out.get(ValueLayout.JAVA_INT, 0)
            }

            val pointer = arena.allocate(ValueLayout.ADDRESS)
            val result = GhosttyVt.ghostty_kitty_graphics_image_get(image, GhosttyVt.GHOSTTY_KITTY_IMAGE_DATA_DATA_PTR(), pointer)
            // The pixels are pending.
            if (result == GhosttyVt.GHOSTTY_NO_VALUE()) return@use null
            checkResult(result, "ghostty_kitty_graphics_image_get(DATA_PTR)")
            val length = arena.allocate(ValueLayout.JAVA_LONG)
            checkResult(
                GhosttyVt.ghostty_kitty_graphics_image_get(image, GhosttyVt.GHOSTTY_KITTY_IMAGE_DATA_DATA_LEN(), length),
                "ghostty_kitty_graphics_image_get(DATA_LEN)",
            )
            val size = length.get(ValueLayout.JAVA_LONG, 0)
            val bytes = pointer.get(ValueLayout.ADDRESS, 0).reinterpret(size).toArray(ValueLayout.JAVA_BYTE)

            GhosttyImage(
                id = id,
                generation = imageGeneration(arena, image),
                width = int(GhosttyVt.GHOSTTY_KITTY_IMAGE_DATA_WIDTH()),
                height = int(GhosttyVt.GHOSTTY_KITTY_IMAGE_DATA_HEIGHT()),
                format = when (int(GhosttyVt.GHOSTTY_KITTY_IMAGE_DATA_FORMAT())) {
                    GhosttyVt.GHOSTTY_KITTY_IMAGE_FORMAT_RGB() -> GhosttyImageFormat.RGB
                    GhosttyVt.GHOSTTY_KITTY_IMAGE_FORMAT_GRAY_ALPHA() -> GhosttyImageFormat.GRAY_ALPHA
                    GhosttyVt.GHOSTTY_KITTY_IMAGE_FORMAT_GRAY() -> GhosttyImageFormat.GRAY
                    GhosttyVt.GHOSTTY_KITTY_IMAGE_FORMAT_PNG() -> GhosttyImageFormat.PNG
                    else -> GhosttyImageFormat.RGBA
                },
                bytes = bytes,
            )
        }
    }

    /** Whether the program asked to be told about the mouse (X10, normal, button or any-event tracking). */
    public val mouseTracking: Boolean
        get() {
            requireOpen()
            return run {
                val out = scratch(ValueLayout.JAVA_BOOLEAN.byteSize())
                checkResult(
                    GhosttyVt.ghostty_terminal_get(handle, GhosttyVt.GHOSTTY_TERMINAL_DATA_MOUSE_TRACKING(), out),
                    "ghostty_terminal_get(MOUSE_TRACKING)",
                )
                out.get(ValueLayout.JAVA_BOOLEAN, 0)
            }
        }

    /** The shape of the pointer that the program asked for (OSC 22): the text cursor until it says otherwise. */
    public val mouseShape: GhosttyMouseShape
        get() {
            requireOpen()
            return run {
                val out = scratch(ValueLayout.JAVA_INT.byteSize())
                checkResult(
                    GhosttyVt.ghostty_terminal_get(handle, GhosttyVt.GHOSTTY_TERMINAL_DATA_MOUSE_SHAPE(), out),
                    "ghostty_terminal_get(MOUSE_SHAPE)",
                )
                GhosttyMouseShape.entries.getOrElse(out.get(ValueLayout.JAVA_INT, 0)) { GhosttyMouseShape.DEFAULT }
            }
        }

    /**
     * The text of the selection, which is what is copied ([format] says how: the characters only, the characters with the escape sequences
     * that give them their styles and colors, or HTML): soft-wrapped lines are joined and the blanks that end a
     * line are dropped. `null` when nothing is selected.
     */
    public fun selectedText(format: GhosttyTextFormat = GhosttyTextFormat.PLAIN): String? {
        requireOpen()
        // Not a selection of its own: the one of the terminal.
        return formatSelection(MemorySegment.NULL, unwrap = true, format = format)
    }

    /**
     * The text of the rows [first] to [last], both included, of the history and the screen: row 0 is the top of the history, in the
     * space of [GhosttyScrollbar.offset] and of [GhosttyScrollViewport.Row]. The viewport does not move. A soft-wrapped line is
     * joined when [unwrap], and the blanks that end a line are dropped.
     *
     * Finding a row walks the pages of the history, which is slow for a very large one: read it in pieces.
     *
     * @throws IllegalArgumentException If the rows are not in the history and the screen.
     */
    public fun readRows(first: Long, last: Long, unwrap: Boolean = true): String {
        requireOpen()
        val total = totalRows
        require(first in 0..last && last < total) { "The rows $first to $last are not in the $total rows of the terminal" }
        return Arena.ofConfined().use { arena ->
            val selection = arena.allocate(GhosttySelection.layout())
            GhosttySelection.size(selection, GhosttySelection.layout().byteSize())
            GhosttySelection.rectangle(selection, false)
            val start = pointRef(arena, GhosttyVt.GHOSTTY_POINT_TAG_SCREEN(), 0, first)
            val end = pointRef(arena, GhosttyVt.GHOSTTY_POINT_TAG_SCREEN(), cols - 1, last)
            MemorySegment.copy(start, 0, GhosttySelection.start(selection), 0, GhosttyGridRef.layout().byteSize())
            MemorySegment.copy(end, 0, GhosttySelection.end(selection), 0, GhosttyGridRef.layout().byteSize())
            formatSelection(selection, unwrap) ?: ""
        }
    }

    /** All the text of the history and the screen, as [readRows] gives it. Empty when there is none. */
    internal fun allText(unwrap: Boolean = true): String {
        requireOpen()
        return Arena.ofConfined().use { arena ->
            val selection = arena.allocate(GhosttySelection.layout())
            GhosttySelection.size(selection, GhosttySelection.layout().byteSize())
            val result = GhosttyVt.ghostty_terminal_select_all(handle, selection)
            if (result == GhosttyVt.GHOSTTY_NO_VALUE()) return@use ""
            checkResult(result, "ghostty_terminal_select_all")
            formatSelection(selection, unwrap) ?: ""
        }
    }

    /**
     * Selects all the text of the history and the screen, which makes it the terminal's selection.
     *
     * @return Whether there was any text to select.
     */
    public fun selectAll(): Boolean {
        requireOpen()
        return Arena.ofConfined().use { arena ->
            val selection = arena.allocate(GhosttySelection.layout())
            GhosttySelection.size(selection, GhosttySelection.layout().byteSize())
            val result = GhosttyVt.ghostty_terminal_select_all(handle, selection)
            if (result == GhosttyVt.GHOSTTY_NO_VALUE()) return@use false
            checkResult(result, "ghostty_terminal_select_all")
            installSelection(selection)
            true
        }
    }

    /**
     * Moves the end of the selection that was last moved (the one that a drag moves) as [adjustment] says, which keeps the selection the
     * terminal's own: it is what the keys do that extend a selection.
     *
     * @return Whether there was a selection to adjust.
     */
    public fun adjustSelection(adjustment: GhosttySelectionAdjust): Boolean {
        requireOpen()
        return Arena.ofConfined().use { arena ->
            val selection = arena.allocate(GhosttySelection.layout())
            GhosttySelection.size(selection, GhosttySelection.layout().byteSize())
            val found = GhosttyVt.ghostty_terminal_get(handle, GhosttyVt.GHOSTTY_TERMINAL_DATA_SELECTION(), selection)
            if (found == GhosttyVt.GHOSTTY_NO_VALUE()) return@use false
            checkResult(found, "ghostty_terminal_get(SELECTION)")
            checkResult(GhosttyVt.ghostty_terminal_selection_adjust(handle, selection, adjustment.ordinal), "ghostty_terminal_selection_adjust")
            installSelection(selection)
            true
        }
    }

    /** The column of the cursor on the screen, from 0. */
    public val cursorColumn: Int get() = getUInt16(GhosttyVt.GHOSTTY_TERMINAL_DATA_CURSOR_X())

    /** The row of the cursor on the screen, from 0. */
    public val cursorRow: Int get() = getUInt16(GhosttyVt.GHOSTTY_TERMINAL_DATA_CURSOR_Y())

    /** How many rows there are in the history and the screen. */
    public val totalRows: Long
        get() {
            requireOpen()
            return run {
                val out = scratch(ValueLayout.JAVA_LONG.byteSize())
                checkResult(GhosttyVt.ghostty_terminal_get(handle, GhosttyVt.GHOSTTY_TERMINAL_DATA_TOTAL_ROWS(), out), "ghostty_terminal_get(TOTAL_ROWS)")
                out.get(ValueLayout.JAVA_LONG, 0)
            }
        }

    /**
     * A reference to where the cursor is, which follows its row as the terminal scrolls, reflows and prunes. It has to be closed, and
     * as long as the terminal is not: see [GhosttyTrackedRef].
     */
    public fun trackCursor(): GhosttyTrackedRef {
        requireOpen()
        return Arena.ofConfined().use { arena ->
            val point = arena.allocate(GhosttyPoint.layout())
            GhosttyPoint.tag(point, GhosttyVt.GHOSTTY_POINT_TAG_ACTIVE())
            val coordinate = GhosttyPointValue.coordinate(GhosttyPoint.value(point))
            GhosttyPointCoordinate.x(coordinate, getUInt16(GhosttyVt.GHOSTTY_TERMINAL_DATA_CURSOR_X()).toShort())
            GhosttyPointCoordinate.y(coordinate, getUInt16(GhosttyVt.GHOSTTY_TERMINAL_DATA_CURSOR_Y()))
            val out = arena.allocate(ValueLayout.ADDRESS)
            checkResult(GhosttyVt.ghostty_terminal_grid_ref_track(handle, point, out), "ghostty_terminal_grid_ref_track")
            GhosttyTrackedRef(out.get(ValueLayout.ADDRESS, 0))
        }
    }

    /**
     * The output of the command that [ref] is in, as the shell marked it (OSC 133), or `null` when [ref] is not in the output of a
     * command, or is gone.
     */
    public fun commandOutput(ref: GhosttyTrackedRef, unwrap: Boolean = true): String? {
        requireOpen()
        if (!ref.hasValue) return null
        return Arena.ofConfined().use { arena ->
            val snapshot = arena.allocate(GhosttyGridRef.layout())
            GhosttyGridRef.size(snapshot, GhosttyGridRef.layout().byteSize())
            checkResult(GhosttyVt.ghostty_tracked_grid_ref_snapshot(ref.handle, snapshot), "ghostty_tracked_grid_ref_snapshot")
            val selection = arena.allocate(GhosttySelection.layout())
            GhosttySelection.size(selection, GhosttySelection.layout().byteSize())
            val result = GhosttyVt.ghostty_terminal_select_output(handle, snapshot, selection)
            if (result == GhosttyVt.GHOSTTY_NO_VALUE()) return@use null
            checkResult(result, "ghostty_terminal_select_output")
            formatSelection(selection, unwrap)
        }
    }

    /**
     * Selects the output of the command that [ref] is in, as the shell marked it (OSC 133), which makes it the terminal's selection.
     *
     * @return Whether [ref] is in the output of a command: nothing is selected when it is not.
     */
    public fun selectCommandOutput(ref: GhosttyTrackedRef): Boolean {
        requireOpen()
        if (!ref.hasValue) return false
        return Arena.ofConfined().use { arena ->
            val snapshot = arena.allocate(GhosttyGridRef.layout())
            GhosttyGridRef.size(snapshot, GhosttyGridRef.layout().byteSize())
            checkResult(GhosttyVt.ghostty_tracked_grid_ref_snapshot(ref.handle, snapshot), "ghostty_tracked_grid_ref_snapshot")
            val selection = arena.allocate(GhosttySelection.layout())
            GhosttySelection.size(selection, GhosttySelection.layout().byteSize())
            val result = GhosttyVt.ghostty_terminal_select_output(handle, snapshot, selection)
            if (result == GhosttyVt.GHOSTTY_NO_VALUE()) return@use false
            checkResult(result, "ghostty_terminal_select_output")
            installSelection(selection)
            true
        }
    }

    // The plain text of [selection], or of the terminal's own when it is NULL, which is `null` when there is none.
    private fun formatSelection(selection: MemorySegment, unwrap: Boolean, format: GhosttyTextFormat = GhosttyTextFormat.PLAIN): String? =
        Arena.ofConfined().use { arena ->
            val options = arena.allocate(GhosttyTerminalSelectionFormatOptions.layout())
            GhosttyTerminalSelectionFormatOptions.size(options, GhosttyTerminalSelectionFormatOptions.layout().byteSize())
            GhosttyTerminalSelectionFormatOptions.emit(
                options,
                when (format) {
                    GhosttyTextFormat.PLAIN -> GhosttyVt.GHOSTTY_FORMATTER_FORMAT_PLAIN()
                    GhosttyTextFormat.VT -> GhosttyVt.GHOSTTY_FORMATTER_FORMAT_VT()
                    GhosttyTextFormat.HTML -> GhosttyVt.GHOSTTY_FORMATTER_FORMAT_HTML()
                },
            )
            GhosttyTerminalSelectionFormatOptions.unwrap(options, unwrap)
            GhosttyTerminalSelectionFormatOptions.trim(options, true)
            GhosttyTerminalSelectionFormatOptions.selection(options, selection)

            val pointer = arena.allocate(ValueLayout.ADDRESS)
            val length = arena.allocate(ValueLayout.JAVA_LONG)
            val result = GhosttyVt.ghostty_terminal_selection_format_alloc(handle, MemorySegment.NULL, options, pointer, length)
            if (result == GhosttyVt.GHOSTTY_NO_VALUE()) return@use null
            checkResult(result, "ghostty_terminal_selection_format_alloc")

            val size = length.get(ValueLayout.JAVA_LONG, 0)
            val buffer = pointer.get(ValueLayout.ADDRESS, 0)
            if (size == 0L || buffer == MemorySegment.NULL) return@use ""
            try {
                String(buffer.reinterpret(size).toArray(ValueLayout.JAVA_BYTE), Charsets.UTF_8)
            } finally {
                GhosttyVt.ghostty_free(MemorySegment.NULL, buffer, size)
            }
        }

    /**
     * What to write to the program for a paste of [text], according to the modes that it set: wrapped in the markers
     * of bracketed paste (mode 2004) if it asked for them, else with its newlines as carriage returns, which is what the
     * Enter key sends. The control bytes that could be taken for commands (escape, nul, delete...) become spaces.
     */
    public fun encodePaste(text: String): ByteArray {
        requireOpen()
        if (text.isEmpty()) return ByteArray(0)
        val bracketed = isModeEnabled(GhosttyMode.BRACKETED_PASTE)
        return Arena.ofConfined().use { arena ->
            // The encoder works in place on its input: it is given a copy.
            val bytes = text.toByteArray(Charsets.UTF_8)
            val data = arena.allocate(bytes.size.toLong())
            MemorySegment.copy(bytes, 0, data, ValueLayout.JAVA_BYTE, 0, bytes.size)

            val written = arena.allocate(ValueLayout.JAVA_LONG)
            // The markers of bracketed paste are a few bytes more than the text.
            var buffer = arena.allocate(bytes.size + PASTE_MARKERS_CAPACITY)
            var result = GhosttyVt.ghostty_paste_encode(data, bytes.size.toLong(), bracketed, buffer, buffer.byteSize(), written)
            if (result == GhosttyVt.GHOSTTY_OUT_OF_SPACE()) {
                // The length is the capacity that is needed, and the text was changed: encode a fresh copy.
                MemorySegment.copy(bytes, 0, data, ValueLayout.JAVA_BYTE, 0, bytes.size)
                buffer = arena.allocate(written.get(ValueLayout.JAVA_LONG, 0))
                result = GhosttyVt.ghostty_paste_encode(data, bytes.size.toLong(), bracketed, buffer, buffer.byteSize(), written)
            }
            checkResult(result, "ghostty_paste_encode")
            buffer.asSlice(0, written.get(ValueLayout.JAVA_LONG, 0)).toArray(ValueLayout.JAVA_BYTE)
        }
    }

    /**
     * Whether pasting [text] could run commands that the user did not see: it has a newline or a carriage return, which a program that did
     * not ask for bracketed paste takes for Enter, or the marker that ends a bracketed paste, which lets the rest out of it. In a bracketed
     * paste (mode 2004) the line breaks are the program's to take as text, so only the marker counts. The host asks the user before it pastes
     * what is unsafe.
     */
    public fun isPasteUnsafe(text: String): Boolean {
        requireOpen()
        if (text.isEmpty()) return false
        val unsafe = Arena.ofConfined().use { arena ->
            val bytes = text.toByteArray(Charsets.UTF_8)
            val data = arena.allocate(bytes.size.toLong())
            MemorySegment.copy(bytes, 0, data, ValueLayout.JAVA_BYTE, 0, bytes.size)
            !GhosttyVt.ghostty_paste_is_safe(data, bytes.size.toLong())
        }
        // libghostty's check takes a carriage return for a newline too (Ghostty's #14687): it is Enter as well.
        return unsafe && (!isModeEnabled(GhosttyMode.BRACKETED_PASTE) || BRACKETED_PASTE_END in text)
    }

    // How much of an unfinished escape sequence is kept, so that a snapshot taken in the middle of one can be restored. 0 keeps none.
    internal fun setContinuationMaxBytes(bytes: Long) {
        requireOpen()
        setLimit(GhosttyVt.GHOSTTY_TERMINAL_OPT_CONTINUATION_MAX_BYTES(), bytes, "CONTINUATION_MAX_BYTES")
    }

    /**
     * Limits the memory that the history may take to [bytes], about: libghostty prunes by pages, so it is somewhat more, and only
     * whole pages of history are removed, at once when the limit is lowered. `0` keeps no history.
     */
    public fun setScrollbackMaxBytes(bytes: Long) {
        requireOpen()
        require(bytes >= 0) { "A limit is not negative: $bytes" }
        setLimit(GhosttyVt.GHOSTTY_TERMINAL_OPT_SCROLLBACK_MAX_BYTES(), bytes, "SCROLLBACK_MAX_BYTES")
    }

    /**
     * Limits the memory that the images of the Kitty graphics protocol may take to [bytes]. Lowering it removes the images that no
     * longer fit, and `0` removes them all and turns the protocol off.
     */
    public fun setImageStorageLimit(bytes: Long) {
        requireOpen()
        require(bytes >= 0) { "A limit is not negative: $bytes" }
        setLimit(GhosttyVt.GHOSTTY_TERMINAL_OPT_KITTY_IMAGE_STORAGE_LIMIT(), bytes, "KITTY_IMAGE_STORAGE_LIMIT")
    }

    /**
     * Which ways a program may give the Kitty graphics protocol an image other than in the escape sequence (`t=f`, `t=t`, `t=s`): they are
     * all off in libghostty, which answers `EINVAL: unsupported medium`, and a program that sends them is told so.
     *
     * Each one lets a program that runs in the terminal make the terminal read something of the user's machine and draw it, so they are the
     * host's to allow.
     *
     * @param file A path to any file the user can read (`t=f`).
     * @param temporaryDirectory The directory that a temporary file (`t=t`, which the terminal deletes once it has read it) must be in, or
     * `null` for none. libghostty also wants the name to hold `tty-graphics-protocol`.
     * @param sharedMemory A POSIX shared memory object by name (`t=s`).
     */
    public fun setKittyImageMediums(file: Boolean, temporaryDirectory: String?, sharedMemory: Boolean) {
        Arena.ofConfined().use { arena ->
            for ((option, enabled, name) in listOf(
                Triple(GhosttyVt.GHOSTTY_TERMINAL_OPT_KITTY_IMAGE_MEDIUM_FILE(), file, "KITTY_IMAGE_MEDIUM_FILE"),
                Triple(GhosttyVt.GHOSTTY_TERMINAL_OPT_KITTY_IMAGE_MEDIUM_SHARED_MEM(), sharedMemory, "KITTY_IMAGE_MEDIUM_SHARED_MEM"),
            )) {
                val flag = arena.allocate(ValueLayout.JAVA_BOOLEAN)
                flag.set(ValueLayout.JAVA_BOOLEAN, 0, enabled)
                checkResult(GhosttyVt.ghostty_terminal_set(handle, option, flag), "ghostty_terminal_set($name)")
            }
            // A null pointer turns the temporary file medium off; the string is copied by the terminal.
            var value = MemorySegment.NULL
            if (temporaryDirectory != null) {
                val bytes = arena.allocateFrom(temporaryDirectory)
                value = arena.allocate(GhosttyString.layout())
                GhosttyString.ptr(value, bytes)
                GhosttyString.len(value, bytes.byteSize() - 1)
            }
            checkResult(
                GhosttyVt.ghostty_terminal_set(handle, GhosttyVt.GHOSTTY_TERMINAL_OPT_KITTY_IMAGE_MEDIUM_TEMP_FILE(), value),
                "ghostty_terminal_set(KITTY_IMAGE_MEDIUM_TEMP_FILE)",
            )
        }
    }

    /**
     * Whether the cursor blinks until a program says otherwise (DECSCUSR), and when it goes back to the default (`CSI 0 q`). A cursor that no
     * program has set follows it as soon as it changes.
     */
    public fun setDefaultCursorBlink(blink: Boolean) {
        Arena.ofConfined().use { arena ->
            val flag = arena.allocate(ValueLayout.JAVA_BOOLEAN)
            flag.set(ValueLayout.JAVA_BOOLEAN, 0, blink)
            checkResult(
                GhosttyVt.ghostty_terminal_set(handle, GhosttyVt.GHOSTTY_TERMINAL_OPT_DEFAULT_CURSOR_BLINK(), flag),
                "ghostty_terminal_set(DEFAULT_CURSOR_BLINK)",
            )
        }
    }

    /** The shape of the cursor until a program sets one (DECSCUSR), and when it goes back to the default (`CSI 0 q`). Not the hollow block. */
    public fun setDefaultCursorStyle(style: GhosttyCursorStyle) {
        require(style != GhosttyCursorStyle.BLOCK_HOLLOW) { "A program does not set the hollow block" }
        Arena.ofConfined().use { arena ->
            val value = arena.allocate(ValueLayout.JAVA_INT)
            value.set(
                ValueLayout.JAVA_INT, 0,
                when (style) {
                    GhosttyCursorStyle.BAR -> GhosttyVt.GHOSTTY_TERMINAL_CURSOR_STYLE_BAR()
                    GhosttyCursorStyle.UNDERLINE -> GhosttyVt.GHOSTTY_TERMINAL_CURSOR_STYLE_UNDERLINE()
                    else -> GhosttyVt.GHOSTTY_TERMINAL_CURSOR_STYLE_BLOCK()
                },
            )
            checkResult(
                GhosttyVt.ghostty_terminal_set(handle, GhosttyVt.GHOSTTY_TERMINAL_OPT_DEFAULT_CURSOR_STYLE(), value),
                "ghostty_terminal_set(DEFAULT_CURSOR_STYLE)",
            )
        }
    }

    private fun setLimit(option: Int, bytes: Long, name: String) {
        Arena.ofConfined().use { arena ->
            val limit = arena.allocate(ValueLayout.JAVA_LONG)
            limit.set(ValueLayout.JAVA_LONG, 0, bytes)
            checkResult(GhosttyVt.ghostty_terminal_set(handle, option, limit), "ghostty_terminal_set($name)")
        }
    }

    /**
     * A token that changes when the terminal does: compressing is worth doing again once it has, and after the terminal has been left
     * alone for a while. Only equality means anything.
     */
    public fun compressionActivity(): Long {
        requireOpen()
        return run {
            val out = scratch(ValueLayout.JAVA_LONG.byteSize())
            checkResult(GhosttyVt.ghostty_terminal_compression_activity(handle, out), "ghostty_terminal_compression_activity")
            out.get(ValueLayout.JAVA_LONG, 0)
        }
    }

    /**
     * Compresses scrollback that is old enough, to free memory: it changes how the history is stored and nothing that it holds, and
     * reading it decompresses it as needed. The caller serializes it with every other access to the terminal.
     */
    public fun compress(mode: GhosttyCompressionMode): GhosttyCompressionResult {
        requireOpen()
        return run {
            val out = scratch(ValueLayout.JAVA_INT.byteSize())
            val native = when (mode) {
                GhosttyCompressionMode.INCREMENTAL -> GhosttyVt.GHOSTTY_TERMINAL_COMPRESSION_MODE_INCREMENTAL()
                GhosttyCompressionMode.FULL -> GhosttyVt.GHOSTTY_TERMINAL_COMPRESSION_MODE_FULL()
            }
            checkResult(GhosttyVt.ghostty_terminal_compress(handle, native, out), "ghostty_terminal_compress")
            when (out.get(ValueLayout.JAVA_INT, 0)) {
                GhosttyVt.GHOSTTY_TERMINAL_COMPRESSION_RESULT_PENDING() -> GhosttyCompressionResult.PENDING
                GhosttyVt.GHOSTTY_TERMINAL_COMPRESSION_RESULT_COMPLETE() -> GhosttyCompressionResult.COMPLETE
                else -> GhosttyCompressionResult.UNSUPPORTED
            }
        }
    }

    /** How much native memory the terminal holds. */
    public fun memoryUsage(): GhosttyMemoryUsage {
        requireOpen()
        return Arena.ofConfined().use { arena ->
            val usage = arena.allocate(GhosttyTerminalMemoryUsage.layout())
            GhosttyTerminalMemoryUsage.size(usage, GhosttyTerminalMemoryUsage.layout().byteSize())
            checkResult(GhosttyVt.ghostty_terminal_get(handle, GhosttyVt.GHOSTTY_TERMINAL_DATA_MEMORY_USAGE(), usage), "ghostty_terminal_get(MEMORY_USAGE)")
            GhosttyMemoryUsage(
                compressionSupported = GhosttyTerminalMemoryUsage.compression_supported(usage),
                residentBytes = GhosttyTerminalMemoryUsage.primary_resident_bytes(usage) + GhosttyTerminalMemoryUsage.alternate_resident_bytes(usage),
                virtualBytes = GhosttyTerminalMemoryUsage.primary_virtual_bytes(usage) + GhosttyTerminalMemoryUsage.alternate_virtual_bytes(usage),
                compressedPages = GhosttyTerminalMemoryUsage.primary_compressed_pages(usage) + GhosttyTerminalMemoryUsage.alternate_compressed_pages(usage),
                compressedBytes = GhosttyTerminalMemoryUsage.primary_compressed_bytes(usage) + GhosttyTerminalMemoryUsage.alternate_compressed_bytes(usage),
                imageBytes = GhosttyTerminalMemoryUsage.primary_image_bytes(usage) + GhosttyTerminalMemoryUsage.alternate_image_bytes(usage),
            )
        }
    }

    /**
     * Everything that the terminal is, as bytes that [restore] makes a terminal of: both screens and their history, the cursor, the modes,
     * the colors, the title and the directory. Not the images of the Kitty protocol, the selection, the viewport, nor what the
     * wrapper sets (the callbacks and the options).
     *
     * It must not run while the terminal is written to or used: the caller serializes it, as it does every call. It takes as long as the
     * history is large. It is [GhosttySnapshotException] if the program is in the middle of an escape sequence, which is for a few
     * microseconds at a time: it can be tried again.
     */
    public fun snapshot(): ByteArray {
        requireOpen()
        return Arena.ofConfined().use { arena ->
            val pointer = arena.allocate(ValueLayout.ADDRESS)
            val length = arena.allocate(ValueLayout.JAVA_LONG)
            val result = GhosttyVt.ghostty_snapshot_encode_alloc(handle, MemorySegment.NULL, pointer, length)
            if (result == GhosttyVt.GHOSTTY_INVALID_VALUE()) throw GhosttySnapshotException("the program is in the middle of an escape sequence")
            snapshotResult(result, "encode it")
            val size = length.get(ValueLayout.JAVA_LONG, 0)
            val buffer = pointer.get(ValueLayout.ADDRESS, 0)
            try {
                buffer.reinterpret(size).toArray(ValueLayout.JAVA_BYTE)
            } finally {
                GhosttyVt.ghostty_free(MemorySegment.NULL, buffer, size)
            }
        }
    }

    /**
     * What to write to the program when the terminal gains ([focused]) or loses the focus, if it asked to be told (mode 1004):
     * `ESC [ I` or `ESC [ O`. Nothing when it did not.
     */
    public fun encodeFocus(focused: Boolean): ByteArray {
        requireOpen()
        if (!isModeEnabled(GhosttyMode.FOCUS_EVENT)) return ByteArray(0)
        val event = if (focused) GhosttyVt.GHOSTTY_FOCUS_GAINED() else GhosttyVt.GHOSTTY_FOCUS_LOST()
        return Arena.ofConfined().use { arena ->
            val written = arena.allocate(ValueLayout.JAVA_LONG)
            var buffer = arena.allocate(FOCUS_REPORT_CAPACITY)
            var result = GhosttyVt.ghostty_focus_encode(event, buffer, buffer.byteSize(), written)
            if (result == GhosttyVt.GHOSTTY_OUT_OF_SPACE()) {
                buffer = arena.allocate(written.get(ValueLayout.JAVA_LONG, 0))
                result = GhosttyVt.ghostty_focus_encode(event, buffer, buffer.byteSize(), written)
            }
            checkResult(result, "ghostty_focus_encode")
            buffer.asSlice(0, written.get(ValueLayout.JAVA_LONG, 0)).toArray(ValueLayout.JAVA_BYTE)
        }
    }

    /** Unselects what is selected. */
    public fun clearSelection() {
        requireOpen()
        checkResult(
            GhosttyVt.ghostty_terminal_set(handle, GhosttyVt.GHOSTTY_TERMINAL_OPT_SELECTION(), MemorySegment.NULL),
            "ghostty_terminal_set(SELECTION)",
        )
    }

    /** Makes the selection, a snapshot of references into this terminal, the one that is shown. */
    internal fun installSelection(selection: MemorySegment) {
        requireOpen()
        checkResult(
            GhosttyVt.ghostty_terminal_set(handle, GhosttyVt.GHOSTTY_TERMINAL_OPT_SELECTION(), selection),
            "ghostty_terminal_set(SELECTION)",
        )
    }

    /** The title that a program set (OSC 0 or 2), empty when none did. */
    public val title: String get() = readString(GhosttyVt.GHOSTTY_TERMINAL_DATA_TITLE(), "TITLE")

    /**
     * The working directory that the shell reported, as it sent it: a `file://` URI for OSC 7, a bare path for the others. Empty when
     * there is none.
     */
    public val pwd: String get() = readString(GhosttyVt.GHOSTTY_TERMINAL_DATA_PWD(), "PWD")

    private fun readString(data: Int, name: String): String {
        requireOpen()
        return run {
            val out = scratch(GhosttyString.layout().byteSize())
            checkResult(GhosttyVt.ghostty_terminal_get(handle, data, out), "ghostty_terminal_get($name)")
            copyString(out)
        }
    }

    /**
     * The address of the hyperlink (OSC 8) at [column] and [row] of the viewport, or `null` when the cell has none or is not on the
     * screen.
     */
    public fun hyperlinkAt(column: Int, row: Int): String? {
        requireOpen()
        if (column !in 0 until cols || row !in 0 until rows) return null
        return Arena.ofConfined().use { arena ->
            val ref = gridRef(arena, column, row)
            val length = arena.allocate(ValueLayout.JAVA_LONG)
            var buffer = arena.allocate(HYPERLINK_INITIAL_CAPACITY)
            var result = GhosttyVt.ghostty_grid_ref_hyperlink_uri(ref, buffer, buffer.byteSize(), length)
            if (result == GhosttyVt.GHOSTTY_OUT_OF_SPACE()) {
                buffer = arena.allocate(length.get(ValueLayout.JAVA_LONG, 0))
                result = GhosttyVt.ghostty_grid_ref_hyperlink_uri(ref, buffer, buffer.byteSize(), length)
            }
            checkResult(result, "ghostty_grid_ref_hyperlink_uri")
            val size = length.get(ValueLayout.JAVA_LONG, 0)
            if (size == 0L) null else String(buffer.asSlice(0, size).toArray(ValueLayout.JAVA_BYTE), Charsets.UTF_8)
        }
    }

    /**
     * Sets what answers a program that reads the clipboard (OSC 52 with `?`): it gets the text, or is refused when this returns
     * `null`. `null` clears it, and then reads are refused.
     *
     * It runs on the thread that called [vtWrite], and the terminal's input waits for it. Call it while nothing writes to the terminal.
     */
    public fun setClipboardReader(reader: ((GhosttyClipboardLocation) -> String?)?) {
        requireOpen()
        clipboardReader = reader
        if (reader == null) {
            checkResult(
                GhosttyVt.ghostty_terminal_set(handle, GhosttyVt.GHOSTTY_TERMINAL_OPT_CLIPBOARD_READ(), MemorySegment.NULL),
                "ghostty_terminal_set(CLIPBOARD_READ)",
            )
            return
        }
        val stub = clipboardReadStub ?: GhosttyTerminalClipboardReadFn.allocate(
            GhosttyTerminalClipboardReadFn.Function { _, _, read -> guarded("The clipboard read") { answerClipboardRead(read) } },
            callbacks,
        ).also { clipboardReadStub = it }
        checkResult(
            GhosttyVt.ghostty_terminal_set(handle, GhosttyVt.GHOSTTY_TERMINAL_OPT_CLIPBOARD_READ(), stub),
            "ghostty_terminal_set(CLIPBOARD_READ)",
        )
    }

    private fun answerClipboardRead(read: MemorySegment) {
        val struct = read.reinterpret(GhosttyClipboardRead.layout().byteSize())
        // The types that the program wants, in its order (OSC 52 asks for `text/plain`), and whether it also wants to know which there are
        // (OSC 5522's `.`): the reader only has text.
        val count = GhosttyClipboardRead.mimes_len(struct)
        val size = GhosttyString.layout().byteSize()
        val mimes = if (count == 0L) emptyList() else GhosttyClipboardRead.mimes(struct).reinterpret(count * size).let { array ->
            (0 until count).map { copyString(array.asSlice(it * size, size)) }
        }
        val list = GhosttyClipboardRead.list(struct)
        val chosen = chooseClipboardText(mimes)
        Arena.ofConfined().use { arena ->
            val reply = arena.allocate(GhosttyClipboardReadReply.layout())
            GhosttyClipboardReadReply.size(reply, GhosttyClipboardReadReply.layout().byteSize())
            if (mimes.isNotEmpty() && chosen == null && !list) {
                // Not the user's clipboard, and nothing that a program could be refused for asking: there is no image to give.
                GhosttyClipboardReadReply.result(reply, GhosttyVt.GHOSTTY_CLIPBOARD_READ_RESULT_UNSUPPORTED())
                GhosttyClipboardReadReplyFn.invoke(GhosttyClipboardRead.reply(struct), struct, reply)
                return
            }
            // Only reading the text is the user's to allow: the list says no more than that there is text, and is answered as Kitty does,
            // without asking.
            if (chosen != null) {
                val text = clipboardReader?.invoke(clipboardLocation(GhosttyClipboardRead.location(struct))) ?: return
                val bytes = text.encodeToByteArray()
                val data = arena.allocate(maxOf(bytes.size.toLong(), 1L))
                MemorySegment.copy(bytes, 0, data, ValueLayout.JAVA_BYTE, 0, bytes.size)
                val mime = arena.allocateFrom(mimes[chosen])
                val content = arena.allocate(GhosttyClipboardContent.layout())
                GhosttyString.ptr(GhosttyClipboardContent.mime(content), mime)
                GhosttyString.len(GhosttyClipboardContent.mime(content), mime.byteSize() - 1)
                GhosttyString.ptr(GhosttyClipboardContent.data(content), data)
                GhosttyString.len(GhosttyClipboardContent.data(content), bytes.size.toLong())
                GhosttyClipboardReadReply.contents(reply, content)
                GhosttyClipboardReadReply.contents_len(reply, 1)
            }
            if (list) {
                val mime = arena.allocateFrom("text/plain")
                val available = arena.allocate(GhosttyString.layout())
                GhosttyString.ptr(available, mime)
                GhosttyString.len(available, mime.byteSize() - 1)
                GhosttyClipboardReadReply.available(reply, available)
                GhosttyClipboardReadReply.available_len(reply, 1)
            }
            GhosttyClipboardReadReply.result(reply, GhosttyVt.GHOSTTY_CLIPBOARD_READ_RESULT_SUCCESS())
            GhosttyClipboardReadReplyFn.invoke(GhosttyClipboardRead.reply(struct), struct, reply)
        }
    }

    private fun answerClipboardWrite(write: MemorySegment) {
        val struct = write.reinterpret(GhosttyClipboardWrite.layout().byteSize())
        val handler = onClipboardWrite ?: return
        val count = GhosttyClipboardWrite.contents_len(struct)
        val text = if (count == 0L) {
            ""
        } else {
            val size = GhosttyClipboardContent.layout().byteSize()
            val contents = GhosttyClipboardWrite.contents(struct).reinterpret(count * size)
            val all = (0 until count).map { contents.asSlice(it * size, size) }
            val chosen = chooseClipboardText(all.map { copyString(GhosttyClipboardContent.mime(it)) }) ?: return
            copyString(GhosttyClipboardContent.data(all[chosen]))
        }
        if (!handler(clipboardLocation(GhosttyClipboardWrite.location(struct)), text)) return
        // Only the protocols that acknowledge (OSC 5522) look at it: for the others, and for a refusal, not replying is enough.
        Arena.ofConfined().use { arena ->
            val reply = arena.allocate(GhosttyClipboardWriteReply.layout())
            GhosttyClipboardWriteReply.size(reply, GhosttyClipboardWriteReply.layout().byteSize())
            GhosttyClipboardWriteReply.result(reply, GhosttyVt.GHOSTTY_CLIPBOARD_WRITE_RESULT_SUCCESS())
            GhosttyClipboardWriteReplyFn.invoke(GhosttyClipboardWrite.reply(struct), struct, reply)
        }
    }

    private fun clipboardLocation(value: Int) = when (value) {
        GhosttyVt.GHOSTTY_CLIPBOARD_LOCATION_SELECTION() -> GhosttyClipboardLocation.SELECTION
        GhosttyVt.GHOSTTY_CLIPBOARD_LOCATION_PRIMARY() -> GhosttyClipboardLocation.PRIMARY
        else -> GhosttyClipboardLocation.STANDARD
    }

    // A borrowed string, copied: it is only valid while the callback that got it runs.
    private fun copyString(string: MemorySegment): String {
        val length = GhosttyString.len(string)
        if (length == 0L) return ""
        return String(GhosttyString.ptr(string).reinterpret(length).toArray(ValueLayout.JAVA_BYTE), Charsets.UTF_8)
    }

    // An exception must never cross into native code: it would take the JVM down.
    private inline fun guarded(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            logger.error("{} failed", what, e)
        }
    }

    // The answer to XTVERSION: a string that stays valid after the callback returns, since the terminal reads it then. It is kept for as long
    // as the version does not change; the empty one is the library's own name.
    // Made at once: it is what the callback gives when making the version failed, which an allocation that fails again would throw into native code.
    private val emptyVersion: MemorySegment = callbacks.allocate(GhosttyString.layout())
    private var versionText: String? = null
    private var versionStruct: MemorySegment? = null

    private fun versionString(): MemorySegment {
        val text = xtVersion?.takeIf { it.isNotEmpty() } ?: return emptyVersion
        if (text != versionText || versionStruct == null) {
            val bytes = callbacks.allocateFrom(text)
            val struct = callbacks.allocate(GhosttyString.layout())
            GhosttyString.ptr(struct, bytes)
            GhosttyString.len(struct, bytes.byteSize() - 1)
            versionText = text
            versionStruct = struct
        }
        return versionStruct!!
    }

    // What the terminal says that it is, to the programs that ask (CSI c, CSI > c, CSI = c): a VT220 that has ANSI color. Not Sixel: it
    // is not drawn.
    private fun fillDeviceAttributes(out: MemorySegment) {
        val primary = GhosttyDeviceAttributes.primary(out)
        GhosttyDeviceAttributesPrimary.conformance_level(primary, 62)
        val features = shortArrayOf(22)
        for ((i, feature) in features.withIndex()) {
            primary.set(ValueLayout.JAVA_SHORT, GhosttyDeviceAttributesPrimary.`features$offset`() + i * 2L, feature)
        }
        GhosttyDeviceAttributesPrimary.num_features(primary, features.size.toLong())
        val secondary = GhosttyDeviceAttributes.secondary(out)
        GhosttyDeviceAttributesSecondary.device_type(secondary, 1)
        GhosttyDeviceAttributesSecondary.firmware_version(secondary, 10)
        GhosttyDeviceAttributesSecondary.rom_cartridge(secondary, 0)
        GhosttyDeviceAttributesTertiary.unit_id(GhosttyDeviceAttributes.tertiary(out), 0)
    }

    private fun installEffects() {
        val effects = listOf(
            GhosttyVt.GHOSTTY_TERMINAL_OPT_BELL() to GhosttyTerminalBellFn.allocate(
                GhosttyTerminalBellFn.Function { _, _ -> guarded("onBell") { onBell?.invoke() } },
                callbacks,
            ),
            GhosttyVt.GHOSTTY_TERMINAL_OPT_TITLE_CHANGED() to GhosttyTerminalTitleChangedFn.allocate(
                GhosttyTerminalTitleChangedFn.Function { _, _ -> guarded("onTitleChanged") { onTitleChanged?.invoke() } },
                callbacks,
            ),
            GhosttyVt.GHOSTTY_TERMINAL_OPT_PWD_CHANGED() to GhosttyTerminalPwdChangedFn.allocate(
                GhosttyTerminalPwdChangedFn.Function { _, _ -> guarded("onPwdChanged") { onPwdChanged?.invoke() } },
                callbacks,
            ),
            GhosttyVt.GHOSTTY_TERMINAL_OPT_RESET() to GhosttyTerminalResetFn.allocate(
                GhosttyTerminalResetFn.Function { _, _ -> guarded("onReset") { onReset?.invoke() } },
                callbacks,
            ),
            GhosttyVt.GHOSTTY_TERMINAL_OPT_DESKTOP_NOTIFICATION() to GhosttyTerminalDesktopNotificationFn.allocate(
                GhosttyTerminalDesktopNotificationFn.Function { _, _, notification ->
                    guarded("onNotification") {
                        val struct = notification.reinterpret(GhosttyTerminalDesktopNotification.layout().byteSize())
                        onNotification?.invoke(
                            copyString(GhosttyTerminalDesktopNotification.title(struct)),
                            copyString(GhosttyTerminalDesktopNotification.body(struct)),
                        )
                    }
                },
                callbacks,
            ),
            GhosttyVt.GHOSTTY_TERMINAL_OPT_PROGRESS_REPORT() to GhosttyTerminalProgressReportFn.allocate(
                GhosttyTerminalProgressReportFn.Function { _, _, report ->
                    guarded("onProgress") {
                        val struct = report.reinterpret(GhosttyTerminalProgressReport.layout().byteSize())
                        val state = when (GhosttyTerminalProgressReport.state(struct)) {
                            GhosttyVt.GHOSTTY_TERMINAL_PROGRESS_STATE_SET() -> GhosttyProgressState.SET
                            GhosttyVt.GHOSTTY_TERMINAL_PROGRESS_STATE_ERROR() -> GhosttyProgressState.ERROR
                            GhosttyVt.GHOSTTY_TERMINAL_PROGRESS_STATE_INDETERMINATE() -> GhosttyProgressState.INDETERMINATE
                            GhosttyVt.GHOSTTY_TERMINAL_PROGRESS_STATE_PAUSE() -> GhosttyProgressState.PAUSE
                            else -> GhosttyProgressState.REMOVE
                        }
                        val percent = GhosttyTerminalProgressReport.progress(struct).toInt().takeIf { it >= 0 }
                        onProgress?.invoke(GhosttyProgress(state, percent))
                    }
                },
                callbacks,
            ),
            GhosttyVt.GHOSTTY_TERMINAL_OPT_SEMANTIC_PROMPT() to GhosttyTerminalSemanticPromptFn.allocate(
                GhosttyTerminalSemanticPromptFn.Function { _, _, event ->
                    guarded("onSemanticPrompt") {
                        val struct = event.reinterpret(GhosttyTerminalSemanticPrompt.layout().byteSize())
                        val kind = when (GhosttyTerminalSemanticPrompt.kind(struct)) {
                            GhosttyVt.GHOSTTY_SEMANTIC_PROMPT_PROMPT_START() -> GhosttyPromptEventKind.PROMPT_START
                            GhosttyVt.GHOSTTY_SEMANTIC_PROMPT_INPUT_START() -> GhosttyPromptEventKind.INPUT_START
                            GhosttyVt.GHOSTTY_SEMANTIC_PROMPT_OUTPUT_START() -> GhosttyPromptEventKind.OUTPUT_START
                            GhosttyVt.GHOSTTY_SEMANTIC_PROMPT_COMMAND_END() -> GhosttyPromptEventKind.COMMAND_END
                            else -> return@guarded
                        }
                        val exitCode = GhosttyTerminalSemanticPrompt.exit_code(struct).takeIf { GhosttyTerminalSemanticPrompt.has_exit_code(struct) }
                        val promptKind = when (GhosttyTerminalSemanticPrompt.prompt_kind(struct)) {
                            GhosttyVt.GHOSTTY_SEMANTIC_PROMPT_PROMPT_RIGHT() -> GhosttyPromptKind.RIGHT
                            GhosttyVt.GHOSTTY_SEMANTIC_PROMPT_PROMPT_CONTINUATION() -> GhosttyPromptKind.CONTINUATION
                            GhosttyVt.GHOSTTY_SEMANTIC_PROMPT_PROMPT_SECONDARY() -> GhosttyPromptKind.SECONDARY
                            else -> GhosttyPromptKind.PRIMARY
                        }
                        onSemanticPrompt?.invoke(
                            GhosttySemanticPrompt(kind, exitCode, copyString(GhosttyTerminalSemanticPrompt.command(struct)), promptKind),
                        )
                    }
                },
                callbacks,
            ),
            GhosttyVt.GHOSTTY_TERMINAL_OPT_PROGRAM_STATUS() to GhosttyTerminalProgramStatusFn.allocate(
                GhosttyTerminalProgramStatusFn.Function { _, _, report ->
                    guarded("onProgramStatus") {
                        val struct = report.reinterpret(GhosttyTerminalProgramStatus.layout().byteSize())
                        val state = when (GhosttyTerminalProgramStatus.state(struct)) {
                            GhosttyVt.GHOSTTY_PROGRAM_STATUS_STATE_IDLE() -> GhosttyProgramState.IDLE
                            GhosttyVt.GHOSTTY_PROGRAM_STATUS_STATE_WORKING() -> GhosttyProgramState.WORKING
                            GhosttyVt.GHOSTTY_PROGRAM_STATUS_STATE_DONE() -> GhosttyProgramState.DONE
                            GhosttyVt.GHOSTTY_PROGRAM_STATUS_STATE_BLOCKED() -> GhosttyProgramState.BLOCKED
                            GhosttyVt.GHOSTTY_PROGRAM_STATUS_STATE_ERROR() -> GhosttyProgramState.ERROR
                            GhosttyVt.GHOSTTY_PROGRAM_STATUS_STATE_CLEAR() -> GhosttyProgramState.CLEAR
                            // A state of a later version of the library: nothing to say about it.
                            else -> return@guarded
                        }
                        val kind = when (GhosttyTerminalProgramStatus.kind(struct)) {
                            GhosttyVt.GHOSTTY_PROGRAM_STATUS_KIND_PERMISSION() -> GhosttyProgramKind.PERMISSION
                            GhosttyVt.GHOSTTY_PROGRAM_STATUS_KIND_QUESTION() -> GhosttyProgramKind.QUESTION
                            GhosttyVt.GHOSTTY_PROGRAM_STATUS_KIND_AUTH() -> GhosttyProgramKind.AUTH
                            else -> GhosttyProgramKind.NONE
                        }
                        onProgramStatus?.invoke(
                            GhosttyProgramStatus(
                                state = state,
                                kind = kind,
                                progress = GhosttyTerminalProgramStatus.progress(struct).toInt().takeIf { it >= 0 },
                                id = copyString(GhosttyTerminalProgramStatus.id(struct)),
                                app = copyString(GhosttyTerminalProgramStatus.app(struct)),
                                title = copyString(GhosttyTerminalProgramStatus.title(struct)),
                                message = copyString(GhosttyTerminalProgramStatus.message(struct)),
                            ),
                        )
                    }
                },
                callbacks,
            ),
            GhosttyVt.GHOSTTY_TERMINAL_OPT_RESIZE_WINDOW() to GhosttyTerminalResizeWindowFn.allocate(
                GhosttyTerminalResizeWindowFn.Function { _, _, request ->
                    guarded("onResizeRequest") {
                        val struct = request.reinterpret(GhosttyTerminalResizeWindow.layout().byteSize())
                        // The kinds of request that a later version adds are not for this one to handle.
                        if (GhosttyTerminalResizeWindow.tag(struct) != GhosttyVt.GHOSTTY_TERMINAL_RESIZE_WINDOW_GRID()) return@guarded
                        val grid = GhosttyTerminalResizeWindowValue.grid(GhosttyTerminalResizeWindow.value(struct))
                        onResizeRequest?.invoke(
                            GhosttyResizeRequest(
                                columns = (GhosttyTerminalResizeWindowGrid.columns(grid).toInt() and 0xFFFF).takeIf { it > 0 },
                                rows = (GhosttyTerminalResizeWindowGrid.rows(grid).toInt() and 0xFFFF).takeIf { it > 0 },
                            ),
                        )
                    }
                },
                callbacks,
            ),
            GhosttyVt.GHOSTTY_TERMINAL_OPT_DEVICE_ATTRIBUTES() to GhosttyTerminalDeviceAttributesFn.allocate(
                GhosttyTerminalDeviceAttributesFn.Function { _, _, out ->
                    // An exception must never cross into native code: it would take the JVM down.
                    try {
                        fillDeviceAttributes(out.reinterpret(GhosttyDeviceAttributes.layout().byteSize()))
                        true
                    } catch (e: Throwable) {
                        logger.error("The device attributes failed", e)
                        false
                    }
                },
                callbacks,
            ),
            GhosttyVt.GHOSTTY_TERMINAL_OPT_RENDER_HOLD() to GhosttyTerminalRenderHoldFn.allocate(
                GhosttyTerminalRenderHoldFn.Function { _, _, held -> guarded("onRenderHold") { onRenderHold?.invoke(held) } },
                callbacks,
            ),
            GhosttyVt.GHOSTTY_TERMINAL_OPT_COLOR_SCHEME() to GhosttyTerminalColorSchemeFn.allocate(
                GhosttyTerminalColorSchemeFn.Function { _, _, out ->
                    // An exception must never cross into native code: it would take the JVM down.
                    try {
                        val dark = isColorSchemeDark ?: return@Function false
                        out.reinterpret(Int.SIZE_BYTES.toLong()).set(
                            ValueLayout.JAVA_INT,
                            0,
                            if (dark) GhosttyVt.GHOSTTY_COLOR_SCHEME_DARK() else GhosttyVt.GHOSTTY_COLOR_SCHEME_LIGHT(),
                        )
                        true
                    } catch (e: Throwable) {
                        logger.error("The color scheme failed", e)
                        false
                    }
                },
                callbacks,
            ),
            GhosttyVt.GHOSTTY_TERMINAL_OPT_XTVERSION() to GhosttyTerminalXtversionFn.allocate(
                GhosttyTerminalXtversionFn.Function { _, _ ->
                    try {
                        versionString()
                    } catch (e: Throwable) {
                        logger.error("The version failed", e)
                        emptyVersion
                    }
                },
                callbacks,
            ),
            GhosttyVt.GHOSTTY_TERMINAL_OPT_CLIPBOARD_WRITE() to GhosttyTerminalClipboardWriteFn.allocate(
                GhosttyTerminalClipboardWriteFn.Function { _, _, write -> guarded("The clipboard write") { answerClipboardWrite(write) } },
                callbacks,
            ),
        )
        // Callbacks are passed as is, not through a pointer to them.
        for ((option, stub) in effects) {
            checkResult(GhosttyVt.ghostty_terminal_set(handle, option, stub), "ghostty_terminal_set(effect $option)")
        }
    }

    /** A reference to the cell at [column] and [row] of the viewport, which is valid until the terminal changes. */
    internal fun gridRef(arena: Arena, column: Int, row: Int): MemorySegment =
        pointRef(arena, GhosttyVt.GHOSTTY_POINT_TAG_VIEWPORT(), column, row.toLong())

    // A reference to a cell, by the kind of coordinates [tag] and [row] are in.
    private fun pointRef(arena: Arena, tag: Int, column: Int, row: Long): MemorySegment {
        requireOpen()
        val point = arena.allocate(GhosttyPoint.layout())
        GhosttyPoint.tag(point, tag)
        val coordinate = GhosttyPointValue.coordinate(GhosttyPoint.value(point))
        GhosttyPointCoordinate.x(coordinate, column.toShort())
        GhosttyPointCoordinate.y(coordinate, row.toInt())

        val ref = arena.allocate(GhosttyGridRef.layout())
        GhosttyGridRef.size(ref, GhosttyGridRef.layout().byteSize())
        checkResult(GhosttyVt.ghostty_terminal_grid_ref(handle, point, ref), "ghostty_terminal_grid_ref")
        return ref
    }

    internal val isClosed: Boolean get() = closed

    public companion object {
        private const val PASTE_MARKERS_CAPACITY = 32L

        // What ends a bracketed paste.
        private const val BRACKETED_PASTE_END = "\u001b[201~"
        private const val FOCUS_REPORT_CAPACITY = 8L
        private const val CONTINUATION_MAX_BYTES = 64L * 1024
        private const val HYPERLINK_INITIAL_CAPACITY = 512L

        /**
         * A terminal that is what [snapshot] saved: its screens and their history, the cursor, the modes, the colors, the title and the
         * directory. Its size is the snapshot's, and it is set up as a new terminal is with what is given here, which the snapshot does
         * not carry (the callbacks, the Kitty images, the terminfo name) or which the caller decides (the other options). The history is
         * kept compressed, as it is restored.
         *
         * The format has no promise of compatibility: a snapshot is only good for the build of the library that made it.
         *
         * @throws GhosttySnapshotException If [snapshot] is not one, is cut or is damaged.
         */
        public fun restore(
            snapshot: ByteArray,
            terminfoName: String? = null,
            defaultCursorBlink: Boolean = false,
            scrollbackMaxBytes: Long? = null,
            imageStorageLimitBytes: Long? = null,
            defaultCursorStyle: GhosttyCursorStyle? = null,
        ): GhosttyTerminal {
            NativeLibrary.ensureLoaded()
            val handle = decodeSnapshot(snapshot)
            val (cols, rows) = try {
                Arena.ofConfined().use { arena ->
                    val out = arena.allocate(ValueLayout.JAVA_SHORT)
                    checkResult(GhosttyVt.ghostty_terminal_get(handle, GhosttyVt.GHOSTTY_TERMINAL_DATA_COLS(), out), "ghostty_terminal_get(COLS)")
                    val cols = out.get(ValueLayout.JAVA_SHORT, 0).toInt() and 0xFFFF
                    checkResult(GhosttyVt.ghostty_terminal_get(handle, GhosttyVt.GHOSTTY_TERMINAL_DATA_ROWS(), out), "ghostty_terminal_get(ROWS)")
                    cols to (out.get(ValueLayout.JAVA_SHORT, 0).toInt() and 0xFFFF)
                }
            } catch (e: Throwable) {
                // Nothing has it yet: it is freed here.
                GhosttyVt.ghostty_terminal_free(handle)
                throw e
            }
            // The terminal frees the handle itself if it cannot be set up.
            return GhosttyTerminal(handle, cols, rows, terminfoName, defaultCursorBlink, scrollbackMaxBytes, imageStorageLimitBytes, defaultCursorStyle)
        }

        private fun decodeSnapshot(snapshot: ByteArray): MemorySegment =
            Arena.ofConfined().use { arena ->
                // The decoder reads the buffer until it is freed: it is not copied.
                val data = arena.allocate(maxOf(snapshot.size.toLong(), 1L))
                MemorySegment.copy(snapshot, 0, data, ValueLayout.JAVA_BYTE, 0, snapshot.size)
                val decoderOut = arena.allocate(ValueLayout.ADDRESS)
                snapshotResult(GhosttyVt.ghostty_snapshot_decoder_new_buf(MemorySegment.NULL, decoderOut, data, snapshot.size.toLong()), "create the decoder")
                val decoder = decoderOut.get(ValueLayout.ADDRESS, 0)
                try {
                    val compress = arena.allocate(ValueLayout.JAVA_BOOLEAN)
                    compress.set(ValueLayout.JAVA_BOOLEAN, 0, true)
                    snapshotResult(
                        GhosttyVt.ghostty_snapshot_decoder_set(decoder, GhosttyVt.GHOSTTY_SNAPSHOT_DECODER_OPT_COMPRESS_HISTORY(), compress),
                        "compress the history",
                    )
                    val terminalOut = arena.allocate(ValueLayout.ADDRESS)
                    snapshotResult(GhosttyVt.ghostty_snapshot_decoder_decode(decoder, terminalOut), "decode it")
                    terminalOut.get(ValueLayout.ADDRESS, 0)
                } finally {
                    GhosttyVt.ghostty_snapshot_decoder_free(decoder)
                }
            }
    }

    public fun isModeEnabled(mode: GhosttyMode): Boolean {
        requireOpen()
        return run {
            val config = scratch(GhosttyTerminalModeConfig.layout().byteSize())
            GhosttyTerminalModeConfig.mode(config, mode.packed)
            checkResult(
                GhosttyVt.ghostty_terminal_get(handle, GhosttyVt.GHOSTTY_TERMINAL_DATA_MODE(), config),
                "ghostty_terminal_get(MODE)",
            )
            GhosttyTerminalModeConfig.value(config)
        }
    }

    /**
     * Sets the colors that the terminal uses unless a program changes them (with OSC 4, 10, 11 or 12): the
     * theme. A program's change wins over what is set here, and what it did not change takes the new value.
     *
     * A `null` clears the default. That brings back libghostty's own cursor color and palette, but not its
     * own foreground and background: the render state only reads them when both are set, and it keeps what
     * it last read when they are not. To go back to the original ones, which are white on black, set them.
     *
     * @param palette The 256 colors that a cell's palette index refers to.
     */
    public fun setDefaultColors(
        foreground: GhosttyRgb?,
        background: GhosttyRgb?,
        cursor: GhosttyRgb?,
        palette: List<GhosttyRgb>?,
    ) {
        require(palette == null || palette.size == PALETTE_SIZE) { "A palette has $PALETTE_SIZE colors, not ${palette?.size}" }
        requireOpen()
        Arena.ofConfined().use { arena ->
            setColor(arena, GhosttyVt.GHOSTTY_TERMINAL_OPT_COLOR_FOREGROUND(), foreground, "COLOR_FOREGROUND")
            setColor(arena, GhosttyVt.GHOSTTY_TERMINAL_OPT_COLOR_BACKGROUND(), background, "COLOR_BACKGROUND")
            setColor(arena, GhosttyVt.GHOSTTY_TERMINAL_OPT_COLOR_CURSOR(), cursor, "COLOR_CURSOR")

            val colors = if (palette == null) {
                MemorySegment.NULL
            } else {
                val array = arena.allocate(GhosttyColorRgb.layout(), palette.size.toLong())
                palette.forEachIndexed { i, color -> writeRgb(GhosttyColorRgb.asSlice(array, i.toLong()), color) }
                array
            }
            checkResult(
                GhosttyVt.ghostty_terminal_set(handle, GhosttyVt.GHOSTTY_TERMINAL_OPT_COLOR_PALETTE(), colors),
                "ghostty_terminal_set(COLOR_PALETTE)",
            )
        }
    }

    private fun setColor(arena: Arena, option: Int, color: GhosttyRgb?, name: String) {
        val value = if (color == null) {
            MemorySegment.NULL
        } else {
            arena.allocate(GhosttyColorRgb.layout()).also { writeRgb(it, color) }
        }
        checkResult(GhosttyVt.ghostty_terminal_set(handle, option, value), "ghostty_terminal_set($name)")
    }

    private fun writeRgb(segment: MemorySegment, color: GhosttyRgb) {
        GhosttyColorRgb.r(segment, color.r.toByte())
        GhosttyColorRgb.g(segment, color.g.toByte())
        GhosttyColorRgb.b(segment, color.b.toByte())
    }

    /**
     * Processes what the program wrote.
     */
    public fun vtWrite(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset) {
        Objects.checkFromIndexSize(offset, length, bytes.size)
        requireOpen()
        var written = 0
        while (written < length) {
            val piece = minOf(length - written, WRITE_BUFFER_BYTES.toInt())
            MemorySegment.copy(bytes, offset + written, writeBuffer, ValueLayout.JAVA_BYTE, 0, piece)
            GhosttyVt.ghostty_terminal_vt_write(handle, writeBuffer, piece.toLong())
            written += piece
        }
    }

    public fun vtWrite(text: String) {
        vtWrite(text.encodeToByteArray())
    }

    /**
     * Resizes the terminal, reflowing its content.
     *
     * The size of a cell in pixels is what programs get when they query the size of the terminal in pixels
     * (`CSI 14 t`, `CSI 16 t`, and the in-band reports of mode 2048), and what sizes the images.
     */
    public fun resize(cols: Int, rows: Int, cellWidthPx: Int, cellHeightPx: Int) {
        requireOpen()
        // Before the call: it answers in-band reports with them.
        reportedCols = cols
        reportedRows = rows
        reportedCellWidthPx = cellWidthPx
        reportedCellHeightPx = cellHeightPx
        checkResult(
            GhosttyVt.ghostty_terminal_resize(
                handle,
                cols.toUInt16("cols"),
                rows.toUInt16("rows"),
                cellWidthPx,
                cellHeightPx,
            ),
            "ghostty_terminal_resize",
        )
    }

    override fun close() {
        if (closed) return
        closed = true
        GhosttyVt.ghostty_terminal_free(handle)
        // Last, so that the terminal never calls a stub that is gone.
        callbacks.close()
    }

    internal fun requireOpen() {
        check(!closed) { "The terminal is closed" }
    }

    private fun getUInt16(data: Int): Int {
        requireOpen()
        return run {
            val out = scratch(ValueLayout.JAVA_SHORT.byteSize())
            checkResult(GhosttyVt.ghostty_terminal_get(handle, data, out), "ghostty_terminal_get")
            out.get(ValueLayout.JAVA_SHORT, 0).toInt() and 0xFFFF
        }
    }
}

/**
 * Which of the representations of a clipboard write, by their MIME types, is the text to put in the clipboard: `text/plain` (whatever its
 * parameters, such as a charset), else another text that is meant to be read as it is. Not HTML, RTF or a list of addresses, which are markup
 * and would be pasted as such; nor an image. `null` when there is none.
 */
internal fun chooseClipboardText(mimes: List<String>): Int? {
    val types = mimes.map { it.substringBefore(';').trim().lowercase() }
    types.indexOf("text/plain").takeIf { it >= 0 }?.let { return it }
    return types.indexOfFirst { it.startsWith("text/") && it !in MARKUP_TEXT }.takeIf { it >= 0 }
}

private val MARKUP_TEXT = setOf("text/html", "text/rtf", "text/richtext", "text/uri-list", "text/xml")
