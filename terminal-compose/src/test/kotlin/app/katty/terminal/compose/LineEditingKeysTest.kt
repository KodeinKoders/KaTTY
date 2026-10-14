package app.katty.terminal.compose

import java.awt.event.KeyEvent as AwtKeyEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LineEditingKeysTest {
    private fun key(
        code: Int,
        shift: Boolean = false, ctrl: Boolean = false, alt: Boolean = false, meta: Boolean = false,
        kind: KeyInfo.Kind = KeyInfo.Kind.PRESSED,
    ) = KeyInfo(kind, code, AwtKeyEvent.KEY_LOCATION_STANDARD, AwtKeyEvent.CHAR_UNDEFINED, shift, ctrl, alt, meta)

    private fun text(
        code: Int,
        shift: Boolean = false, ctrl: Boolean = false, alt: Boolean = false, meta: Boolean = false,
        kind: KeyInfo.Kind = KeyInfo.Kind.PRESSED, mac: Boolean = true,
    ) = lineEditingText(key(code, shift, ctrl, alt, meta, kind), mac)

    @Test
    fun optionAndLeftOrRightMoveByAWord() {
        assertEquals("\u001bb", text(AwtKeyEvent.VK_LEFT, alt = true))
        assertEquals("\u001bf", text(AwtKeyEvent.VK_RIGHT, alt = true))
    }

    @Test
    fun commandAndLeftOrRightGoToTheStartOrTheEndOfTheLine() {
        assertEquals("\u0001", text(AwtKeyEvent.VK_LEFT, meta = true))
        assertEquals("\u0005", text(AwtKeyEvent.VK_RIGHT, meta = true))
    }

    @Test
    fun commandAndBackspaceDeleteWhatIsBeforeTheCursor() {
        assertEquals("\u0015", text(AwtKeyEvent.VK_BACK_SPACE, meta = true))
    }

    @Test
    fun theReleaseIsMappedToo() {
        // For it to be consumed: the program is told nothing of the key.
        assertEquals("\u001bb", text(AwtKeyEvent.VK_LEFT, alt = true, kind = KeyInfo.Kind.RELEASED))
        assertEquals("\u0005", text(AwtKeyEvent.VK_RIGHT, meta = true, kind = KeyInfo.Kind.RELEASED))
    }

    @Test
    fun theKeysAreTheProgramsOwnElsewhereThanOnMacOS() {
        assertNull(text(AwtKeyEvent.VK_LEFT, alt = true, mac = false))
        assertNull(text(AwtKeyEvent.VK_RIGHT, meta = true, mac = false))
        assertNull(text(AwtKeyEvent.VK_BACK_SPACE, meta = true, mac = false))
    }

    @Test
    fun anotherModifierMakesItAnotherKey() {
        assertNull(text(AwtKeyEvent.VK_LEFT, alt = true, shift = true))
        assertNull(text(AwtKeyEvent.VK_LEFT, meta = true, shift = true))
        assertNull(text(AwtKeyEvent.VK_LEFT, alt = true, ctrl = true))
        assertNull(text(AwtKeyEvent.VK_LEFT, meta = true, ctrl = true))
        assertNull(text(AwtKeyEvent.VK_LEFT, alt = true, meta = true))
    }

    @Test
    fun otherKeysAndTypedCharactersAreNotMapped() {
        assertNull(text(AwtKeyEvent.VK_LEFT))
        assertNull(text(AwtKeyEvent.VK_UP, alt = true))
        assertNull(text(AwtKeyEvent.VK_DOWN, meta = true))
        assertNull(text(AwtKeyEvent.VK_BACK_SPACE, alt = true))
        assertNull(text(AwtKeyEvent.VK_LEFT, alt = true, kind = KeyInfo.Kind.TYPED))
    }
}
