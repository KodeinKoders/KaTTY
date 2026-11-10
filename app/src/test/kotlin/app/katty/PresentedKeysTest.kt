package app.katty

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import java.awt.event.KeyEvent as Awt

class PresentedKeysTest {
    private fun key(code: Int, meta: Boolean = false, ctrl: Boolean = false, alt: Boolean = false, shift: Boolean = false, vertical: Boolean = false, map: ShortcutMap = ShortcutMap.DEFAULT) =
        presentedKey(code, meta, ctrl, alt, shift, vertical, map)

    // A password typed during a demo must not be on the screen: nothing that types is shown.
    @Test
    fun whatIsTypedIsNeverShown() {
        for (code in listOf(Awt.VK_A, Awt.VK_Z, Awt.VK_1, Awt.VK_SPACE, Awt.VK_ENTER, Awt.VK_BACK_SPACE, Awt.VK_PERIOD, Awt.VK_F5)) {
            assertNull(key(code), "${KeyBinding.keyText(code)}")
            assertNull(key(code, shift = true), "⇧${KeyBinding.keyText(code)}")
            assertNull(key(code, alt = true), "⌥${KeyBinding.keyText(code)}: the characters of Option")
            assertNull(key(code, alt = true, shift = true), "⌥⇧${KeyBinding.keyText(code)}")
        }
        // The program's: only the terminal's own (with Shift) are shown.
        assertNull(key(Awt.VK_PAGE_UP))
        assertNull(key(Awt.VK_HOME))
    }

    @Test
    fun aModifierAloneIsNothing() {
        for (code in listOf(Awt.VK_META, Awt.VK_CONTROL, Awt.VK_ALT, Awt.VK_SHIFT, Awt.VK_CAPS_LOCK)) {
            assertNull(key(code, meta = true, ctrl = true, alt = true, shift = true))
        }
    }

    @Test
    fun aShortcutOfTheWindowSaysWhatItDoesAsTheLayoutDrawsIt() {
        assertEquals(PresentedKey("⌘D", "Split right"), key(Awt.VK_D, meta = true))
        assertEquals(PresentedKey("⌘D", "Split down"), key(Awt.VK_D, meta = true, vertical = true))
        assertEquals(PresentedKey("⌘⇧D", "Split down"), key(Awt.VK_D, meta = true, shift = true))
        assertEquals(PresentedKey("⌘W", "Close the terminal"), key(Awt.VK_W, meta = true))
        assertEquals(PresentedKey("⌘3", "Workspace 3"), key(Awt.VK_3, meta = true))
        assertEquals(PresentedKey("⌘⌥2", "Pane 2"), key(Awt.VK_2, meta = true, alt = true))
        assertEquals(PresentedKey("⌘⌥P", "Start or stop presentation mode"), key(Awt.VK_P, meta = true, alt = true))
    }

    @Test
    fun aShortcutThatTheUserBoundIsKnownByItsNewKeys() {
        val map = ShortcutMap.DEFAULT.with(AppShortcut.CLOSE_TERMINAL, KeyBinding(Awt.VK_E))

        assertEquals(PresentedKey("⌘E", "Close the terminal"), key(Awt.VK_E, meta = true, map = map))
        assertEquals(PresentedKey("⌘W"), key(Awt.VK_W, meta = true, map = map), "Shown, but it does nothing now")
    }

    @Test
    fun theKeysOfTheTerminalSayWhatTheyDo() {
        assertEquals(PresentedKey("⌘C", "Copy"), key(Awt.VK_C, meta = true))
        assertEquals(PresentedKey("⌘V", "Paste"), key(Awt.VK_V, meta = true))
        assertEquals(PresentedKey("⌘↑", "Jump to the previous prompt"), key(Awt.VK_UP, meta = true))
        assertEquals(PresentedKey("⇧PgUp", "Scroll up a page"), key(Awt.VK_PAGE_UP, shift = true))
        assertEquals(PresentedKey("⇧End", "Scroll to the bottom"), key(Awt.VK_END, shift = true))
    }

    @Test
    fun controlEscapeTabAndTheArrowsAreShownAsTheyAre() {
        assertEquals(PresentedKey("⌃C"), key(Awt.VK_C, ctrl = true))
        assertEquals(PresentedKey("⌃R"), key(Awt.VK_R, ctrl = true))
        assertEquals(PresentedKey("⎋"), key(Awt.VK_ESCAPE))
        assertEquals(PresentedKey("⇥"), key(Awt.VK_TAB))
        assertEquals(PresentedKey("⇧⇥"), key(Awt.VK_TAB, shift = true))
        assertEquals(PresentedKey("↓"), key(Awt.VK_DOWN))
        assertEquals(PresentedKey("⌥←"), key(Awt.VK_LEFT, alt = true))
        assertEquals(PresentedKey("⌘→"), key(Awt.VK_RIGHT, meta = true), "A line editing key of the shell, which is no shortcut")
    }

    @Test
    fun theSameKeyAgainCountsOnItsLineAndThreeLinesAreKeptForAWhile() {
        var now = 0L
        val keys = PresentedKeys { now }
        val down = PresentedKey("↓")

        repeat(5) { keys.show(down); now += 100 }
        assertEquals(listOf("↓ ×5"), keys.lines.map { it.text })

        now += PresentedKeys.REPEAT_MS + 1
        keys.show(down)
        assertEquals(listOf("↓ ×5", "↓"), keys.lines.map { it.text }, "Pressed again after a pause, it is a new line")

        keys.show(PresentedKey("⌘D", "Split right"))
        keys.show(PresentedKey("⌃C"))
        assertEquals(listOf("↓", "⌘D", "⌃C"), keys.lines.map { it.text }, "Three lines at most, the newest last")

        now += PresentedKeys.SHOWN_MS
        keys.expire()
        assertEquals(emptyList(), keys.lines)
    }
}
