package app.katty.ghostty

import app.katty.ghostty.ffm.GhosttyColorRgb
import app.katty.ghostty.ffm.GhosttyGridRef
import app.katty.ghostty.ffm.GhosttyPoint
import app.katty.ghostty.ffm.GhosttyPointCoordinate
import app.katty.ghostty.ffm.GhosttyPointValue
import app.katty.ghostty.ffm.GhosttyString
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
 */
public class GhosttyTerminal(
    cols: Int,
    rows: Int,
    terminfoName: String? = null,
    defaultCursorBlink: Boolean = false,
) : AutoCloseable {
    internal val handle: MemorySegment

    // Holds the upcall stubs: native code calls them for as long as the terminal lives.
    private val callbacks = Arena.ofShared()

    private var closed = false

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

            Arena.ofConfined().use { arena ->
                val blink = arena.allocate(ValueLayout.JAVA_BOOLEAN)
                blink.set(ValueLayout.JAVA_BOOLEAN, 0, defaultCursorBlink)
                checkResult(
                    GhosttyVt.ghostty_terminal_set(handle, GhosttyVt.GHOSTTY_TERMINAL_OPT_DEFAULT_CURSOR_BLINK(), blink),
                    "ghostty_terminal_set(DEFAULT_CURSOR_BLINK)",
                )
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
     * The size of a cell in pixels is what programs get when they query the size of the terminal in pixels.
     */
    public fun resize(cols: Int, rows: Int, cellWidthPx: Int, cellHeightPx: Int) {
        requireOpen()
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
