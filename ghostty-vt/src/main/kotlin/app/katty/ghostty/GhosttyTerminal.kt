package app.katty.ghostty

import app.katty.ghostty.ffm.GhosttyColorRgb
import app.katty.ghostty.ffm.GhosttyGridRef
import app.katty.ghostty.ffm.GhosttyKittyGraphicsPlacementRenderInfo
import app.katty.ghostty.ffm.GhosttyPoint
import app.katty.ghostty.ffm.GhosttyPointCoordinate
import app.katty.ghostty.ffm.GhosttyPointValue
import app.katty.ghostty.ffm.GhosttySizeReportSize
import app.katty.ghostty.ffm.GhosttyString
import app.katty.ghostty.ffm.GhosttyTerminalScrollViewport
import app.katty.ghostty.ffm.GhosttyTerminalScrollViewportValue
import app.katty.ghostty.ffm.GhosttyTerminalScrollbar
import app.katty.ghostty.ffm.GhosttyTerminalSizeFn
import app.katty.ghostty.ffm.GhosttyTerminalSelectionFormatOptions
import app.katty.ghostty.ffm.GhosttyTerminalModeConfig
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
 */
public class GhosttyTerminal(
    cols: Int,
    rows: Int,
    terminfoName: String? = null,
    defaultCursorBlink: Boolean = false,
    scrollbackMaxBytes: Long? = null,
    imageStorageLimitBytes: Long? = null,
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

            Arena.ofConfined().use { arena ->
                val blink = arena.allocate(ValueLayout.JAVA_BOOLEAN)
                blink.set(ValueLayout.JAVA_BOOLEAN, 0, defaultCursorBlink)
                checkResult(
                    GhosttyVt.ghostty_terminal_set(handle, GhosttyVt.GHOSTTY_TERMINAL_OPT_DEFAULT_CURSOR_BLINK(), blink),
                    "ghostty_terminal_set(DEFAULT_CURSOR_BLINK)",
                )
            }

            if (scrollbackMaxBytes != null) {
                Arena.ofConfined().use { arena ->
                    val limit = arena.allocate(ValueLayout.JAVA_LONG)
                    limit.set(ValueLayout.JAVA_LONG, 0, scrollbackMaxBytes)
                    checkResult(
                        GhosttyVt.ghostty_terminal_set(handle, GhosttyVt.GHOSTTY_TERMINAL_OPT_SCROLLBACK_MAX_BYTES(), limit),
                        "ghostty_terminal_set(SCROLLBACK_MAX_BYTES)",
                    )
                }
            }

            if (imageStorageLimitBytes != null) {
                Arena.ofConfined().use { arena ->
                    val limit = arena.allocate(ValueLayout.JAVA_LONG)
                    limit.set(ValueLayout.JAVA_LONG, 0, imageStorageLimitBytes)
                    checkResult(
                        GhosttyVt.ghostty_terminal_set(handle, GhosttyVt.GHOSTTY_TERMINAL_OPT_KITTY_IMAGE_STORAGE_LIMIT(), limit),
                        "ghostty_terminal_set(KITTY_IMAGE_STORAGE_LIMIT)",
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
