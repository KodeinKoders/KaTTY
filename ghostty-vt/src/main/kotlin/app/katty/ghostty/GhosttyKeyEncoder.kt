package app.katty.ghostty

import app.katty.ghostty.ffm.GhosttyVt
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout

/**
 * Encodes key events as the bytes to send to the program running in a terminal, according to the modes
 * that program set: application cursor keys, the Kitty keyboard protocol, and so on.
 *
 * Not thread-safe, and [setOptionsFrom] reads a [GhosttyTerminal], so both must be serialized with it.
 */
public class GhosttyKeyEncoder : AutoCloseable {
    private val encoder: MemorySegment
    private val event: MemorySegment

    private var closed = false

    init {
        NativeLibrary.ensureLoaded()

        encoder = newHandle { GhosttyVt.ghostty_key_encoder_new(MemorySegment.NULL, it) }
        try {
            event = newHandle { GhosttyVt.ghostty_key_event_new(MemorySegment.NULL, it) }
        } catch (e: Throwable) {
            GhosttyVt.ghostty_key_encoder_free(encoder)
            throw e
        }
    }

    /**
     * Takes the modes that the program running in [terminal] set into account from now on.
     *
     * Call it before encoding what the user typed, since the program can change them at any time.
     */
    public fun setOptionsFrom(terminal: GhosttyTerminal) {
        requireOpen()
        terminal.requireOpen()
        GhosttyVt.ghostty_key_encoder_setopt_from_terminal(encoder, terminal.handle)
        setOptionAsAlt()
    }

    // On macOS the encoder takes the Alt of a key that has text for the Option that made the text, and drops it (Option and `b` is `∫`,
    // sent as `b`). The host decides which Option is the program's Alt before it makes the event, and a key that it gives with Alt is
    // Alt: so for the encoder it always is.
    private fun setOptionAsAlt() {
        Arena.ofConfined().use { arena ->
            val value = arena.allocate(ValueLayout.JAVA_INT)
            value.set(ValueLayout.JAVA_INT, 0, GhosttyVt.GHOSTTY_OPTION_AS_ALT_TRUE())
            GhosttyVt.ghostty_key_encoder_setopt(encoder, GhosttyVt.GHOSTTY_KEY_ENCODER_OPT_MACOS_OPTION_AS_ALT(), value)
        }
    }

    /**
     * The bytes to send, none for a key that does not produce any (a lone modifier, a release the program
     * did not ask for).
     */
    public fun encode(keyEvent: GhosttyKeyEvent): ByteArray {
        requireOpen()
        return Arena.ofConfined().use { arena ->
            GhosttyVt.ghostty_key_event_set_action(event, keyEvent.action.ordinal)
            GhosttyVt.ghostty_key_event_set_key(event, keyEvent.key.code)
            GhosttyVt.ghostty_key_event_set_mods(event, keyEvent.mods.bits.toShort())
            GhosttyVt.ghostty_key_event_set_consumed_mods(event, keyEvent.consumedMods.bits.toShort())
            GhosttyVt.ghostty_key_event_set_composing(event, keyEvent.composing)
            GhosttyVt.ghostty_key_event_set_unshifted_codepoint(event, keyEvent.unshiftedCodepoint)

            // The event does not copy the text, it has to stay valid until the encoding is done.
            val text = keyEvent.text?.takeIf { it.isNotEmpty() }
            if (text == null) {
                GhosttyVt.ghostty_key_event_set_utf8(event, MemorySegment.NULL, 0)
            } else {
                val bytes = arena.allocateFrom(text)
                GhosttyVt.ghostty_key_event_set_utf8(event, bytes, bytes.byteSize() - 1)
            }

            val written = arena.allocate(ValueLayout.JAVA_LONG)
            var buffer = arena.allocate(INITIAL_CAPACITY)
            var result = GhosttyVt.ghostty_key_encoder_encode(encoder, event, buffer, buffer.byteSize(), written)
            if (result == GhosttyVt.GHOSTTY_OUT_OF_SPACE()) {
                // The length is the capacity that is needed.
                buffer = arena.allocate(written.get(ValueLayout.JAVA_LONG, 0))
                result = GhosttyVt.ghostty_key_encoder_encode(encoder, event, buffer, buffer.byteSize(), written)
            }
            checkResult(result, "ghostty_key_encoder_encode")
            buffer.asSlice(0, written.get(ValueLayout.JAVA_LONG, 0)).toArray(ValueLayout.JAVA_BYTE)
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        GhosttyVt.ghostty_key_event_free(event)
        GhosttyVt.ghostty_key_encoder_free(encoder)
    }

    private fun requireOpen() {
        check(!closed) { "The key encoder is closed" }
    }

    private fun newHandle(create: (MemorySegment) -> Int): MemorySegment =
        Arena.ofConfined().use { arena ->
            val out = arena.allocate(ValueLayout.ADDRESS)
            checkResult(create(out), "creating a key encoder object")
            out.get(ValueLayout.ADDRESS, 0)
        }

    private companion object {
        const val INITIAL_CAPACITY = 128L
    }
}
