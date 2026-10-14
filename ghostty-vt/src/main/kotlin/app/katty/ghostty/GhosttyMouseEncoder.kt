package app.katty.ghostty

import app.katty.ghostty.ffm.GhosttyMouseEncoderSize
import app.katty.ghostty.ffm.GhosttyMousePosition
import app.katty.ghostty.ffm.GhosttyVt
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout

/**
 * Encodes mouse events as the bytes to send to the program running in a terminal, in the protocol that
 * program chose (X10, UTF-8, SGR, URxvt, SGR-Pixels) and for what it asked to be told (clicks, drags,
 * motion). A program that asked for nothing gets nothing.
 *
 * Not thread-safe, and [setOptionsFrom] reads a [GhosttyTerminal], so both must be serialized with it.
 */
public class GhosttyMouseEncoder : AutoCloseable {
    private val encoder: MemorySegment
    private val event: MemorySegment

    private var closed = false

    init {
        NativeLibrary.ensureLoaded()

        encoder = newHandle { GhosttyVt.ghostty_mouse_encoder_new(MemorySegment.NULL, it) }
        try {
            event = newHandle { GhosttyVt.ghostty_mouse_event_new(MemorySegment.NULL, it) }
        } catch (e: Throwable) {
            GhosttyVt.ghostty_mouse_encoder_free(encoder)
            throw e
        }
        // A motion in the cell it was already reported in is not reported again.
        setBoolean(GhosttyVt.GHOSTTY_MOUSE_ENCODER_OPT_TRACK_LAST_CELL(), true)
    }

    /**
     * Takes the tracking mode and the format that the program running in [terminal] set into account from now
     * on. Call it before encoding, since the program can change them at any time.
     */
    public fun setOptionsFrom(terminal: GhosttyTerminal) {
        requireOpen()
        terminal.requireOpen()
        GhosttyVt.ghostty_mouse_encoder_setopt_from_terminal(encoder, terminal.handle)
    }

    /**
     * The geometry that positions in pixels are converted to cells with: the screen is [cols] by [rows] cells of
     * [cellWidthPx] by [cellHeightPx] pixels, with no padding.
     */
    public fun setSize(cols: Int, rows: Int, cellWidthPx: Int, cellHeightPx: Int) {
        requireOpen()
        require(cols > 0 && rows > 0 && cellWidthPx > 0 && cellHeightPx > 0) { "The size has to be positive" }
        Arena.ofConfined().use { arena ->
            val size = arena.allocate(GhosttyMouseEncoderSize.layout())
            GhosttyMouseEncoderSize.size(size, GhosttyMouseEncoderSize.layout().byteSize())
            GhosttyMouseEncoderSize.screen_width(size, cols * cellWidthPx)
            GhosttyMouseEncoderSize.screen_height(size, rows * cellHeightPx)
            GhosttyMouseEncoderSize.cell_width(size, cellWidthPx)
            GhosttyMouseEncoderSize.cell_height(size, cellHeightPx)
            GhosttyVt.ghostty_mouse_encoder_setopt(encoder, GhosttyVt.GHOSTTY_MOUSE_ENCODER_OPT_SIZE(), size)
        }
    }

    /**
     * The bytes to send, none for an event that does not produce any: the program did not ask for it, or it is
     * a motion in the cell that was already reported.
     *
     * @param anyButtonPressed Whether a button is held, which is what a program that asked for drags only is told
     * motion for.
     */
    public fun encode(mouseEvent: GhosttyMouseEvent, anyButtonPressed: Boolean): ByteArray {
        requireOpen()
        setBoolean(GhosttyVt.GHOSTTY_MOUSE_ENCODER_OPT_ANY_BUTTON_PRESSED(), anyButtonPressed)
        return Arena.ofConfined().use { arena ->
            GhosttyVt.ghostty_mouse_event_set_action(event, mouseEvent.action.ordinal)
            val button = mouseEvent.button
            if (button == null) {
                GhosttyVt.ghostty_mouse_event_clear_button(event)
            } else {
                GhosttyVt.ghostty_mouse_event_set_button(event, button.ordinal)
            }
            GhosttyVt.ghostty_mouse_event_set_mods(event, mouseEvent.mods.bits.toShort())
            val position = arena.allocate(GhosttyMousePosition.layout())
            GhosttyMousePosition.x(position, mouseEvent.x)
            GhosttyMousePosition.y(position, mouseEvent.y)
            GhosttyVt.ghostty_mouse_event_set_position(event, position)

            val written = arena.allocate(ValueLayout.JAVA_LONG)
            var buffer = arena.allocate(INITIAL_CAPACITY)
            var result = GhosttyVt.ghostty_mouse_encoder_encode(encoder, event, buffer, buffer.byteSize(), written)
            if (result == GhosttyVt.GHOSTTY_OUT_OF_SPACE()) {
                // The length is the capacity that is needed.
                buffer = arena.allocate(written.get(ValueLayout.JAVA_LONG, 0))
                result = GhosttyVt.ghostty_mouse_encoder_encode(encoder, event, buffer, buffer.byteSize(), written)
            }
            checkResult(result, "ghostty_mouse_encoder_encode")
            buffer.asSlice(0, written.get(ValueLayout.JAVA_LONG, 0)).toArray(ValueLayout.JAVA_BYTE)
        }
    }

    /** Forgets the cell of the last motion, so that the next one is reported. */
    public fun reset() {
        requireOpen()
        GhosttyVt.ghostty_mouse_encoder_reset(encoder)
    }

    override fun close() {
        if (closed) return
        closed = true
        GhosttyVt.ghostty_mouse_event_free(event)
        GhosttyVt.ghostty_mouse_encoder_free(encoder)
    }

    private fun setBoolean(option: Int, value: Boolean) {
        Arena.ofConfined().use { arena ->
            val segment = arena.allocate(ValueLayout.JAVA_BOOLEAN)
            segment.set(ValueLayout.JAVA_BOOLEAN, 0, value)
            GhosttyVt.ghostty_mouse_encoder_setopt(encoder, option, segment)
        }
    }

    private fun requireOpen() {
        check(!closed) { "The mouse encoder is closed" }
    }

    private fun newHandle(create: (MemorySegment) -> Int): MemorySegment =
        Arena.ofConfined().use { arena ->
            val out = arena.allocate(ValueLayout.ADDRESS)
            checkResult(create(out), "creating a mouse encoder object")
            out.get(ValueLayout.ADDRESS, 0)
        }

    private companion object {
        const val INITIAL_CAPACITY = 128L
    }
}
