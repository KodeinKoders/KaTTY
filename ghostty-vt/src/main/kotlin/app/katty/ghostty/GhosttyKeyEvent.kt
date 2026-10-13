package app.katty.ghostty

import app.katty.ghostty.ffm.GhosttyVt

public enum class GhosttyKeyAction {
    RELEASE,
    PRESS,
    REPEAT,
}

/**
 * The modifier keys held during a key event.
 */
@JvmInline
public value class GhosttyMods(public val bits: Int) {
    public infix fun or(other: GhosttyMods): GhosttyMods = GhosttyMods(bits or other.bits)

    public operator fun contains(other: GhosttyMods): Boolean = bits and other.bits == other.bits

    public companion object {
        public val NONE: GhosttyMods = GhosttyMods(0)
        public val SHIFT: GhosttyMods = GhosttyMods(GhosttyVt.GHOSTTY_MODS_SHIFT())
        public val CTRL: GhosttyMods = GhosttyMods(GhosttyVt.GHOSTTY_MODS_CTRL())
        public val ALT: GhosttyMods = GhosttyMods(GhosttyVt.GHOSTTY_MODS_ALT())
        public val SUPER: GhosttyMods = GhosttyMods(GhosttyVt.GHOSTTY_MODS_SUPER())
        public val CAPS_LOCK: GhosttyMods = GhosttyMods(GhosttyVt.GHOSTTY_MODS_CAPS_LOCK())
        public val NUM_LOCK: GhosttyMods = GhosttyMods(GhosttyVt.GHOSTTY_MODS_NUM_LOCK())

        // Which side the modifier is on. Only meaningful along with the modifier itself.
        public val SHIFT_RIGHT: GhosttyMods = GhosttyMods(GhosttyVt.GHOSTTY_MODS_SHIFT_SIDE())
        public val CTRL_RIGHT: GhosttyMods = GhosttyMods(GhosttyVt.GHOSTTY_MODS_CTRL_SIDE())
        public val ALT_RIGHT: GhosttyMods = GhosttyMods(GhosttyVt.GHOSTTY_MODS_ALT_SIDE())
        public val SUPER_RIGHT: GhosttyMods = GhosttyMods(GhosttyVt.GHOSTTY_MODS_SUPER_SIDE())
    }
}

/**
 * A key press, release or repeat, to be encoded for the program running in the terminal.
 */
public data class GhosttyKeyEvent(
    /** The physical key. */
    public val key: GhosttyKey,
    public val action: GhosttyKeyAction = GhosttyKeyAction.PRESS,
    public val mods: GhosttyMods = GhosttyMods.NONE,
    /** The modifiers that were used to produce [text], and so that do not count as held. */
    public val consumedMods: GhosttyMods = GhosttyMods.NONE,
    /** Whether the key is part of a composition (an input method, a dead key). */
    public val composing: Boolean = false,
    /**
     * The text the key produces, as typed: before any Ctrl / Meta transformation. `null` when the key
     * produces none, and for control characters and platform function key codes.
     */
    public val text: String? = null,
    /** The codepoint the key produces without Shift, or 0. */
    public val unshiftedCodepoint: Int = 0,
)
