package app.katty.terminal.compose

import app.katty.terminal.TerminalMouseButton
import java.awt.event.KeyEvent as AwtKeyEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TerminalClipboardTest {
    private fun key(
        code: Int,
        shift: Boolean = false, ctrl: Boolean = false, alt: Boolean = false, meta: Boolean = false,
        kind: KeyInfo.Kind = KeyInfo.Kind.PRESSED,
    ) = KeyInfo(kind, code, AwtKeyEvent.KEY_LOCATION_STANDARD, AwtKeyEvent.CHAR_UNDEFINED, shift, ctrl, alt, meta)

    @Test
    fun commandAndCAndVCopyAndPasteOnMacOS() {
        assertEquals(ClipboardShortcut.COPY, clipboardShortcut(key(AwtKeyEvent.VK_C, meta = true), mac = true))
        assertEquals(ClipboardShortcut.PASTE, clipboardShortcut(key(AwtKeyEvent.VK_V, meta = true), mac = true))
        // Their release is the terminal's too.
        assertEquals(ClipboardShortcut.COPY, clipboardShortcut(key(AwtKeyEvent.VK_C, meta = true, kind = KeyInfo.Kind.RELEASED), mac = true))
    }

    @Test
    fun controlAndShiftAndCAndVCopyAndPasteElsewhere() {
        assertEquals(ClipboardShortcut.COPY, clipboardShortcut(key(AwtKeyEvent.VK_C, ctrl = true, shift = true), mac = false))
        assertEquals(ClipboardShortcut.PASTE, clipboardShortcut(key(AwtKeyEvent.VK_V, ctrl = true, shift = true), mac = false))
    }

    @Test
    fun controlAndCIsTheProgramsOwnWhereControlAndShiftIsNeeded() {
        // An interrupt.
        assertNull(clipboardShortcut(key(AwtKeyEvent.VK_C, ctrl = true), mac = false))
        assertNull(clipboardShortcut(key(AwtKeyEvent.VK_C, ctrl = true), mac = true))
    }

    @Test
    fun theShortcutsOfTheOtherSystemAreNotOnesHere() {
        assertNull(clipboardShortcut(key(AwtKeyEvent.VK_C, ctrl = true, shift = true), mac = true))
        assertNull(clipboardShortcut(key(AwtKeyEvent.VK_C, meta = true), mac = false))
    }

    @Test
    fun anotherModifierMakesItAnotherKey() {
        assertNull(clipboardShortcut(key(AwtKeyEvent.VK_C, meta = true, shift = true), mac = true))
        assertNull(clipboardShortcut(key(AwtKeyEvent.VK_C, meta = true, alt = true), mac = true))
        assertNull(clipboardShortcut(key(AwtKeyEvent.VK_C, ctrl = true, shift = true, alt = true), mac = false))
    }

    @Test
    fun otherKeysAndTypedCharactersAreNotShortcuts() {
        assertNull(clipboardShortcut(key(AwtKeyEvent.VK_X, meta = true), mac = true))
        assertNull(clipboardShortcut(key(AwtKeyEvent.VK_C, meta = true, kind = KeyInfo.Kind.TYPED), mac = true))
    }

    private fun press(button: TerminalMouseButton, shift: Boolean = false, kind: PointerInfo.Kind = PointerInfo.Kind.PRESS) =
        PointerInfo(kind, 1f, 1f, button, shift = shift)

    @Test
    fun theMiddleButtonPastesWhenTheProgramDidNotAskForTheMouse() {
        assertEquals(true, middleClickPastes(press(TerminalMouseButton.MIDDLE), mouseTracking = false))
        assertEquals(true, middleClickPastes(press(TerminalMouseButton.MIDDLE, shift = true), mouseTracking = false))
    }

    @Test
    fun theMiddleButtonIsTheProgramsWhenItAskedForTheMouseUnlessShiftIsHeld() {
        assertEquals(false, middleClickPastes(press(TerminalMouseButton.MIDDLE), mouseTracking = true))
        assertEquals(true, middleClickPastes(press(TerminalMouseButton.MIDDLE, shift = true), mouseTracking = true))
    }

    @Test
    fun onlyAPressOfTheMiddleButtonPastes() {
        assertEquals(false, middleClickPastes(press(TerminalMouseButton.LEFT), mouseTracking = false))
        assertEquals(false, middleClickPastes(press(TerminalMouseButton.RIGHT), mouseTracking = false))
        assertEquals(false, middleClickPastes(press(TerminalMouseButton.MIDDLE, kind = PointerInfo.Kind.RELEASE), mouseTracking = false))
    }
}
