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
    fun printableKeysAreSentAsTheTextThatWasTypedByTheKey() {
        // The press of a key that has text is not sent: the text comes with the KEY_TYPED, and it is of that key.
        assertNull(keys.translate(keyInfo(PRESSED, KeyEvent.VK_A, 'a')))
        assertEquals(
            KeyInput(TerminalKey.A, text = "a", unshiftedCodepoint = 'a'.code),
            keys.translate(keyInfo(TYPED, KeyEvent.VK_UNDEFINED, 'a')),
        )
        // The text was sent (the input method did not deliver it first).
        keys.confirmTyped()
        // Its release is sent: the program that asked for the releases of keys has it, the others get nothing of it.
        assertEquals(
            KeyInput(TerminalKey.A, TerminalKeyAction.RELEASE, unshiftedCodepoint = 'a'.code),
            keys.translate(keyInfo(RELEASED, KeyEvent.VK_A, 'a')),
        )
    }

    @Test
    fun theReleaseOfATypedKeyWhoseTextTheInputMethodDeliveredIsNotSent() {
        keys.translate(keyInfo(PRESSED, KeyEvent.VK_E, 'é'))
        keys.translate(keyInfo(TYPED, KeyEvent.VK_UNDEFINED, 'é'))
        // Not confirmed: the input method had sent the text, without its key, so the program never had the press.

        assertNull(keys.translate(keyInfo(RELEASED, KeyEvent.VK_E, 'é')))
    }

    @Test
    fun theReleaseOfAKeyWhosePressTheTerminalTookIsNotSent() {
        // Option and Left is the line editor's: the terminal took the press, and the translator never saw it.
        assertNull(keys.translate(keyInfo(RELEASED, KeyEvent.VK_LEFT)))
        assertNull(keys.translate(keyInfo(RELEASED, KeyEvent.VK_SHIFT)))
    }

    @Test
    fun theKeysThatAreDownAreReleasedAllAtOnce() {
        keys.translate(keyInfo(PRESSED, KeyEvent.VK_SHIFT, modifiers = InputEvent.SHIFT_DOWN_MASK, location = KeyEvent.KEY_LOCATION_LEFT))
        keys.translate(keyInfo(PRESSED, KeyEvent.VK_UP, modifiers = InputEvent.SHIFT_DOWN_MASK))

        assertEquals(
            listOf(KeyInput(TerminalKey.SHIFT_LEFT, TerminalKeyAction.RELEASE), KeyInput(TerminalKey.ARROW_UP, TerminalKeyAction.RELEASE)),
            keys.releaseAll(),
        )
        // And they are forgotten: their own release, which goes elsewhere, is nothing here.
        assertEquals(emptyList(), keys.releaseAll())
        assertNull(keys.translate(keyInfo(RELEASED, KeyEvent.VK_UP)))
    }

    @Test
    fun aShiftedCharacterHasShiftConsumedAndTheKeyBeforeShift() {
        val shift = InputEvent.SHIFT_DOWN_MASK

        assertNull(keys.translate(keyInfo(PRESSED, KeyEvent.VK_A, 'A', shift)))
        assertEquals(
            KeyInput(
                TerminalKey.A, mods = setOf(TerminalModifier.SHIFT), consumedMods = setOf(TerminalModifier.SHIFT),
                text = "A", unshiftedCodepoint = 'a'.code,
            ),
            keys.translate(keyInfo(TYPED, KeyEvent.VK_UNDEFINED, 'A', shift)),
        )
    }

    @Test
    fun theReleaseOfAKeyThatSentNothingIsNotSent() {
        // A Command shortcut is not the program's, nor is a key whose text never came (an input method took it).
        assertNull(keys.translate(keyInfo(PRESSED, KeyEvent.VK_A, 'a', InputEvent.META_DOWN_MASK)))
        assertNull(keys.translate(keyInfo(RELEASED, KeyEvent.VK_A, 'a', InputEvent.META_DOWN_MASK)))
        assertNull(keys.translate(keyInfo(PRESSED, KeyEvent.VK_B, 'b')))
        assertNull(keys.translate(keyInfo(RELEASED, KeyEvent.VK_B, 'b')))
    }

    @Test
    fun aTextThatNoKeyJustPressedTypedIsTextOfNoKey() {
        // What an input method commits, with no press before it.
        assertEquals(KeyInput(TerminalKey.UNIDENTIFIED, text = "あ"), keys.translate(keyInfo(TYPED, KeyEvent.VK_UNDEFINED, 'あ')))
    }

    @Test
    fun theLockKeysThatAreOnAreAmongTheModifiers() {
        val info = KeyInfo(KeyInfo.Kind.PRESSED, KeyEvent.VK_UP, KeyEvent.KEY_LOCATION_STANDARD, KeyEvent.CHAR_UNDEFINED, false, false, false, false, capsLock = true, numLock = true)

        assertEquals(
            KeyInput(TerminalKey.ARROW_UP, mods = setOf(TerminalModifier.CAPS_LOCK, TerminalModifier.NUM_LOCK)),
            keys.translate(info),
        )
    }

    @Test
    fun composedCharactersAreSentAsTyped() {
        // Option+E is a dead key: no text. The E that follows has none either, and the layout composes the é.
        assertNull(keys.translate(keyInfo(PRESSED, KeyEvent.VK_E, KeyEvent.CHAR_UNDEFINED, InputEvent.ALT_DOWN_MASK)))
        assertNull(keys.translate(keyInfo(RELEASED, KeyEvent.VK_E, KeyEvent.CHAR_UNDEFINED, InputEvent.ALT_DOWN_MASK)))
        assertNull(keys.translate(keyInfo(PRESSED, KeyEvent.VK_E, KeyEvent.CHAR_UNDEFINED)))

        assertEquals(
            KeyInput(TerminalKey.E, text = "é", unshiftedCodepoint = 'e'.code),
            keys.translate(keyInfo(TYPED, KeyEvent.VK_UNDEFINED, 'é')),
        )
    }

    @Test
    fun optionIsNotAlt() {
        // Option+A types å: it is text, not Alt+A.
        assertNull(keys.translate(keyInfo(PRESSED, KeyEvent.VK_A, 'å', InputEvent.ALT_DOWN_MASK)))
        // The key and its text, with no Alt: Option made the text.
        assertEquals(
            KeyInput(TerminalKey.A, text = "å", unshiftedCodepoint = 'a'.code),
            keys.translate(keyInfo(TYPED, KeyEvent.VK_UNDEFINED, 'å', InputEvent.ALT_DOWN_MASK)),
        )
    }

    @Test
    fun optionIsAltWhenAskedAndTheKeyIsSentInsteadOfTheCharacter() {
        val keys = KeyTranslator(TerminalOptionAsAlt.BOTH)
        val option = InputEvent.ALT_DOWN_MASK

        // Option+B types ∫: the press is what is sent, as Alt and B, and the character is dropped.
        keys.translate(keyInfo(PRESSED, KeyEvent.VK_ALT, modifiers = option, location = KeyEvent.KEY_LOCATION_LEFT))
        assertEquals(
            KeyInput(TerminalKey.B, mods = setOf(TerminalModifier.ALT), text = "b", unshiftedCodepoint = 'b'.code),
            keys.translate(keyInfo(PRESSED, KeyEvent.VK_B, '∫', option)),
        )
        assertNull(keys.translate(keyInfo(TYPED, KeyEvent.VK_UNDEFINED, '∫', option)))
    }

    @Test
    fun optionAsAltKeepsWhatShiftMakesOfTheKey() {
        val keys = KeyTranslator(TerminalOptionAsAlt.BOTH)
        val altShift = InputEvent.ALT_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK

        assertEquals(
            KeyInput(
                TerminalKey.B, mods = setOf(TerminalModifier.SHIFT, TerminalModifier.ALT), consumedMods = setOf(TerminalModifier.SHIFT),
                text = "B", unshiftedCodepoint = 'b'.code,
            ),
            keys.translate(keyInfo(PRESSED, KeyEvent.VK_B, 'Í', altShift)),
        )
        assertEquals(
            KeyInput(
                TerminalKey.DIGIT_1, mods = setOf(TerminalModifier.SHIFT, TerminalModifier.ALT), consumedMods = setOf(TerminalModifier.SHIFT),
                text = "!", unshiftedCodepoint = '1'.code,
            ),
            keys.translate(keyInfo(PRESSED, KeyEvent.VK_1, '⁄', altShift)),
        )
    }

    @Test
    fun onlyTheOptionKeyThatWasAskedForIsAlt() {
        val option = InputEvent.ALT_DOWN_MASK
        val left = KeyTranslator(TerminalOptionAsAlt.LEFT)
        val right = KeyTranslator(TerminalOptionAsAlt.RIGHT)

        left.translate(keyInfo(PRESSED, KeyEvent.VK_ALT, modifiers = option, location = KeyEvent.KEY_LOCATION_RIGHT))
        right.translate(keyInfo(PRESSED, KeyEvent.VK_ALT, modifiers = option, location = KeyEvent.KEY_LOCATION_RIGHT))

        // The right one types with the setting for the left one, and is the program's Alt with the other.
        assertNull(left.translate(keyInfo(PRESSED, KeyEvent.VK_B, '∫', option)))
        assertEquals(
            KeyInput(TerminalKey.B, text = "∫", unshiftedCodepoint = 'b'.code),
            left.translate(keyInfo(TYPED, KeyEvent.VK_UNDEFINED, '∫', option)),
        )
        assertEquals(TerminalKey.B, right.translate(keyInfo(PRESSED, KeyEvent.VK_B, '∫', option))?.key)

        // Released: it types again.
        right.translate(keyInfo(RELEASED, KeyEvent.VK_ALT, location = KeyEvent.KEY_LOCATION_RIGHT))
        assertNull(right.translate(keyInfo(PRESSED, KeyEvent.VK_B, 'b')))
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
