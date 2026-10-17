package app.katty.terminal.compose

import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.nativeKeyLocation
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import app.katty.terminal.KeyInput
import app.katty.terminal.TerminalKey
import app.katty.terminal.TerminalKeyAction
import app.katty.terminal.TerminalModifier
import java.awt.event.KeyEvent as AwtKeyEvent

/** What matters of a key event, as AWT reports it and Compose hands it over. */
internal class KeyInfo(
    val kind: Kind,
    /** An AWT `VK_` code: it is the layout's key, not its position. */
    val keyCode: Int,
    /** An AWT `KEY_LOCATION_`. */
    val location: Int,
    /** The character of the event, or [AwtKeyEvent.CHAR_UNDEFINED]. */
    val char: Char,
    val shift: Boolean,
    val ctrl: Boolean,
    val alt: Boolean,
    val meta: Boolean,
    /** Whether the Caps Lock and Num Lock keys are on: the Kitty keyboard protocol reports them among the modifiers. */
    val capsLock: Boolean = false,
    val numLock: Boolean = false,
) {
    enum class Kind { PRESSED, RELEASED, TYPED }
}

/**
 * Reads a Compose key event, which on the desktop is an AWT one: a key press or release, and a typed
 * character, which Compose gives the type `Unknown`.
 */
internal fun KeyEvent.toKeyInfo() = KeyInfo(
    kind = when (type) {
        KeyEventType.KeyDown -> KeyInfo.Kind.PRESSED
        KeyEventType.KeyUp -> KeyInfo.Kind.RELEASED
        else -> KeyInfo.Kind.TYPED
    },
    keyCode = key.nativeKeyCode,
    location = key.nativeKeyLocation,
    char = utf16CodePoint.toChar(),
    shift = isShiftPressed,
    ctrl = isCtrlPressed,
    alt = isAltPressed,
    meta = isMetaPressed,
    capsLock = lockingKeyState(AwtKeyEvent.VK_CAPS_LOCK),
    numLock = lockingKeyState(AwtKeyEvent.VK_NUM_LOCK),
)

// Whether a lock key is on. The platform may not say, and then it is off.
private fun lockingKeyState(keyCode: Int): Boolean = try {
    java.awt.Toolkit.getDefaultToolkit().getLockingKeyState(keyCode)
} catch (e: UnsupportedOperationException) {
    false
}

/**
 * Whether the Option key of the Mac is the program's Alt: Option and a key is then `ESC` and the key, as in Ghostty and iTerm2's
 * "Esc+" setting, and not the character that the layout makes of it (`å`). It is what Emacs and readline's word keys need.
 */
public enum class TerminalOptionAsAlt {
    /** Option types the characters of the layout, and is not sent as Alt. */
    NONE,

    /** Both Option keys are Alt. */
    BOTH,

    /** Only the left Option key is Alt: the right one still types the characters. */
    LEFT,

    /** Only the right Option key is Alt. */
    RIGHT,
}

/**
 * Turns the key events that Compose hands over into what is sent to the program.
 *
 * AWT sends a `KEY_PRESSED` for each physical key, and a `KEY_TYPED` with the character it produced when
 * there is one. The text comes from the latter: the keyboard layout has already composed it, so dead keys,
 * input methods and Option characters (`å`) work. The former is only used for keys that have no text
 * (arrows, Enter, Escape, function keys, modifiers) and for Ctrl combinations. Option is the key that
 * produces the characters: it is not the Alt of the program, so Option+key is not sent as Alt+key, unless [optionAsAlt] says
 * that it is: it is then sent from the `KEY_PRESSED`, as the key and not as the character that came of it. Command
 * shortcuts are not sent either. The keys that edit the line (Option or Command and the arrows) are the terminal's
 * shortcuts, taken before: see [lineEditingText].
 *
 * Not thread-safe: it is used from the UI thread.
 */
internal class KeyTranslator(private val optionAsAlt: TerminalOptionAsAlt = TerminalOptionAsAlt.NONE) {
    // The AWT key codes that are down, which is how a repeat is told from a press.
    private val down = HashSet<Int>()

    // Which of the Option keys are down: AWT's modifiers do not say which.
    private var leftOption = false
    private var rightOption = false

    // The key that was just pressed and whose text is still to come as a KEY_TYPED, so that the text is sent as what that key typed and not
    // as text of no key (the Kitty keyboard protocol tells them apart). It does not outlive the next event of another key.
    private var pending: Pending? = null

    // The keys whose press was sent as a key, by AWT key code: their release is sent too.
    private val sent = HashSet<Int>()

    private class Pending(val keyCode: Int, val key: TerminalKey, val action: TerminalKeyAction, val unshifted: Char, val info: KeyInfo)

    // The first half of a character that does not fit in a char, which is sent as two KEY_TYPED.
    private var highSurrogate: Char? = null

    /** Forgets which keys are down: their release will not be seen, for instance when the focus is lost. */
    fun reset() {
        down.clear()
        sent.clear()
        pending = null
        leftOption = false
        rightOption = false
        highSurrogate = null
    }

    // Whether the Option that is down is the program's Alt.
    private fun optionIsAlt(event: KeyInfo): Boolean = event.alt && when (optionAsAlt) {
        TerminalOptionAsAlt.NONE -> false
        TerminalOptionAsAlt.BOTH -> true
        TerminalOptionAsAlt.LEFT -> leftOption
        TerminalOptionAsAlt.RIGHT -> rightOption
    }

    private fun trackOption(event: KeyInfo, isDown: Boolean) {
        if (event.keyCode != AwtKeyEvent.VK_ALT) return
        if (event.location == AwtKeyEvent.KEY_LOCATION_RIGHT) rightOption = isDown else leftOption = isDown
    }

    /** `null` when the event is not for the program. */
    fun translate(event: KeyInfo): KeyInput? = when (event.kind) {
        KeyInfo.Kind.TYPED -> typed(event)
        KeyInfo.Kind.PRESSED -> pressed(event)
        KeyInfo.Kind.RELEASED -> released(event)
    }

    private fun typed(event: KeyInfo): KeyInput? {
        val char = event.char
        // The Ctrl combinations are control characters: they are sent from the KEY_PRESSED.
        if (char == AwtKeyEvent.CHAR_UNDEFINED || char.isISOControl() || event.ctrl || event.meta) {
            return null
        }
        // The program's Alt: the key was sent when it was pressed, and this is the character that the layout made of it.
        if (optionIsAlt(event)) return null
        if (char.isHighSurrogate()) {
            highSurrogate = char
            return null
        }
        val text = if (char.isLowSurrogate()) {
            val high = highSurrogate ?: return null
            highSurrogate = null
            "$high$char"
        } else {
            char.toString()
        }
        // The key that typed it, if it is known: the program may ask for keys and not for text.
        val key = pending.also { pending = null }
        if (key == null || text.codePointCount(0, text.length) != 1) return KeyInput(TerminalKey.UNIDENTIFIED, text = text)
        sent += key.keyCode
        // Option made the text of an Option key that is not Alt (`å`): the program is told of the key and its text, not of an Alt. Shift
        // made the text if it is not what the key types without it.
        val mods = modifiers(key.info) - TerminalModifier.ALT
        return KeyInput(
            key = key.key,
            action = key.action,
            mods = mods,
            consumedMods = if (TerminalModifier.SHIFT in mods && text != key.unshifted.toString()) setOf(TerminalModifier.SHIFT) else emptySet(),
            text = text,
            unshiftedCodepoint = key.unshifted.code,
        )
    }

    private fun pressed(event: KeyInfo): KeyInput? {
        trackOption(event, isDown = true)
        pending = null
        val key = terminalKey(event.keyCode, event.location) ?: return null
        val action = if (down.add(event.keyCode)) TerminalKeyAction.PRESS else TerminalKeyAction.REPEAT
        val mods = modifiers(event)

        val unshifted = unshiftedChar(key) ?: return KeyInput(key, action, mods)

        // A key that has text. Its text comes with the KEY_TYPED, unless there is none because of Ctrl, or it is not wanted: Option
        // is the program's Alt, and the key is sent instead of the character that the layout makes of it.
        if (event.meta || !(event.ctrl || optionIsAlt(event))) {
            // A Command shortcut is not for the program: no text comes of it, and nothing is waited for.
            if (!event.meta) pending = Pending(event.keyCode, key, action, unshifted, event)
            return null
        }
        sent += event.keyCode
        // Ctrl keeps the key of a symbol and tells Shift apart, as it always did; Alt has the symbol that Shift makes of it.
        val shiftedChar = if (event.shift && (unshifted.isLetter() || optionIsAlt(event))) shiftedChar(unshifted) else null
        return KeyInput(
            key = key,
            action = action,
            mods = mods,
            consumedMods = if (shiftedChar != null) setOf(TerminalModifier.SHIFT) else emptySet(),
            text = (shiftedChar ?: unshifted).toString(),
            unshiftedCodepoint = unshifted.code,
        )
    }

    private fun released(event: KeyInfo): KeyInput? {
        down.remove(event.keyCode)
        trackOption(event, isDown = false)
        val key = terminalKey(event.keyCode, event.location) ?: return null
        if (pending?.keyCode == event.keyCode) pending = null
        val unshifted = unshiftedChar(key) ?: return KeyInput(key, TerminalKeyAction.RELEASE, modifiers(event))
        // A key that has text: its release is only for a program that asked for the releases of keys (the Kitty keyboard protocol), and
        // only if its press was sent. Option that made the text is not told, as for the press.
        if (!sent.remove(event.keyCode)) return null
        val mods = if (optionIsAlt(event)) modifiers(event) else modifiers(event) - TerminalModifier.ALT
        return KeyInput(key, TerminalKeyAction.RELEASE, mods, unshiftedCodepoint = unshifted.code)
    }

    private fun modifiers(event: KeyInfo): Set<TerminalModifier> = buildSet {
        if (event.shift) add(TerminalModifier.SHIFT)
        if (event.ctrl) add(TerminalModifier.CTRL)
        if (event.alt) add(TerminalModifier.ALT)
        if (event.meta) add(TerminalModifier.SUPER)
        if (event.capsLock) add(TerminalModifier.CAPS_LOCK)
        if (event.numLock) add(TerminalModifier.NUM_LOCK)
    }
}

/** What Shift makes of [char] on a US keyboard: the capital of a letter, the symbol over a digit or a punctuation key; `null` for the others. */
internal fun shiftedChar(char: Char): Char? = when {
    char.isLetter() -> char.uppercaseChar()
    else -> SHIFTED.getOrNull(UNSHIFTED.indexOf(char))
}

private const val UNSHIFTED = "1234567890-=[]\\;',./`"
private const val SHIFTED = "!@#$%^&*()_+{}|:\"<>?~"

/** The character a key types without Shift on a US keyboard, for the keys that type one; `null` for the others. */
internal fun unshiftedChar(key: TerminalKey): Char? = when (key) {
    in TerminalKey.A..TerminalKey.Z -> 'a' + (key.ordinal - TerminalKey.A.ordinal)
    TerminalKey.DIGIT_0 -> '0'
    TerminalKey.DIGIT_1 -> '1'
    TerminalKey.DIGIT_2 -> '2'
    TerminalKey.DIGIT_3 -> '3'
    TerminalKey.DIGIT_4 -> '4'
    TerminalKey.DIGIT_5 -> '5'
    TerminalKey.DIGIT_6 -> '6'
    TerminalKey.DIGIT_7 -> '7'
    TerminalKey.DIGIT_8 -> '8'
    TerminalKey.DIGIT_9 -> '9'
    TerminalKey.BACKQUOTE -> '`'
    TerminalKey.BACKSLASH -> '\\'
    TerminalKey.BRACKET_LEFT -> '['
    TerminalKey.BRACKET_RIGHT -> ']'
    TerminalKey.COMMA -> ','
    TerminalKey.EQUAL -> '='
    TerminalKey.MINUS -> '-'
    TerminalKey.PERIOD -> '.'
    TerminalKey.QUOTE -> '\''
    TerminalKey.SEMICOLON -> ';'
    TerminalKey.SLASH -> '/'
    TerminalKey.SPACE -> ' '
    TerminalKey.NUMPAD_0 -> '0'
    TerminalKey.NUMPAD_1 -> '1'
    TerminalKey.NUMPAD_2 -> '2'
    TerminalKey.NUMPAD_3 -> '3'
    TerminalKey.NUMPAD_4 -> '4'
    TerminalKey.NUMPAD_5 -> '5'
    TerminalKey.NUMPAD_6 -> '6'
    TerminalKey.NUMPAD_7 -> '7'
    TerminalKey.NUMPAD_8 -> '8'
    TerminalKey.NUMPAD_9 -> '9'
    TerminalKey.NUMPAD_ADD -> '+'
    TerminalKey.NUMPAD_SUBTRACT -> '-'
    TerminalKey.NUMPAD_MULTIPLY -> '*'
    TerminalKey.NUMPAD_DIVIDE -> '/'
    TerminalKey.NUMPAD_DECIMAL -> '.'
    else -> null
}

/**
 * The physical key of an AWT key code. AWT reports the key as the layout names it, not its position, so on
 * a keyboard that is not a US one a key is the one that types the same letter.
 */
internal fun terminalKey(keyCode: Int, location: Int): TerminalKey? {
    val right = location == AwtKeyEvent.KEY_LOCATION_RIGHT
    val numpad = location == AwtKeyEvent.KEY_LOCATION_NUMPAD
    return when (keyCode) {
        in AwtKeyEvent.VK_A..AwtKeyEvent.VK_Z -> TerminalKey.entries[TerminalKey.A.ordinal + (keyCode - AwtKeyEvent.VK_A)]
        in AwtKeyEvent.VK_0..AwtKeyEvent.VK_9 -> TerminalKey.entries[TerminalKey.DIGIT_0.ordinal + (keyCode - AwtKeyEvent.VK_0)]
        in AwtKeyEvent.VK_NUMPAD0..AwtKeyEvent.VK_NUMPAD9 -> TerminalKey.entries[TerminalKey.NUMPAD_0.ordinal + (keyCode - AwtKeyEvent.VK_NUMPAD0)]
        in AwtKeyEvent.VK_F1..AwtKeyEvent.VK_F12 -> TerminalKey.entries[TerminalKey.F1.ordinal + (keyCode - AwtKeyEvent.VK_F1)]
        in AwtKeyEvent.VK_F13..AwtKeyEvent.VK_F24 -> TerminalKey.entries[TerminalKey.F13.ordinal + (keyCode - AwtKeyEvent.VK_F13)]
        AwtKeyEvent.VK_BACK_QUOTE -> TerminalKey.BACKQUOTE
        AwtKeyEvent.VK_BACK_SLASH -> TerminalKey.BACKSLASH
        AwtKeyEvent.VK_OPEN_BRACKET -> TerminalKey.BRACKET_LEFT
        AwtKeyEvent.VK_CLOSE_BRACKET -> TerminalKey.BRACKET_RIGHT
        AwtKeyEvent.VK_COMMA -> TerminalKey.COMMA
        AwtKeyEvent.VK_EQUALS -> TerminalKey.EQUAL
        AwtKeyEvent.VK_MINUS -> TerminalKey.MINUS
        AwtKeyEvent.VK_PERIOD -> if (numpad) TerminalKey.NUMPAD_DECIMAL else TerminalKey.PERIOD
        AwtKeyEvent.VK_QUOTE -> TerminalKey.QUOTE
        AwtKeyEvent.VK_SEMICOLON -> TerminalKey.SEMICOLON
        AwtKeyEvent.VK_SLASH -> TerminalKey.SLASH
        AwtKeyEvent.VK_SPACE -> TerminalKey.SPACE
        AwtKeyEvent.VK_ENTER -> if (numpad) TerminalKey.NUMPAD_ENTER else TerminalKey.ENTER
        AwtKeyEvent.VK_TAB -> TerminalKey.TAB
        AwtKeyEvent.VK_BACK_SPACE -> TerminalKey.BACKSPACE
        AwtKeyEvent.VK_ESCAPE -> TerminalKey.ESCAPE
        AwtKeyEvent.VK_DELETE -> TerminalKey.DELETE
        AwtKeyEvent.VK_INSERT -> TerminalKey.INSERT
        AwtKeyEvent.VK_HOME -> TerminalKey.HOME
        AwtKeyEvent.VK_END -> TerminalKey.END
        AwtKeyEvent.VK_PAGE_UP -> TerminalKey.PAGE_UP
        AwtKeyEvent.VK_PAGE_DOWN -> TerminalKey.PAGE_DOWN
        AwtKeyEvent.VK_UP -> TerminalKey.ARROW_UP
        AwtKeyEvent.VK_DOWN -> TerminalKey.ARROW_DOWN
        AwtKeyEvent.VK_LEFT -> TerminalKey.ARROW_LEFT
        AwtKeyEvent.VK_RIGHT -> TerminalKey.ARROW_RIGHT
        AwtKeyEvent.VK_ADD -> TerminalKey.NUMPAD_ADD
        AwtKeyEvent.VK_SUBTRACT -> TerminalKey.NUMPAD_SUBTRACT
        AwtKeyEvent.VK_MULTIPLY -> TerminalKey.NUMPAD_MULTIPLY
        AwtKeyEvent.VK_DIVIDE -> TerminalKey.NUMPAD_DIVIDE
        AwtKeyEvent.VK_SEPARATOR -> TerminalKey.NUMPAD_SEPARATOR
        AwtKeyEvent.VK_SHIFT -> if (right) TerminalKey.SHIFT_RIGHT else TerminalKey.SHIFT_LEFT
        AwtKeyEvent.VK_CONTROL -> if (right) TerminalKey.CONTROL_RIGHT else TerminalKey.CONTROL_LEFT
        AwtKeyEvent.VK_ALT -> if (right) TerminalKey.ALT_RIGHT else TerminalKey.ALT_LEFT
        AwtKeyEvent.VK_META -> if (right) TerminalKey.META_RIGHT else TerminalKey.META_LEFT
        AwtKeyEvent.VK_CAPS_LOCK -> TerminalKey.CAPS_LOCK
        AwtKeyEvent.VK_NUM_LOCK -> TerminalKey.NUM_LOCK
        AwtKeyEvent.VK_SCROLL_LOCK -> TerminalKey.SCROLL_LOCK
        AwtKeyEvent.VK_PRINTSCREEN -> TerminalKey.PRINT_SCREEN
        AwtKeyEvent.VK_PAUSE -> TerminalKey.PAUSE
        AwtKeyEvent.VK_CONTEXT_MENU -> TerminalKey.CONTEXT_MENU
        else -> null
    }
}
