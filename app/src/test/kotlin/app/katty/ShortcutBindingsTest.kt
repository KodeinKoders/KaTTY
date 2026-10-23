package app.katty

import java.awt.event.KeyEvent as Awt
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShortcutBindingsTest {
    @AfterTest
    fun restore() {
        Shortcuts.map = ShortcutMap.DEFAULT
    }

    private val map = ShortcutMap.DEFAULT

    private fun lookup(map: ShortcutMap, key: Int, meta: Boolean = true, ctrl: Boolean = false, alt: Boolean = false, shift: Boolean = false) =
        map.lookup(key, meta, ctrl, alt, shift)

    @Test
    fun theDefaultsAreWrittenAsTheyAlwaysWere() {
        // Written out, not computed: what the menus and the palette have shown must not change by itself.
        val expected = mapOf(
            AppShortcut.ADD_PROJECT to "⌘O", AppShortcut.NEW_WORKSPACE to "⌘N", AppShortcut.CLOSE_WORKSPACE to "⌘⇧W", AppShortcut.SPLIT_MAIN to "⌘D",
            AppShortcut.SPLIT_OTHER to "⌘⇧D", AppShortcut.CLOSE_TERMINAL to "⌘W", AppShortcut.RESTART_TERMINAL to "⌘R", AppShortcut.JUMP_TO_WAITING to "⌘⇧J",
            AppShortcut.ATTENTION to "⌘J", AppShortcut.BOARD to "⌘⇧B", AppShortcut.SIDEBAR to "⌘B", AppShortcut.MAXIMIZE to "⌘⇧↩",
            AppShortcut.RESIZE_LEFT to "⌘⌃←", AppShortcut.RESIZE_RIGHT to "⌘⌃→", AppShortcut.RESIZE_UP to "⌘⌃↑", AppShortcut.RESIZE_DOWN to "⌘⌃↓",
            AppShortcut.FIND_NEXT to "⌘G", AppShortcut.FIND_PREVIOUS to "⌘⇧G", AppShortcut.FONT_BIGGER to "⌘=", AppShortcut.FONT_SMALLER to "⌘-",
            AppShortcut.FONT_RESET to "⌘0", AppShortcut.MENU to "⌘,", AppShortcut.PALETTE to "⌘K", AppShortcut.FOCUS_LEFT to "⌘⌥←",
            AppShortcut.FOCUS_RIGHT to "⌘⌥→", AppShortcut.FOCUS_UP to "⌘⌥↑", AppShortcut.FOCUS_DOWN to "⌘⌥↓", AppShortcut.CLEAR_SCREEN to "⌘L",
            AppShortcut.FIND to "⌘F", AppShortcut.RENAME_TERMINAL to "⌘F2", AppShortcut.RENAME_WORKSPACE to "⌘⇧F2",
            AppShortcut.WORKSPACE_PREVIOUS to "⌘⇧[", AppShortcut.WORKSPACE_NEXT to "⌘⇧]", AppShortcut.WORKSPACE_NUMBER to "⌘1…9",
            AppShortcut.PANE_NUMBER to "⌘⌥1…9", AppShortcut.PROJECT_PREVIOUS to "⌘⌥[", AppShortcut.PROJECT_NEXT to "⌘⌥]",
        )

        assertEquals(AppShortcut.entries.toSet(), expected.keys, "A shortcut is missing from this list")
        for ((shortcut, label) in expected) assertEquals(label, map.label(shortcut), "$shortcut")
        assertEquals("⌘D", AppShortcut.SPLIT_MAIN.label)
    }

    @Test
    fun noTwoDefaultsAreTheSameKeys() {
        val seen = HashMap<KeyBinding, AppShortcut>()
        for (shortcut in AppShortcut.entries) for (binding in shortcut.defaults) {
            // The digit families are one binding each for the modifiers: they cannot collide with one another either.
            val other = seen.put(binding, shortcut)
            assertTrue(other == null, "$shortcut and $other are both ${binding.label}")
        }
    }

    @Test
    fun everyDefaultHasCommandAndIsFoundByItsOwnKeys() {
        for (shortcut in AppShortcut.entries) for (binding in shortcut.defaults) {
            assertTrue(binding.meta, "$shortcut is a shortcut of the window: it has Command")
            assertEquals(shortcut, lookup(map, if (shortcut.takesDigit) Awt.VK_5 else binding.keyCode, binding.meta, binding.ctrl, binding.alt, binding.shift), "$shortcut")
        }
    }

    @Test
    fun theModifiersMustBeExactlyTheBindings() {
        assertEquals(AppShortcut.SPLIT_MAIN, lookup(map, Awt.VK_D))
        assertEquals(AppShortcut.SPLIT_OTHER, lookup(map, Awt.VK_D, shift = true))
        assertNull(lookup(map, Awt.VK_D, ctrl = true))
        assertNull(lookup(map, Awt.VK_D, alt = true))
        assertNull(lookup(map, Awt.VK_D, meta = false), "Without Command it is the program's")
        assertNull(lookup(map, Awt.VK_F, ctrl = true), "Command, Control and F is the full screen of the system")
    }

    @Test
    fun theKeysThatMeanTheSameAreAliasesAndOnlyTheFirstIsShown() {
        for (shift in listOf(false, true)) {
            assertEquals(AppShortcut.FONT_BIGGER, lookup(map, Awt.VK_EQUALS, shift = shift))
            assertEquals(AppShortcut.FONT_BIGGER, lookup(map, Awt.VK_PLUS, shift = shift))
            assertEquals(AppShortcut.FONT_BIGGER, lookup(map, Awt.VK_ADD, shift = shift))
        }
        assertEquals(AppShortcut.FONT_SMALLER, lookup(map, Awt.VK_SUBTRACT))
        assertEquals("⌘=", map.label(AppShortcut.FONT_BIGGER))
    }

    @Test
    fun theDigitFamiliesTakeTheNineDigitsWithTheirModifiers() {
        for (digit in Awt.VK_1..Awt.VK_9) {
            assertEquals(AppShortcut.WORKSPACE_NUMBER, lookup(map, digit))
            assertEquals(AppShortcut.PANE_NUMBER, lookup(map, digit, alt = true))
        }
        // Zero is not a number of one: it resets the font.
        assertEquals(AppShortcut.FONT_RESET, lookup(map, Awt.VK_0))
        assertNull(lookup(map, Awt.VK_1, shift = true))
    }

    @Test
    fun aBindingIsKeptAsTextAndReadBack() {
        val binding = KeyBinding(Awt.VK_D, meta = true, ctrl = false, alt = true, shift = true)

        assertEquals("meta+alt+shift:68", binding.encode())
        assertEquals(binding, KeyBinding.decode(binding.encode()))
        assertEquals(KeyBinding(Awt.VK_F2), KeyBinding.decode("meta:113"))
        for (text in listOf("", "meta", "meta:", "meta:x", "meta:-4", "super+meta:68", ":0", "a:b:c")) assertNull(KeyBinding.decode(text), text)
    }

    @Test
    fun theKeysAreWrittenWithTheSymbolsOfAMacKeyboard() {
        assertEquals("⌘⇧↩", KeyBinding(Awt.VK_ENTER, shift = true).label)
        assertEquals("⌘⌃⌥⇧⌫", KeyBinding(Awt.VK_BACK_SPACE, ctrl = true, alt = true, shift = true).label)
        assertEquals("⌘F12", KeyBinding(Awt.VK_F12).label)
        assertEquals("⌘[", KeyBinding(Awt.VK_OPEN_BRACKET).label)
        assertEquals("⌘Num 3", KeyBinding(Awt.VK_NUMPAD3).label)
        assertEquals("⌘Space", KeyBinding(Awt.VK_SPACE).label)
        assertEquals("⌘7", KeyBinding(Awt.VK_7).label)
    }

    @Test
    fun aShortcutIsRebound() {
        val changed = map.with(AppShortcut.SPLIT_MAIN, KeyBinding(Awt.VK_E))

        assertEquals("⌘E", changed.label(AppShortcut.SPLIT_MAIN))
        assertEquals(AppShortcut.SPLIT_MAIN, lookup(changed, Awt.VK_E))
        assertNull(lookup(changed, Awt.VK_D), "The old keys are not the shortcut any more")
        assertFalse(changed.isDefault(AppShortcut.SPLIT_MAIN))
        assertEquals(mapOf("SPLIT_MAIN" to "meta:69"), changed.overrides)
    }

    @Test
    fun aShortcutIsUnboundAndResetAndEverythingIsResetAtOnce() {
        val unbound = map.with(AppShortcut.FIND, null)

        assertEquals("", unbound.label(AppShortcut.FIND))
        assertNull(unbound.bindingOf(AppShortcut.FIND))
        assertNull(lookup(unbound, Awt.VK_F))
        assertEquals(mapOf("FIND" to "none"), unbound.overrides)

        assertEquals(ShortcutMap.DEFAULT, unbound.reset(AppShortcut.FIND))
        assertEquals(emptyMap(), unbound.reset(AppShortcut.FIND).overrides)
        assertEquals(ShortcutMap.DEFAULT, unbound.with(AppShortcut.FONT_RESET, KeyBinding(Awt.VK_9)).resetAll())
    }

    @Test
    fun whatIsKeptInTheFileComesBack() {
        val changed = map.with(AppShortcut.SPLIT_MAIN, KeyBinding(Awt.VK_E, alt = true)).with(AppShortcut.FIND, null).with(AppShortcut.PANE_NUMBER, KeyBinding(Awt.VK_1, ctrl = true))

        assertEquals(changed, ShortcutMap.of(changed.overrides))
        assertEquals("⌘⌃1…9", ShortcutMap.of(changed.overrides).label(AppShortcut.PANE_NUMBER))
        assertEquals(ShortcutMap.DEFAULT, ShortcutMap.of(emptyMap()))
    }

    @Test
    fun whatCannotBeReadInTheFileIsTheDefault() {
        val read = ShortcutMap.of(
            mapOf(
                "NOT_A_SHORTCUT" to "meta:68", "FIND" to "gibberish", "SPLIT_MAIN" to "shift:69", "NEW_WORKSPACE" to "meta:16",
                "ADD_PROJECT" to "meta+alt:79",
            ),
        )

        assertEquals("⌘F", read.label(AppShortcut.FIND))
        assertEquals("⌘D", read.label(AppShortcut.SPLIT_MAIN), "A binding without Command would take keys that a terminal needs")
        assertEquals("⌘N", read.label(AppShortcut.NEW_WORKSPACE), "A modifier alone is not a key")
        assertEquals("⌘⌥O", read.label(AppShortcut.ADD_PROJECT), "What is fine is kept")
    }

    @Test
    fun aCombinationThatIsFreeIsOk() {
        assertEquals(BindingCheck.Ok, map.check(AppShortcut.SPLIT_MAIN, KeyBinding(Awt.VK_E)))
        assertEquals(BindingCheck.Ok, map.check(AppShortcut.SPLIT_MAIN, KeyBinding(Awt.VK_D)), "Its own keys are fine")
        assertEquals(BindingCheck.Ok, map.check(AppShortcut.FIND, KeyBinding(Awt.VK_E, alt = true, ctrl = true)))
    }

    @Test
    fun aShortcutNeedsCommand() {
        assertEquals(BindingCheck.NeedsCommand, map.check(AppShortcut.FIND, KeyBinding(Awt.VK_E, meta = false)))
        assertEquals(BindingCheck.NeedsCommand, map.check(AppShortcut.FIND, KeyBinding(Awt.VK_E, meta = false, ctrl = true, alt = true)))
    }

    @Test
    fun theKeysOfTheTerminalAndTheSystemAreRefusedWithAReason() {
        for (key in listOf(Awt.VK_C, Awt.VK_V, Awt.VK_A, Awt.VK_UP, Awt.VK_DOWN, Awt.VK_LEFT, Awt.VK_RIGHT, Awt.VK_BACK_SPACE, Awt.VK_Q, Awt.VK_H, Awt.VK_M, Awt.VK_TAB, Awt.VK_SPACE, Awt.VK_BACK_QUOTE)) {
            val check = map.check(AppShortcut.FIND, KeyBinding(key))
            assertTrue(check is BindingCheck.Reserved && check.reason.isNotBlank(), "${KeyBinding(key).label}: $check")
        }
        assertTrue(map.check(AppShortcut.FIND, KeyBinding(Awt.VK_SHIFT)) is BindingCheck.Reserved, "A modifier alone")
        // With another modifier they are free: ⌘⇧C is not the copy.
        assertEquals(BindingCheck.Ok, map.check(AppShortcut.FIND, KeyBinding(Awt.VK_C, shift = true)))
        assertEquals(BindingCheck.Ok, map.check(AppShortcut.FIND, KeyBinding(Awt.VK_UP, alt = true, ctrl = true)))
    }

    @Test
    fun aCombinationThatAnotherShortcutHasIsTaken() {
        assertEquals(BindingCheck.Taken(AppShortcut.SPLIT_MAIN), map.check(AppShortcut.FIND, KeyBinding(Awt.VK_D)))
        // An alias is taken too: ⌘ numeric plus is the font.
        assertEquals(BindingCheck.Taken(AppShortcut.FONT_BIGGER), map.check(AppShortcut.FIND, KeyBinding(Awt.VK_ADD)))
        // A digit is taken by the family that has the digits with those modifiers.
        assertEquals(BindingCheck.Taken(AppShortcut.WORKSPACE_NUMBER), map.check(AppShortcut.FIND, KeyBinding(Awt.VK_3)))
    }

    @Test
    fun aShortcutThatTakesDigitsIsGivenTheModifiersOfADigit() {
        assertEquals(BindingCheck.Ok, map.check(AppShortcut.PANE_NUMBER, KeyBinding(Awt.VK_4, ctrl = true)))
        assertTrue(map.check(AppShortcut.PANE_NUMBER, KeyBinding(Awt.VK_E, ctrl = true)) is BindingCheck.Reserved)
        // The modifiers of another family are taken.
        assertEquals(BindingCheck.Taken(AppShortcut.WORKSPACE_NUMBER), map.check(AppShortcut.PANE_NUMBER, KeyBinding(Awt.VK_4)))
        // And what is not a digit cannot take the modifiers of a family.
        assertEquals(BindingCheck.Taken(AppShortcut.PANE_NUMBER), map.check(AppShortcut.FIND, KeyBinding(Awt.VK_9, alt = true)))
    }

    @Test
    fun aRebindingTakesEffectInTheMapInUse() {
        Shortcuts.map = ShortcutMap.DEFAULT.with(AppShortcut.SPLIT_MAIN, KeyBinding(Awt.VK_E))

        assertEquals("⌘E", AppShortcut.SPLIT_MAIN.label)
        assertEquals(AppShortcut.SPLIT_MAIN, Shortcuts.map.lookup(Awt.VK_E, true, false, false, false))
        assertNotNull(Shortcuts.map.bindingOf(AppShortcut.SPLIT_MAIN))
    }

    @Test
    fun everyShortcutHasAGroupAndATitle() {
        for (shortcut in AppShortcut.entries) assertTrue(shortcut.title.isNotBlank(), "$shortcut")
        assertEquals(ShortcutGroup.entries.toSet(), AppShortcut.entries.map { it.group }.toSet(), "No group is empty")
    }

    @Test
    fun aFileCannotTakeTheKeysOfTheTerminalOrBindTwoShortcutsToTheSameKeys() {
        // ⌘C copies in a terminal: a shortcut written with it by hand keeps its default.
        val copy = ShortcutMap.of(mapOf("SPLIT_MAIN" to "meta:67"))
        assertTrue(copy.isDefault(AppShortcut.SPLIT_MAIN))
        assertNull(copy.lookup(Awt.VK_C, meta = true, ctrl = false, alt = false, shift = false))

        // Two shortcuts with the same keys: one of them keeps its default, and the keys are one shortcut's.
        val twice = ShortcutMap.of(mapOf("SPLIT_MAIN" to "meta+ctrl+alt:74", "FIND" to "meta+ctrl+alt:74"))
        assertEquals(1, listOf(AppShortcut.SPLIT_MAIN, AppShortcut.FIND).count { twice.isDefault(it) })
        assertTrue(twice.lookup(Awt.VK_J, meta = true, ctrl = true, alt = true, shift = false) in setOf(AppShortcut.SPLIT_MAIN, AppShortcut.FIND))

        // And the keys of a default that is still there.
        val taken = ShortcutMap.of(mapOf("FIND" to "meta:68"))
        assertTrue(taken.isDefault(AppShortcut.FIND), "⌘D is the main split's")
    }
}
