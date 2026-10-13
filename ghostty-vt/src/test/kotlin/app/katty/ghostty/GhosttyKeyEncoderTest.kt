package app.katty.ghostty

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class GhosttyKeyEncoderTest {
    private val terminal = GhosttyTerminal(cols = 20, rows = 5)
    private val encoder = GhosttyKeyEncoder()

    @AfterTest
    fun close() {
        encoder.close()
        terminal.close()
    }

    private fun encode(event: GhosttyKeyEvent): String {
        encoder.setOptionsFrom(terminal)
        return encoder.encode(event).decodeToString()
    }

    @Test
    fun encodesPlainKeys() {
        assertEquals("\r", encode(GhosttyKeyEvent(GhosttyKey.ENTER)))
        assertEquals("\u001b[A", encode(GhosttyKeyEvent(GhosttyKey.ARROW_UP)))
        assertEquals("a", encode(GhosttyKeyEvent(GhosttyKey.A, text = "a", unshiftedCodepoint = 'a'.code)))
        assertEquals("\t", encode(GhosttyKeyEvent(GhosttyKey.TAB)))
    }

    @Test
    fun followsTheModesTheProgramSets() {
        terminal.vtWrite("\u001b[?1h")

        assertEquals("\u001bOA", encode(GhosttyKeyEvent(GhosttyKey.ARROW_UP)), "Application cursor keys")
    }

    @Test
    fun encodesShiftEnterDifferentlyOnceKittyKeyboardIsEnabled() {
        val shiftEnter = GhosttyKeyEvent(GhosttyKey.ENTER, mods = GhosttyMods.SHIFT)

        // Unlike xterm, libghostty does not send a plain Enter for Shift+Enter, even without the protocol.
        assertEquals("\u001b[27;2;13~", encode(shiftEnter))

        // CSI > 1 u: the program asks for the Kitty keyboard protocol's disambiguate flag.
        terminal.vtWrite("\u001b[>1u")
        assertEquals(1, terminal.kittyKeyboardFlags)

        assertEquals("\u001b[13;2u", encode(shiftEnter))
        assertEquals("\r", encode(GhosttyKeyEvent(GhosttyKey.ENTER)), "Enter itself is unchanged")
    }

    @Test
    fun encodesCtrlLettersAsControlCharactersUntilKittyKeyboardIsEnabled() {
        val ctrlC = GhosttyKeyEvent(GhosttyKey.C, mods = GhosttyMods.CTRL, text = "c", unshiftedCodepoint = 'c'.code)

        assertEquals("\u0003", encode(ctrlC))

        terminal.vtWrite("\u001b[>1u")
        assertEquals("\u001b[99;5u", encode(ctrlC))
    }

    @Test
    fun distinguishesCtrlITab() {
        // Ctrl+I, Ctrl+M and Ctrl+[ are not turned into Tab, Enter and Escape (fixterms), the text
        // being the character as typed, not the control character it would become.
        val ctrlI = GhosttyKeyEvent(GhosttyKey.I, mods = GhosttyMods.CTRL, text = "i", unshiftedCodepoint = 'i'.code)

        assertEquals("\u001b[105;5u", encode(ctrlI))
        assertEquals("\t", encode(GhosttyKeyEvent(GhosttyKey.TAB)))
    }

    @Test
    fun keyCodesAreDistinct() {
        assertEquals(GhosttyKey.entries.size, GhosttyKey.entries.map { it.code }.toSet().size)
    }
}
