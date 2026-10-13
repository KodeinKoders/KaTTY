package app.katty.ghostty

import app.katty.ghostty.ffm.GhosttyString
import app.katty.ghostty.ffm.GhosttyTerminalModeConfig
import app.katty.ghostty.ffm.GhosttyTerminalWritePtyFn
import app.katty.ghostty.ffm.GhosttyVt
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.util.Objects

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
 */
public class GhosttyTerminal(
    cols: Int,
    rows: Int,
    terminfoName: String? = null,
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
     * terminal.
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
                        e.printStackTrace()
                    }
                },
                callbacks,
            )
            // Callbacks are passed as is, not through a pointer to them.
            checkResult(
                GhosttyVt.ghostty_terminal_set(handle, GhosttyVt.GHOSTTY_TERMINAL_OPT_WRITE_PTY(), writePty),
                "ghostty_terminal_set(WRITE_PTY)",
            )

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
