package app.katty.terminal

public enum class TerminalKeyAction {
    RELEASE,
    PRESS,
    REPEAT,
}

public enum class TerminalModifier {
    SHIFT,
    CTRL,
    ALT,
    SUPER,
    CAPS_LOCK,
    NUM_LOCK,
}

/**
 * A key press, release or repeat, to send to the program running in the terminal.
 */
public data class KeyInput(
    /** The physical key. */
    public val key: TerminalKey,
    public val action: TerminalKeyAction = TerminalKeyAction.PRESS,
    public val mods: Set<TerminalModifier> = emptySet(),
    /** The modifiers that were used to produce [text], and so that do not count as held. */
    public val consumedMods: Set<TerminalModifier> = emptySet(),
    /** Whether the key is part of a composition (an input method, a dead key). */
    public val composing: Boolean = false,
    /**
     * The text the key produces, as typed: before any Ctrl / Meta transformation (`"c"` for Ctrl+C, not
     * the control character). `null` when the key produces none, and for control characters and platform
     * function key codes: the program is then told about the key itself.
     */
    public val text: String? = null,
    /** The codepoint the key produces without Shift, or 0. */
    public val unshiftedCodepoint: Int = 0,
)
