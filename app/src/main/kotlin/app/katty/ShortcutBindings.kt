package app.katty

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.katty.core.isMacOs
import java.awt.event.KeyEvent as Awt

/**
 * The keyboard that the shortcuts are made for: a Mac's, where they have Command, which no program in a terminal sees, and are written with its
 * symbols (`⌘⇧D`), or a PC's (Windows, Linux), where they have Control and Shift or Alt and Shift (Control alone is the program's: Ctrl+C, Ctrl+R;
 * Control and Alt together is AltGr, which types characters on many keyboards), and are written in words (`Ctrl+Shift+D`).
 */
internal enum class KeyboardStyle {
    MAC,
    PC,
    ;

    companion object {
        /** The keyboard of the system that the application runs on: the one of the defaults. */
        val current: KeyboardStyle = if (isMacOs) MAC else PC

        /** The keyboard of the shortcuts in use ([Shortcuts.map]), which labels and keys follow: the system's, but in a test that sets another. */
        val inUse: KeyboardStyle get() = Shortcuts.map.style
    }
}

/**
 * A key and the modifiers that go with it: what a shortcut is bound to. The key is its AWT key code, which is what Compose Desktop's
 * `Key.nativeKeyCode` is: it is the key and not the character, so it is the same physical key on every layout.
 */
internal data class KeyBinding(
    val keyCode: Int,
    val meta: Boolean = true,
    val ctrl: Boolean = false,
    val alt: Boolean = false,
    val shift: Boolean = false,
) {
    /** The modifiers as the keyboard of the system writes them: `⌘⌃⌥⇧` on a Mac, in this order, and `Ctrl+Alt+Shift+` on a PC. */
    val modifiersText: String get() = modifiersText(KeyboardStyle.inUse)

    fun modifiersText(style: KeyboardStyle): String = when (style) {
        KeyboardStyle.MAC -> (if (meta) "⌘" else "") + (if (ctrl) "⌃" else "") + (if (alt) "⌥" else "") + (if (shift) "⇧" else "")
        KeyboardStyle.PC -> (if (meta) "Win+" else "") + (if (ctrl) "Ctrl+" else "") + (if (alt) "Alt+" else "") + (if (shift) "Shift+" else "")
    }

    /** The key, as it is written next to the modifiers: `D`, `↩`, `F2`, `[` (`Enter` on a PC). */
    fun keyText(style: KeyboardStyle = KeyboardStyle.inUse): String = keyText(keyCode, style)

    /** The keys as the menus and the palette show them: `⌘⇧D`, `Ctrl+Shift+D`. */
    val label: String get() = label(KeyboardStyle.inUse)

    fun label(style: KeyboardStyle): String = modifiersText(style) + keyText(style)

    /** What is kept in the file: the modifiers and the key code, `meta+shift:68`. */
    fun encode(): String = listOfNotNull("meta".takeIf { meta }, "ctrl".takeIf { ctrl }, "alt".takeIf { alt }, "shift".takeIf { shift }).joinToString("+") + ":$keyCode"

    /** The same modifiers, whatever the key. */
    fun sameModifiers(other: KeyBinding): Boolean = meta == other.meta && ctrl == other.ctrl && alt == other.alt && shift == other.shift

    companion object {
        /** What [encode] wrote, or `null` for text that is not that. */
        fun decode(text: String): KeyBinding? {
            val (modifiers, key) = text.split(':', limit = 2).takeIf { it.size == 2 } ?: return null
            val names = modifiers.split('+').filter { it.isNotEmpty() }
            if (names.any { it !in setOf("meta", "ctrl", "alt", "shift") }) return null
            val keyCode = key.toIntOrNull()?.takeIf { it > 0 } ?: return null
            return KeyBinding(keyCode, "meta" in names, "ctrl" in names, "alt" in names, "shift" in names)
        }

        /**
         * How a key is written: the symbol that a Mac keyboard has on it where there is one, else its name. A PC writes the keys that have no
         * character in words (`Left`, `Enter`, `Backspace`), as Windows does.
         */
        fun keyText(keyCode: Int, style: KeyboardStyle = KeyboardStyle.inUse): String = if (style == KeyboardStyle.PC) pcKeyText(keyCode) ?: macKeyText(keyCode) else macKeyText(keyCode)

        private fun pcKeyText(keyCode: Int): String? = when (keyCode) {
            Awt.VK_LEFT -> "Left"
            Awt.VK_RIGHT -> "Right"
            Awt.VK_UP -> "Up"
            Awt.VK_DOWN -> "Down"
            Awt.VK_ENTER -> "Enter"
            Awt.VK_BACK_SPACE -> "Backspace"
            Awt.VK_DELETE -> "Delete"
            Awt.VK_ESCAPE -> "Esc"
            Awt.VK_TAB -> "Tab"
            else -> null
        }

        private fun macKeyText(keyCode: Int): String = when (keyCode) {
            Awt.VK_LEFT -> "←"
            Awt.VK_RIGHT -> "→"
            Awt.VK_UP -> "↑"
            Awt.VK_DOWN -> "↓"
            Awt.VK_ENTER -> "↩"
            Awt.VK_BACK_SPACE -> "⌫"
            Awt.VK_DELETE -> "⌦"
            Awt.VK_ESCAPE -> "⎋"
            Awt.VK_TAB -> "⇥"
            Awt.VK_SPACE -> "Space"
            Awt.VK_PAGE_UP -> "PgUp"
            Awt.VK_PAGE_DOWN -> "PgDn"
            Awt.VK_HOME -> "Home"
            Awt.VK_END -> "End"
            Awt.VK_OPEN_BRACKET -> "["
            Awt.VK_CLOSE_BRACKET -> "]"
            Awt.VK_COMMA -> ","
            Awt.VK_PERIOD -> "."
            Awt.VK_MINUS -> "-"
            Awt.VK_EQUALS -> "="
            Awt.VK_PLUS -> "+"
            Awt.VK_SLASH -> "/"
            Awt.VK_SEMICOLON -> ";"
            Awt.VK_QUOTE -> "'"
            Awt.VK_BACK_SLASH -> "\\"
            Awt.VK_BACK_QUOTE -> "`"
            in Awt.VK_F1..Awt.VK_F12 -> "F${keyCode - Awt.VK_F1 + 1}"
            in Awt.VK_0..Awt.VK_9, in Awt.VK_A..Awt.VK_Z -> keyCode.toChar().toString()
            in Awt.VK_NUMPAD0..Awt.VK_NUMPAD9 -> "Num ${keyCode - Awt.VK_NUMPAD0}"
            Awt.VK_ADD -> "Num +"
            Awt.VK_SUBTRACT -> "Num -"
            else -> Awt.getKeyText(keyCode)
        }

        /** Whether the key is a modifier: pressed alone, it is not a shortcut, only the start of one. */
        fun isModifier(keyCode: Int): Boolean =
            keyCode == Awt.VK_META || keyCode == Awt.VK_CONTROL || keyCode == Awt.VK_ALT || keyCode == Awt.VK_SHIFT || keyCode == Awt.VK_ALT_GRAPH ||
                keyCode == Awt.VK_CAPS_LOCK || keyCode == Awt.VK_WINDOWS
    }
}

/** What the dialog groups the shortcuts by. */
internal enum class ShortcutGroup(val title: String) {
    PROJECTS("Projects and workspaces"),
    PANES("Panes"),
    RUN("Run terminal"),
    AGENTS("Agents and views"),
    TERMINAL("In a terminal"),
    LOOK("The look and the menu"),
}

/** What [ShortcutMap.check] says of a key combination offered for a shortcut. */
internal sealed interface BindingCheck {
    data object Ok : BindingCheck

    /** A shortcut has to include Command on a Mac, Control or Alt on a PC, so that a program in a terminal never sees it. */
    data object NeedsModifier : BindingCheck

    /** A combination that the terminal handles itself or the system keeps: [reason] says which. */
    data class Reserved(val reason: String) : BindingCheck

    /** A combination that another shortcut has. */
    data class Taken(val by: AppShortcut) : BindingCheck
}

/**
 * What every shortcut of the window is bound to: its defaults for the keyboard of [style], with what the user changed. Immutable; [Shortcuts]
 * holds the one in use.
 *
 * A shortcut has no binding, one, or (for the defaults of a few) several that mean the same: the first is the one that is shown.
 */
internal class ShortcutMap private constructor(private val bindings: Map<AppShortcut, List<KeyBinding>>, val style: KeyboardStyle) {
    /** The binding that is shown for [shortcut], `null` when it is unbound. */
    fun bindingOf(shortcut: AppShortcut): KeyBinding? = bindings[shortcut]?.firstOrNull()

    /**
     * The keys of [shortcut] as the menus and the palette show them (`⌘⇧D`; `⌘⌥1…9` for the ones that take a digit; `Ctrl+Shift+D` on a PC), and
     * nothing for a shortcut that is unbound.
     */
    fun label(shortcut: AppShortcut): String {
        val binding = bindingOf(shortcut) ?: return ""
        return if (shortcut.takesDigit) binding.modifiersText(style) + "1…9" else binding.label(style)
    }

    /** The shortcut that these keys are, `null` when they are none: the modifiers must be exactly the binding's. */
    fun lookup(keyCode: Int, meta: Boolean, ctrl: Boolean, alt: Boolean, shift: Boolean): AppShortcut? {
        val pressed = KeyBinding(keyCode, meta, ctrl, alt, shift)
        for ((shortcut, list) in bindings) for (binding in list) {
            if (!binding.sameModifiers(pressed)) continue
            if (if (shortcut.takesDigit) keyCode in DIGITS else binding.keyCode == keyCode) return shortcut
        }
        return null
    }

    /** Whether [shortcut] is bound as it is by default. */
    fun isDefault(shortcut: AppShortcut): Boolean = bindings[shortcut] == shortcut.defaults(style)

    /** What is different from the defaults, as it is kept in the file: the shortcut's name and its binding, or `none`. */
    val overrides: Map<String, String>
        get() = AppShortcut.entries.filter { !isDefault(it) }.associate { it.name to (bindingOf(it)?.encode() ?: NONE) }

    /** This map with [shortcut] bound to [binding], or unbound for `null`. Another shortcut that has the same keys is not touched: see [check]. */
    fun with(shortcut: AppShortcut, binding: KeyBinding?): ShortcutMap =
        ShortcutMap(bindings + (shortcut to listOfNotNull(binding)), style)

    fun reset(shortcut: AppShortcut): ShortcutMap = ShortcutMap(bindings + (shortcut to shortcut.defaults(style)), style)

    fun resetAll(): ShortcutMap = defaults(style)

    /**
     * Whether [binding] can be given to [shortcut]: it has to include Command on a Mac (Control or Alt on a PC, not both), not be a combination that
     * the terminal or the system keeps, and not be one that another shortcut has (the caller may take it from it). A shortcut that takes a digit is
     * given the modifiers of the combination, whatever the digit that was pressed.
     */
    fun check(shortcut: AppShortcut, binding: KeyBinding): BindingCheck {
        if (KeyBinding.isModifier(binding.keyCode)) return BindingCheck.Reserved("A modifier alone is not a shortcut.")
        modifierCheck(binding, style)?.let { return it }
        reservedReason(binding, style)?.let { return BindingCheck.Reserved(it) }
        if (shortcut.takesDigit) {
            if (binding.keyCode !in DIGITS) return BindingCheck.Reserved("This one takes the digits 1 to 9: press a digit with the modifiers you want.")
            for (digit in DIGITS) lookup(digit, binding.meta, binding.ctrl, binding.alt, binding.shift)?.takeIf { it != shortcut }?.let { return BindingCheck.Taken(it) }
            return BindingCheck.Ok
        }
        lookup(binding.keyCode, binding.meta, binding.ctrl, binding.alt, binding.shift)?.takeIf { it != shortcut }?.let { return BindingCheck.Taken(it) }
        return BindingCheck.Ok
    }

    override fun equals(other: Any?): Boolean = other is ShortcutMap && other.bindings == bindings && other.style == style

    override fun hashCode(): Int = bindings.hashCode()

    companion object {
        /** The defaults of the keyboard of [style]: what the user has not changed anything of. */
        fun defaults(style: KeyboardStyle): ShortcutMap = if (style == KeyboardStyle.current) DEFAULT else ShortcutMap(AppShortcut.entries.associateWith { it.defaults(style) }, style)

        /** What the user has not changed anything of, on this system's keyboard. */
        val DEFAULT = ShortcutMap(AppShortcut.entries.associateWith { it.defaults(KeyboardStyle.current) }, KeyboardStyle.current)

        /** The value in the file of a shortcut that is unbound. */
        const val NONE = "none"

        private val DIGITS = Awt.VK_1..Awt.VK_9

        /** The shortcuts that had another name, by their former name: a file written before keeps their keys. */
        private val FORMER_NAMES = mapOf("MENU" to AppShortcut.SETTINGS)

        /**
         * The defaults of [style] with [overrides] (as [ShortcutMap.overrides] writes them) put on them. A name that is not a shortcut, a value that cannot
         * be read, and a binding that [check] refuses (it does not have the modifiers of the keyboard, the terminal or the system keeps it, another
         * shortcut has it: a file edited by hand, or written on another system) are left out: that shortcut keeps its default. Of two overrides with
         * the same keys, the one that comes later in the file keeps them.
         */
        fun of(overrides: Map<String, String>, style: KeyboardStyle = KeyboardStyle.current): ShortcutMap {
            val defaults = defaults(style)
            if (overrides.isEmpty()) return defaults
            val changed = LinkedHashMap<AppShortcut, List<KeyBinding>>()
            for ((name, value) in overrides) {
                val shortcut = AppShortcut.entries.firstOrNull { it.name == name } ?: FORMER_NAMES[name] ?: continue
                if (value == NONE) {
                    changed[shortcut] = emptyList()
                } else {
                    KeyBinding.decode(value)?.takeIf { modifierCheck(it, style) == null && !KeyBinding.isModifier(it.keyCode) }?.let { changed[shortcut] = listOf(it) }
                }
            }
            var map = ShortcutMap(defaults.bindings + changed, style)
            for ((shortcut, list) in changed) {
                val binding = list.singleOrNull() ?: continue
                // Against the others as they are in the end, without this one's own keys.
                if (ShortcutMap(map.bindings - shortcut, style).check(shortcut, binding) != BindingCheck.Ok) map = map.reset(shortcut)
            }
            return map
        }

        // What a shortcut needs of its modifiers: Command on a Mac; on a PC Control or Alt, and not both, which is AltGr, nor the Windows key.
        private fun modifierCheck(binding: KeyBinding, style: KeyboardStyle): BindingCheck? = when (style) {
            KeyboardStyle.MAC -> if (binding.meta) null else BindingCheck.NeedsModifier
            KeyboardStyle.PC -> when {
                binding.meta -> BindingCheck.Reserved("${binding.label(style)}: the Windows key is the system's.")
                !binding.ctrl && !binding.alt -> BindingCheck.NeedsModifier
                binding.ctrl && binding.alt -> BindingCheck.Reserved("Ctrl+Alt is AltGr, which types characters on many keyboards.")
                else -> null
            }
        }

        // What the terminal handles itself before the program, what the program needs, and what the system keeps.
        private fun reservedReason(binding: KeyBinding, style: KeyboardStyle): String? {
            val label = binding.label(style)
            return when (style) {
                KeyboardStyle.MAC -> {
                    if (binding.ctrl || binding.alt || binding.shift) return null
                    when (binding.keyCode) {
                        Awt.VK_C, Awt.VK_V, Awt.VK_A -> "$label copies, pastes and selects all in a terminal."
                        Awt.VK_UP, Awt.VK_DOWN -> "$label jumps from prompt to prompt in a terminal."
                        Awt.VK_LEFT, Awt.VK_RIGHT, Awt.VK_BACK_SPACE -> "$label edits the line of the shell, as in any Mac terminal."
                        Awt.VK_Q -> "$label quits the application, for the system."
                        Awt.VK_H -> "$label hides the application, for the system."
                        Awt.VK_M -> "$label minimizes the window, for the system."
                        Awt.VK_TAB, Awt.VK_SPACE, Awt.VK_BACK_QUOTE -> "$label is kept by the system."
                        else -> null
                    }
                }
                KeyboardStyle.PC -> when {
                    binding.ctrl && binding.shift && binding.keyCode in setOf(Awt.VK_C, Awt.VK_V, Awt.VK_A) -> "$label copies, pastes and selects all in a terminal."
                    binding.ctrl && binding.shift && binding.keyCode in setOf(Awt.VK_UP, Awt.VK_DOWN) -> "$label jumps from prompt to prompt in a terminal."
                    binding.shift -> null
                    binding.ctrl && binding.keyCode in PROGRAM_CTRL_KEYS -> "$label is the program's (Ctrl+C interrupts it, Ctrl+R searches, Ctrl+Left goes back a word…)."
                    binding.alt && binding.keyCode in Awt.VK_A..Awt.VK_Z -> "$label is the program's Meta key (Alt+B and Alt+F move by word)."
                    binding.alt && binding.keyCode in setOf(Awt.VK_F4, Awt.VK_TAB, Awt.VK_SPACE, Awt.VK_ESCAPE) -> "$label is kept by the system."
                    binding.ctrl && binding.keyCode == Awt.VK_ESCAPE -> "$label is kept by the system."
                    else -> null
                }
            }
        }

        // What Control alone types for a program on a PC: the control characters, and the editing of the line by word.
        private val PROGRAM_CTRL_KEYS: Set<Int> = (Awt.VK_A..Awt.VK_Z).toSet() + setOf(
            Awt.VK_OPEN_BRACKET, Awt.VK_CLOSE_BRACKET, Awt.VK_BACK_SLASH, Awt.VK_SPACE,
            Awt.VK_LEFT, Awt.VK_RIGHT, Awt.VK_UP, Awt.VK_DOWN, Awt.VK_HOME, Awt.VK_END, Awt.VK_BACK_SPACE, Awt.VK_DELETE, Awt.VK_ENTER, Awt.VK_TAB,
        )
    }
}

/**
 * The shortcuts that are in use: one map for the whole process, which the window sets from the settings. It is snapshot state, so what shows a
 * label in a composition draws again when the user changes a shortcut. A test that changes it puts it back.
 */
internal object Shortcuts {
    var map: ShortcutMap by mutableStateOf(ShortcutMap.DEFAULT)
}
