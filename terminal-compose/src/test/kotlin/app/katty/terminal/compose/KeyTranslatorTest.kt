package app.katty.terminal.compose

import app.katty.terminal.KeyInput
import app.katty.terminal.TerminalKey
import app.katty.terminal.TerminalKeyAction
import app.katty.terminal.TerminalModifier
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** A key event, described with the AWT constants that it carries: a kind (as an AWT event id), a code, and modifier masks. */
internal fun keyInfo(
    id: Int,
    keyCode: Int,
    keyChar: Char = KeyEvent.CHAR_UNDEFINED,
    modifiers: Int = 0,
    location: Int = if (id == KeyEvent.KEY_TYPED) KeyEvent.KEY_LOCATION_UNKNOWN else KeyEvent.KEY_LOCATION_STANDARD,
) = KeyInfo(
    kind = when (id) {
        KeyEvent.KEY_PRESSED -> KeyInfo.Kind.PRESSED
        KeyEvent.KEY_RELEASED -> KeyInfo.Kind.RELEASED
        else -> KeyInfo.Kind.TYPED
    },
    keyCode = keyCode,
    location = location,
    char = keyChar,
    shift = modifiers and InputEvent.SHIFT_DOWN_MASK != 0,
    ctrl = modifiers and InputEvent.CTRL_DOWN_MASK != 0,
    alt = modifiers and InputEvent.ALT_DOWN_MASK != 0,
    meta = modifiers and InputEvent.META_DOWN_MASK != 0,
)

private const val PRESSED = KeyEvent.KEY_PRESSED
private const val RELEASED = KeyEvent.KEY_RELEASED
private const val TYPED = KeyEvent.KEY_TYPED

class KeyTranslatorTest {
    private val keys = KeyTranslator()

    @Test
    fun printableKeysAreSentAsTheTextThatWasTyped() {
        // The press of a key that has text is not sent: the text comes with the KEY_TYPED.
        assertNull(keys.translate(keyInfo(PRESSED, KeyEvent.VK_A, 'a')))
        assertEquals(KeyInput(TerminalKey.UNIDENTIFIED, text = "a"), keys.translate(keyInfo(TYPED, KeyEvent.VK_UNDEFINED, 'a')))
        assertNull(keys.translate(keyInfo(RELEASED, KeyEvent.VK_A, 'a')))
    }

    @Test
    fun composedCharactersAreSentAsTyped() {
        // Option+E is a dead key: no text. The E that follows has none either, and the layout composes the é.
        assertNull(keys.translate(keyInfo(PRESSED, KeyEvent.VK_E, KeyEvent.CHAR_UNDEFINED, InputEvent.ALT_DOWN_MASK)))
        assertNull(keys.translate(keyInfo(PRESSED, KeyEvent.VK_E, KeyEvent.CHAR_UNDEFINED)))

        assertEquals(KeyInput(TerminalKey.UNIDENTIFIED, text = "é"), keys.translate(keyInfo(TYPED, KeyEvent.VK_UNDEFINED, 'é')))
    }

    @Test
    fun optionIsNotAlt() {
        // Option+A types å: it is text, not Alt+A.
        assertNull(keys.translate(keyInfo(PRESSED, KeyEvent.VK_A, 'å', InputEvent.ALT_DOWN_MASK)))
        assertEquals(
            KeyInput(TerminalKey.UNIDENTIFIED, text = "å"),
            keys.translate(keyInfo(TYPED, KeyEvent.VK_UNDEFINED, 'å', InputEvent.ALT_DOWN_MASK)),
        )
    }

    @Test
    fun aCharacterOutsideTheBasicPlaneComesAsTwoTypedChars() {
        val smile = "😀"

        assertNull(keys.translate(keyInfo(TYPED, KeyEvent.VK_UNDEFINED, smile[0])))
        assertEquals(KeyInput(TerminalKey.UNIDENTIFIED, text = smile), keys.translate(keyInfo(TYPED, KeyEvent.VK_UNDEFINED, smile[1])))
    }

    @Test
    fun keysWithoutTextAreSentAsKeys() {
        assertEquals(KeyInput(TerminalKey.ENTER), keys.translate(keyInfo(PRESSED, KeyEvent.VK_ENTER, '\n')))
        assertEquals(KeyInput(TerminalKey.ARROW_UP), keys.translate(keyInfo(PRESSED, KeyEvent.VK_UP)))
        assertEquals(KeyInput(TerminalKey.F5), keys.translate(keyInfo(PRESSED, KeyEvent.VK_F5)))
        assertEquals(KeyInput(TerminalKey.ESCAPE), keys.translate(keyInfo(PRESSED, KeyEvent.VK_ESCAPE, '\u001b')))
        // The Enter that AWT also types is a control character: it is not sent twice.
        assertNull(keys.translate(keyInfo(TYPED, KeyEvent.VK_UNDEFINED, '\n')))
    }

    @Test
    fun modifiersAreSentWithTheKey() {
        val input = keys.translate(keyInfo(PRESSED, KeyEvent.VK_ENTER, '\n', InputEvent.SHIFT_DOWN_MASK))

        assertEquals(KeyInput(TerminalKey.ENTER, mods = setOf(TerminalModifier.SHIFT)), input)
    }

    @Test
    fun altIsSentWithTheKeysThatHaveNoText() {
        val input = keys.translate(keyInfo(PRESSED, KeyEvent.VK_LEFT, KeyEvent.CHAR_UNDEFINED, InputEvent.ALT_DOWN_MASK))

        assertEquals(KeyInput(TerminalKey.ARROW_LEFT, mods = setOf(TerminalModifier.ALT)), input)
    }

    @Test
    fun ctrlLettersAreSentAsKeysWithTheirText() {
        // The text is the character as typed, not the control character: the encoder derives that.
        val input = keys.translate(keyInfo(PRESSED, KeyEvent.VK_C, '\u0003', InputEvent.CTRL_DOWN_MASK))

        assertEquals(
            KeyInput(TerminalKey.C, mods = setOf(TerminalModifier.CTRL), text = "c", unshiftedCodepoint = 'c'.code),
            input,
        )
        // And the control character that AWT types as well is not sent again.
        assertNull(keys.translate(keyInfo(TYPED, KeyEvent.VK_UNDEFINED, '\u0003', InputEvent.CTRL_DOWN_MASK)))
    }

    @Test
    fun ctrlShiftLettersAreUpperCase() {
        val input = keys.translate(keyInfo(PRESSED, KeyEvent.VK_M, '\r', InputEvent.CTRL_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK))

        assertEquals(
            KeyInput(
                TerminalKey.M,
                mods = setOf(TerminalModifier.CTRL, TerminalModifier.SHIFT),
                consumedMods = setOf(TerminalModifier.SHIFT),
                text = "M",
                unshiftedCodepoint = 'm'.code,
            ),
            input,
        )
    }

    @Test
    fun commandShortcutsAreNotSent() {
        assertNull(keys.translate(keyInfo(PRESSED, KeyEvent.VK_C, 'c', InputEvent.META_DOWN_MASK)))
        assertNull(keys.translate(keyInfo(TYPED, KeyEvent.VK_UNDEFINED, 'c', InputEvent.META_DOWN_MASK)))
    }

    @Test
    fun aKeyThatStaysDownRepeats() {
        val press = keyInfo(PRESSED, KeyEvent.VK_DOWN)

        assertEquals(TerminalKeyAction.PRESS, keys.translate(press)?.action)
        assertEquals(TerminalKeyAction.REPEAT, keys.translate(press)?.action)
        assertEquals(TerminalKeyAction.RELEASE, keys.translate(keyInfo(RELEASED, KeyEvent.VK_DOWN))?.action)
        assertEquals(TerminalKeyAction.PRESS, keys.translate(press)?.action)
    }

    @Test
    fun forgetsWhichKeysAreDownOnReset() {
        keys.translate(keyInfo(PRESSED, KeyEvent.VK_DOWN))
        keys.reset()

        assertEquals(TerminalKeyAction.PRESS, keys.translate(keyInfo(PRESSED, KeyEvent.VK_DOWN))?.action)
    }

    @Test
    fun modifierKeysKnowTheirSide() {
        assertEquals(TerminalKey.SHIFT_LEFT, keys.translate(keyInfo(PRESSED, KeyEvent.VK_SHIFT, location = KeyEvent.KEY_LOCATION_LEFT))?.key)
        assertEquals(TerminalKey.SHIFT_RIGHT, keys.translate(keyInfo(PRESSED, KeyEvent.VK_SHIFT, location = KeyEvent.KEY_LOCATION_RIGHT))?.key)
        assertEquals(TerminalKey.META_LEFT, keys.translate(keyInfo(PRESSED, KeyEvent.VK_META, location = KeyEvent.KEY_LOCATION_LEFT))?.key)
    }

    @Test
    fun theNumpadEnterIsNotTheEnter() {
        assertEquals(
            TerminalKey.NUMPAD_ENTER,
            keys.translate(keyInfo(PRESSED, KeyEvent.VK_ENTER, '\n', location = KeyEvent.KEY_LOCATION_NUMPAD))?.key,
        )
    }

    @Test
    fun everyLetterAndDigitIsMapped() {
        for (code in KeyEvent.VK_A..KeyEvent.VK_Z) {
            assertEquals(TerminalKey.valueOf(('A' + (code - KeyEvent.VK_A)).toString()), terminalKey(code, KeyEvent.KEY_LOCATION_STANDARD))
        }
        for (code in KeyEvent.VK_0..KeyEvent.VK_9) {
            assertEquals(TerminalKey.valueOf("DIGIT_${code - KeyEvent.VK_0}"), terminalKey(code, KeyEvent.KEY_LOCATION_STANDARD))
        }
        for (code in KeyEvent.VK_F1..KeyEvent.VK_F12) {
            assertEquals(TerminalKey.valueOf("F${code - KeyEvent.VK_F1 + 1}"), terminalKey(code, KeyEvent.KEY_LOCATION_STANDARD))
        }
    }
}
