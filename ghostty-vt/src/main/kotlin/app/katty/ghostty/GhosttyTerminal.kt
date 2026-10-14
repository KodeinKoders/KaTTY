package app.katty.ghostty

import app.katty.ghostty.ffm.GhosttyClipboardContent
import app.katty.ghostty.ffm.GhosttyClipboardRead
import app.katty.ghostty.ffm.GhosttyClipboardReadReply
import app.katty.ghostty.ffm.GhosttyClipboardReadReplyFn
import app.katty.ghostty.ffm.GhosttyClipboardWrite
import app.katty.ghostty.ffm.GhosttyClipboardWriteReply
import app.katty.ghostty.ffm.GhosttyClipboardWriteReplyFn
import app.katty.ghostty.ffm.GhosttyColorRgb
import app.katty.ghostty.ffm.GhosttyGridRef
import app.katty.ghostty.ffm.GhosttyKittyGraphicsPlacementRenderInfo
import app.katty.ghostty.ffm.GhosttyPoint
import app.katty.ghostty.ffm.GhosttyPointCoordinate
import app.katty.ghostty.ffm.GhosttyPointValue
import app.katty.ghostty.ffm.GhosttySizeReportSize
import app.katty.ghostty.ffm.GhosttyString
import app.katty.ghostty.ffm.GhosttyTerminalBellFn
import app.katty.ghostty.ffm.GhosttyTerminalClipboardReadFn
import app.katty.ghostty.ffm.GhosttyTerminalClipboardWriteFn
import app.katty.ghostty.ffm.GhosttyTerminalDesktopNotification
import app.katty.ghostty.ffm.GhosttyTerminalDesktopNotificationFn
import app.katty.ghostty.ffm.GhosttyTerminalModeConfig
import app.katty.ghostty.ffm.GhosttyTerminalProgressReport
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
public class GhosttyTerminal(
    cols: Int,
    rows: Int,
    terminfoName: String? = null,
    defaultCursorBlink: Boolean = false,
    scrollbackMaxBytes: Long? = null,
    imageStorageLimitBytes: Long? = null,
    defaultCursorStyle: GhosttyCursorStyle? = null,
) : AutoCloseable {
    internal val handle: MemorySegment

    // Holds the upcall stubs: native code calls them for as long as the terminal lives.
    private val callbacks = Arena.ofShared()

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
     * It is called on the thread that called [vtWrite], while it runs, so it must not call back into this
     * terminal. An exception it throws is logged, since it cannot cross into the native code.
     */
    @Volatile
    public var onWritePty: ((ByteArray) -> Unit)? = null

    // What the terminal reports besides the screen. Each is called on the thread that called [vtWrite], while it runs, like
    // [onWritePty]: it must only record or enqueue, and must not call back into this terminal. An exception that it throws is
    // logged, since it cannot cross into the native code. The strings that come with them are copies.

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

    /** The shell reported a step of the prompt (OSC 133). */
    @Volatile
    public var onSemanticPrompt: ((GhosttySemanticPrompt) -> Unit)? = null

    /**
     * A program asked to put [String] in the clipboard (OSC 52): the text, empty to clear it. Return whether it is allowed.
     * Without a handler, writes are refused.
     */
    @Volatile
    public var onClipboardWrite: ((GhosttyClipboardLocation, String) -> Boolean)? = null

    // Not installed until a reader is set: having one also makes the terminal send the program paste events (mode 5522).
    private var clipboardReader: ((GhosttyClipboardLocation) -> String?)? = null
    private var clipboardReadStub: MemorySegment? = null

    init {
        NativeLibrary.ensureLoaded()

        handle = Arena.ofConfined().use { arena ->
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

            Arena.ofConfined().use { arena ->
                val blink = arena.allocate(ValueLayout.JAVA_BOOLEAN)
                blink.set(ValueLayout.JAVA_BOOLEAN, 0, defaultCursorBlink)
                checkResult(
                    GhosttyVt.ghostty_terminal_set(handle, GhosttyVt.GHOSTTY_TERMINAL_OPT_DEFAULT_CURSOR_BLINK(), blink),
                    "ghostty_terminal_set(DEFAULT_CURSOR_BLINK)",
                )
            }

            if (scrollbackMaxBytes != null) setLimit(GhosttyVt.GHOSTTY_TERMINAL_OPT_SCROLLBACK_MAX_BYTES(), scrollbackMaxBytes, "SCROLLBACK_MAX_BYTES")
            if (imageStorageLimitBytes != null) {
                setLimit(GhosttyVt.GHOSTTY_TERMINAL_OPT_KITTY_IMAGE_STORAGE_LIMIT(), imageStorageLimitBytes, "KITTY_IMAGE_STORAGE_LIMIT")
            }
            if (defaultCursorStyle != null) {
                require(defaultCursorStyle != GhosttyCursorStyle.BLOCK_HOLLOW) { "A program does not set the hollow block" }
                Arena.ofConfined().use { arena ->
                    val style = arena.allocate(ValueLayout.JAVA_INT)
                    style.set(
                        ValueLayout.JAVA_INT, 0,
                        when (defaultCursorStyle) {
                            GhosttyCursorStyle.BAR -> GhosttyVt.GHOSTTY_TERMINAL_CURSOR_STYLE_BAR()
                            GhosttyCursorStyle.UNDERLINE -> GhosttyVt.GHOSTTY_TERMINAL_CURSOR_STYLE_UNDERLINE()
                            else -> GhosttyVt.GHOSTTY_TERMINAL_CURSOR_STYLE_BLOCK()
                        },
                    )
                    checkResult(
                        GhosttyVt.ghostty_terminal_set(handle, GhosttyVt.GHOSTTY_TERMINAL_OPT_DEFAULT_CURSOR_STYLE(), style),
                        "ghostty_terminal_set(DEFAULT_CURSOR_STYLE)",
                    )
                }
            }

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
    public val kittyKeyboardFlags: Int
        get() {
            requireOpen()
            return Arena.ofConfined().use { arena ->
                val out = arena.allocate(ValueLayout.JAVA_BYTE)
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
            return Arena.ofConfined().use { arena ->
                val out = arena.allocate(GhosttyTerminalScrollbar.layout())
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
            return Arena.ofConfined().use { arena ->
                val out = arena.allocate(ValueLayout.JAVA_INT)
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
        return Arena.ofConfined().use { arena ->
            val out = arena.allocate(ValueLayout.ADDRESS)
            val result = GhosttyVt.ghostty_terminal_get(handle, GhosttyVt.GHOSTTY_TERMINAL_DATA_KITTY_GRAPHICS(), out)
            if (result == GhosttyVt.GHOSTTY_NO_VALUE()) return@use null
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
            return Arena.ofConfined().use { arena ->
                val out = arena.allocate(ValueLayout.JAVA_LONG)
                val result = GhosttyVt.ghostty_kitty_graphics_get(graphics, GhosttyVt.GHOSTTY_KITTY_GRAPHICS_DATA_GENERATION(), out)
                if (result == GhosttyVt.GHOSTTY_NO_VALUE()) return@use 0L
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
            return Arena.ofConfined().use { arena ->
                val out = arena.allocate(ValueLayout.JAVA_BOOLEAN)
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
            return Arena.ofConfined().use { arena ->
                val out = arena.allocate(ValueLayout.JAVA_INT)
                checkResult(
                    GhosttyVt.ghostty_terminal_get(handle, GhosttyVt.GHOSTTY_TERMINAL_DATA_MOUSE_SHAPE(), out),
                    "ghostty_terminal_get(MOUSE_SHAPE)",
                )
                GhosttyMouseShape.entries.getOrElse(out.get(ValueLayout.JAVA_INT, 0)) { GhosttyMouseShape.DEFAULT }
            }
        }

    /**
     * The text of the selection, which is what is copied: soft-wrapped lines are joined and the blanks that end a
     * line are dropped. `null` when nothing is selected.
     */
    public fun selectedText(): String? {
        requireOpen()
        return Arena.ofConfined().use { arena ->
            val options = arena.allocate(GhosttyTerminalSelectionFormatOptions.layout())
            GhosttyTerminalSelectionFormatOptions.size(options, GhosttyTerminalSelectionFormatOptions.layout().byteSize())
            GhosttyTerminalSelectionFormatOptions.emit(options, GhosttyVt.GHOSTTY_FORMATTER_FORMAT_PLAIN())
            GhosttyTerminalSelectionFormatOptions.unwrap(options, true)
            GhosttyTerminalSelectionFormatOptions.trim(options, true)
            // Not a selection of its own: the one of the terminal.
            GhosttyTerminalSelectionFormatOptions.selection(options, MemorySegment.NULL)

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

    private fun setLimit(option: Int, bytes: Long, name: String) {
        Arena.ofConfined().use { arena ->
            val limit = arena.allocate(ValueLayout.JAVA_LONG)
            limit.set(ValueLayout.JAVA_LONG, 0, bytes)
            checkResult(GhosttyVt.ghostty_terminal_set(handle, option, limit), "ghostty_terminal_set($name)")
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
        return Arena.ofConfined().use { arena ->
            val out = arena.allocate(GhosttyString.layout())
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
        val text = clipboardReader?.invoke(clipboardLocation(GhosttyClipboardRead.location(struct))) ?: return
        Arena.ofConfined().use { arena ->
            val bytes = text.encodeToByteArray()
            val data = arena.allocate(maxOf(bytes.size.toLong(), 1L))
            MemorySegment.copy(bytes, 0, data, ValueLayout.JAVA_BYTE, 0, bytes.size)
            val mime = arena.allocateFrom("text/plain")
            val content = arena.allocate(GhosttyClipboardContent.layout())
            GhosttyString.ptr(GhosttyClipboardContent.mime(content), mime)
            GhosttyString.len(GhosttyClipboardContent.mime(content), mime.byteSize() - 1)
            GhosttyString.ptr(GhosttyClipboardContent.data(content), data)
            GhosttyString.len(GhosttyClipboardContent.data(content), bytes.size.toLong())

            val reply = arena.allocate(GhosttyClipboardReadReply.layout())
            GhosttyClipboardReadReply.size(reply, GhosttyClipboardReadReply.layout().byteSize())
            GhosttyClipboardReadReply.result(reply, GhosttyVt.GHOSTTY_CLIPBOARD_READ_RESULT_SUCCESS())
            GhosttyClipboardReadReply.contents(reply, content)
            GhosttyClipboardReadReply.contents_len(reply, 1)
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
            // The first text: the other representations (images, HTML) are not for a terminal.
            val content = (0 until count).map { contents.asSlice(it * size, size) }
                .firstOrNull { copyString(GhosttyClipboardContent.mime(it)).startsWith("text/") }
                ?: return
            copyString(GhosttyClipboardContent.data(content))
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
                        onSemanticPrompt?.invoke(GhosttySemanticPrompt(kind, exitCode, copyString(GhosttyTerminalSemanticPrompt.command(struct))))
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
    internal fun gridRef(arena: Arena, column: Int, row: Int): MemorySegment {
        requireOpen()
        val point = arena.allocate(GhosttyPoint.layout())
        GhosttyPoint.tag(point, GhosttyVt.GHOSTTY_POINT_TAG_VIEWPORT())
        val coordinate = GhosttyPointValue.coordinate(GhosttyPoint.value(point))
        GhosttyPointCoordinate.x(coordinate, column.toShort())
        GhosttyPointCoordinate.y(coordinate, row)

        val ref = arena.allocate(GhosttyGridRef.layout())
        GhosttyGridRef.size(ref, GhosttyGridRef.layout().byteSize())
        checkResult(GhosttyVt.ghostty_terminal_grid_ref(handle, point, ref), "ghostty_terminal_grid_ref")
        return ref
    }

    internal val isClosed: Boolean get() = closed

    private companion object {
        const val PASTE_MARKERS_CAPACITY = 32L
        const val FOCUS_REPORT_CAPACITY = 8L
        const val HYPERLINK_INITIAL_CAPACITY = 512L
    }

    public fun isModeEnabled(mode: GhosttyMode): Boolean {
        requireOpen()
        return Arena.ofConfined().use { arena ->
            val config = arena.allocate(GhosttyTerminalModeConfig.layout())
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
        if (length == 0) return
        Arena.ofConfined().use { arena ->
            val native = arena.allocate(length.toLong())
            MemorySegment.copy(bytes, offset, native, ValueLayout.JAVA_BYTE, 0, length)
            GhosttyVt.ghostty_terminal_vt_write(handle, native, length.toLong())
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
        return Arena.ofConfined().use { arena ->
            val out = arena.allocate(ValueLayout.JAVA_SHORT)
            checkResult(GhosttyVt.ghostty_terminal_get(handle, data, out), "ghostty_terminal_get")
            out.get(ValueLayout.JAVA_SHORT, 0).toInt() and 0xFFFF
        }
    }
}
