package app.katty

import app.katty.screens.fixedKeys
import app.katty.screens.shortcutKeys
import java.awt.event.KeyEvent as Awt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The shortcuts of a PC (Windows, Linux), whatever the system that runs the tests: [ShortcutBindingsTest] has a Mac's. */
class PcShortcutsTest {
    private val pc = KeyboardStyle.PC
    private val map = ShortcutMap.defaults(pc)

    private fun key(code: Int, ctrl: Boolean = false, alt: Boolean = false, shift: Boolean = false) = KeyBinding(code, meta = false, ctrl = ctrl, alt = alt, shift = shift)

    @Test
    fun theDefaultsAreTheTableThatWasChosen() {
        // Written out, not computed: the table that the user approved.
        val expected = mapOf(
            AppShortcut.ADD_PROJECT to "Ctrl+Shift+O", AppShortcut.OPEN_RECENT to "Alt+Shift+O", AppShortcut.NEW_WORKSPACE to "Ctrl+Shift+N",
            AppShortcut.CLOSE_WORKSPACE to "Alt+Shift+W", AppShortcut.REMOVE_PROJECT to "", AppShortcut.RENAME_WORKSPACE to "Alt+Shift+F2",
            AppShortcut.WORKSPACE_PREVIOUS to "Ctrl+PgUp", AppShortcut.WORKSPACE_NEXT to "Ctrl+PgDn",
            AppShortcut.PROJECT_PREVIOUS to "Ctrl+Shift+PgUp", AppShortcut.PROJECT_NEXT to "Ctrl+Shift+PgDn",
            AppShortcut.WORKSPACE_NUMBER to "Alt+1…9", AppShortcut.PANE_NUMBER to "Alt+Shift+1…9",
            AppShortcut.SPLIT_MAIN to "Ctrl+Shift+D", AppShortcut.SPLIT_OTHER to "Alt+Shift+D", AppShortcut.CLOSE_TERMINAL to "Ctrl+Shift+W",
            AppShortcut.RESTART_TERMINAL to "Alt+Shift+L", AppShortcut.REOPEN_CLOSED to "Ctrl+Shift+T", AppShortcut.MAXIMIZE to "Ctrl+Shift+Enter",
            AppShortcut.FOCUS_LEFT to "Alt+Left", AppShortcut.FOCUS_RIGHT to "Alt+Right", AppShortcut.FOCUS_UP to "Alt+Up", AppShortcut.FOCUS_DOWN to "Alt+Down",
            AppShortcut.RESIZE_LEFT to "Alt+Shift+Left", AppShortcut.RESIZE_RIGHT to "Alt+Shift+Right", AppShortcut.RESIZE_UP to "Alt+Shift+Up",
            AppShortcut.RESIZE_DOWN to "Alt+Shift+Down", AppShortcut.RENAME_TERMINAL to "Ctrl+Shift+F2",
            AppShortcut.RUN to "Ctrl+Shift+R", AppShortcut.RUN_TOGGLE to "Alt+Shift+R",
            AppShortcut.ATTENTION to "Ctrl+Shift+J", AppShortcut.JUMP_TO_WAITING to "Alt+Shift+J", AppShortcut.SIDEBAR to "Ctrl+Shift+B", AppShortcut.BOARD to "Alt+Shift+B",
            AppShortcut.FIND to "Ctrl+Shift+F", AppShortcut.SEARCH_ALL to "Alt+Shift+F", AppShortcut.FIND_NEXT to "Ctrl+Shift+G", AppShortcut.FIND_PREVIOUS to "Alt+Shift+G",
            AppShortcut.CLEAR_SCREEN to "Ctrl+Shift+L", AppShortcut.ASK_AI_COMMAND to "Ctrl+Shift+I",
            AppShortcut.PALETTE to "Ctrl+Shift+P", AppShortcut.SETTINGS to "Ctrl+,",
            AppShortcut.FONT_BIGGER to "Ctrl+=", AppShortcut.FONT_SMALLER to "Ctrl+-", AppShortcut.FONT_RESET to "Ctrl+0",
            AppShortcut.PRESENTATION to "Alt+Shift+P", AppShortcut.TEACH to "Alt+Shift+T",
        )

        assertEquals(AppShortcut.entries.toSet(), expected.keys, "A shortcut is missing from this list")
        for ((shortcut, label) in expected) assertEquals(label, map.label(shortcut), "$shortcut")
        assertEquals(AppShortcut.PALETTE, map.lookup(Awt.VK_K, false, true, false, true), "Ctrl+Shift+K opens the palette too")
    }

    @Test
    fun everyDefaultIsAcceptedNoneIsControlAndAltAndNoTwoAreTheSame() {
        val seen = HashMap<KeyBinding, AppShortcut>()
        for (shortcut in AppShortcut.entries) for (binding in shortcut.defaults(pc)) {
            assertTrue(!(binding.ctrl && binding.alt) && !binding.meta, "$shortcut: ${binding.label(pc)}")
            assertEquals(BindingCheck.Ok, ShortcutMap.defaults(pc).with(shortcut, null).check(shortcut, binding), "$shortcut: ${binding.label(pc)}")
            val other = seen.put(binding, shortcut)
            assertNull(other, "$shortcut and $other are both ${binding.label(pc)}")
            assertEquals(shortcut, map.lookup(if (shortcut.takesDigit) Awt.VK_5 else binding.keyCode, false, binding.ctrl, binding.alt, binding.shift))
        }
    }

    @Test
    fun whatTheProgramTheTerminalAndTheSystemNeedIsRefused() {
        fun check(binding: KeyBinding) = map.check(AppShortcut.FIND, binding)

        assertEquals(BindingCheck.NeedsModifier, check(key(Awt.VK_E, shift = true)))
        for (refused in listOf(
            key(Awt.VK_E, ctrl = true, alt = true), // AltGr: € on many keyboards
            key(Awt.VK_R, ctrl = true), // the program's search
            key(Awt.VK_LEFT, ctrl = true), // a word back
            key(Awt.VK_B, alt = true), // the program's Meta
            key(Awt.VK_C, ctrl = true, shift = true), // the terminal's copy
            key(Awt.VK_UP, ctrl = true, shift = true), // a prompt up
            key(Awt.VK_F4, alt = true), // the system's
            KeyBinding(Awt.VK_E, meta = true), // the Windows key
        )) {
            assertTrue(check(refused) is BindingCheck.Reserved, "${refused.label(pc)}: ${check(refused)}")
        }
        assertEquals(BindingCheck.Ok, check(key(Awt.VK_E, alt = true, shift = true)))
        assertEquals(BindingCheck.Ok, check(key(Awt.VK_F5, ctrl = true)))
    }

    @Test
    fun aBindingOfAMacInTheFileIsNotAPcs() {
        val read = ShortcutMap.of(mapOf("FIND" to "meta:70", "SPLIT_MAIN" to "alt+shift:69"), pc)

        assertEquals("Ctrl+Shift+F", read.label(AppShortcut.FIND), "Command is the Windows key there")
        assertEquals("Alt+Shift+E", read.label(AppShortcut.SPLIT_MAIN))
    }

    @Test
    fun theKeysOfAPcAreWrittenInWordsAndShownOneByOne() {
        assertEquals("Ctrl+Shift+Enter", key(Awt.VK_ENTER, ctrl = true, shift = true).label(pc))
        assertEquals("Alt+Backspace", key(Awt.VK_BACK_SPACE, alt = true).label(pc))
        assertEquals("Ctrl Shift D", shortcutKeys("Ctrl+Shift+D"))
        assertEquals("Ctrl +", shortcutKeys("Ctrl++"))
        assertEquals("Ctrl Num +", shortcutKeys("Ctrl+Num +"))
        assertEquals("⌘ ⇧ D", shortcutKeys("⌘⇧D"), "A Mac's still")
        assertEquals("F2", shortcutKeys("F2"))
    }

    @Test
    fun theFixedKeysOfAPcAreItsOwn() {
        val keys = fixedKeys(pc)
        assertEquals("Ctrl+Shift+C Ctrl+Shift+V Ctrl+Shift+A", keys.first().keys)
        assertTrue(keys.none { "⌘" in it.keys || "⌥" in it.keys }, "$keys")
    }

    @Test
    fun presentationModeShowsAPcsKeysAndNeverWhatAltGrTypes() {
        assertEquals(PresentedKey("Ctrl+Shift+C", "Copy"), presentedKey(Awt.VK_C, false, true, false, true, vertical = false, map = map))
        assertEquals("Split right", presentedKey(Awt.VK_D, false, true, false, true, vertical = false, map = map)?.caption)
        assertNull(presentedKey(Awt.VK_E, false, true, true, false, vertical = false, map = map), "AltGr+E types €")
    }
}
