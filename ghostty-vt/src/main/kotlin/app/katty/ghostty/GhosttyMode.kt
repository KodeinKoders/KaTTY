package app.katty.ghostty

/**
 * A terminal mode: the number that DECSET / SM take, and whether it is an ANSI or a DEC private one.
 *
 * libghostty-vt packs both in 16 bits (value in bits 0 to 14, ANSI flag in bit 15), with static inline
 * helpers that are not exported by the library, so the packing is done here.
 */
@JvmInline
public value class GhosttyMode private constructor(internal val packed: Short) {
    public val value: Int get() = packed.toInt() and 0x7FFF

    public val isAnsi: Boolean get() = packed.toInt() and 0x8000 != 0

    public companion object {
        /** A DEC private mode, the ones set with `CSI ? n h`. */
        public fun dec(value: Int): GhosttyMode = of(value, ansi = false)

        /** A standard ANSI mode, the ones set with `CSI n h`. */
        public fun ansi(value: Int): GhosttyMode = of(value, ansi = true)

        private fun of(value: Int, ansi: Boolean): GhosttyMode {
            require(value in 0..0x7FFF) { "A mode value must be in 0..32767, was $value" }
            return GhosttyMode((value or (if (ansi) 0x8000 else 0)).toShort())
        }

        /** Cursor keys send application sequences (DECCKM). */
        public val CURSOR_KEYS: GhosttyMode = dec(1)

        /** Autowrap (DECAWM). */
        /** Origin mode (DECOM): positions are relative to the scrolling region. Setting it or resetting it moves the cursor home. */
        public val ORIGIN: GhosttyMode = dec(6)

        public val WRAPAROUND: GhosttyMode = dec(7)

        /** The cursor is visible (DECTCEM). */
        public val CURSOR_VISIBLE: GhosttyMode = dec(25)

        /** Application keypad. */
        public val KEYPAD_KEYS: GhosttyMode = dec(66)

        /** The wheel sends the arrow keys on the alternate screen, when the program did not ask for the mouse. */
        public val ALT_SCROLL: GhosttyMode = dec(1007)

        /** Focus in/out events are reported. */
        public val FOCUS_EVENT: GhosttyMode = dec(1004)

        /** The alternate screen is active. */
        public val ALT_SCREEN: GhosttyMode = dec(1047)

        /** The alternate screen is active, with the cursor saved and the screen cleared on entering. */
        public val ALT_SCREEN_SAVE: GhosttyMode = dec(1049)

        /** Pastes are wrapped in bracketed paste markers. */
        public val BRACKETED_PASTE: GhosttyMode = dec(2004)

        /** Synchronized output. */
        public val SYNC_OUTPUT: GhosttyMode = dec(2026)
    }
}
