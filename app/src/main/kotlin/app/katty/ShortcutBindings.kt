package app.katty

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.awt.event.KeyEvent as Awt

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
    /** The modifiers as a Mac writes them, in this order: `⌘⌃⌥⇧`. */
    val modifiersText: String get() = (if (meta) "⌘" else "") + (if (ctrl) "⌃" else "") + (if (alt) "⌥" else "") + (if (shift) "⇧" else "")

    /** The key, as it is written next to the modifiers: `D`, `↩`, `F2`, `[`. */
    fun keyText(): String = keyText(keyCode)

    /** The keys as the menus and the palette show them: `⌘⇧D`. */
    val label: String get() = modifiersText + keyText()

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

        /** How a key is written: the symbol that a Mac keyboard has on it where there is one, else its name. */
        fun keyText(keyCode: Int): String = when (keyCode) {
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
    AGENTS("Agents and views"),
    TERMINAL("In a terminal"),
    LOOK("The look and the menu"),
}

/** What [ShortcutMap.check] says of a key combination offered for a shortcut. */
internal sealed interface BindingCheck {
    data object Ok : BindingCheck

    /** A shortcut has to include Command, so that a program in a terminal never sees it. */
    data object NeedsCommand : BindingCheck

    /** A combination that the terminal handles itself or the system keeps: [reason] says which. */
    data class Reserved(val reason: String) : BindingCheck

    /** A combination that another shortcut has. */
    data class Taken(val by: AppShortcut) : BindingCheck
}

/**
 * What every shortcut of the window is bound to: its defaults, with what the user changed. Immutable; [Shortcuts] holds the one in use.
 *
 * A shortcut has no binding, one, or (for the defaults of a few) several that mean the same: the first is the one that is shown.
 */
internal class ShortcutMap private constructor(private val bindings: Map<AppShortcut, List<KeyBinding>>) {
    /** The binding that is shown for [shortcut], `null` when it is unbound. */
    fun bindingOf(shortcut: AppShortcut): KeyBinding? = bindings[shortcut]?.firstOrNull()

    /**
     * The keys of [shortcut] as the menus and the palette show them (`⌘⇧D`; `⌘⌥1…9` for the ones that take a digit), and nothing for a shortcut
     * that is unbound.
     */
    fun label(shortcut: AppShortcut): String {
        val binding = bindingOf(shortcut) ?: return ""
        return if (shortcut.takesDigit) binding.modifiersText + "1…9" else binding.label
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
    fun isDefault(shortcut: AppShortcut): Boolean = bindings[shortcut] == shortcut.defaults

    /** What is different from the defaults, as it is kept in the file: the shortcut's name and its binding, or `none`. */
    val overrides: Map<String, String>
        get() = AppShortcut.entries.filter { !isDefault(it) }.associate { it.name to (bindingOf(it)?.encode() ?: NONE) }

    /** This map with [shortcut] bound to [binding], or unbound for `null`. Another shortcut that has the same keys is not touched: see [check]. */
    fun with(shortcut: AppShortcut, binding: KeyBinding?): ShortcutMap =
        ShortcutMap(bindings + (shortcut to listOfNotNull(binding)))

    fun reset(shortcut: AppShortcut): ShortcutMap = ShortcutMap(bindings + (shortcut to shortcut.defaults))

    fun resetAll(): ShortcutMap = DEFAULT

    /**
     * Whether [binding] can be given to [shortcut]: it has to include Command, not be a combination that the terminal or the system keeps, and not be
     * one that another shortcut has (the caller may take it from it). A shortcut that takes a digit is given the modifiers of the combination, whatever
     * the digit that was pressed.
     */
    fun check(shortcut: AppShortcut, binding: KeyBinding): BindingCheck {
        if (KeyBinding.isModifier(binding.keyCode)) return BindingCheck.Reserved("A modifier alone is not a shortcut.")
        if (!binding.meta) return BindingCheck.NeedsCommand
        reservedReason(binding)?.let { return BindingCheck.Reserved(it) }
        if (shortcut.takesDigit) {
            if (binding.keyCode !in DIGITS) return BindingCheck.Reserved("This one takes the digits 1 to 9: press a digit with the modifiers you want.")
            for (digit in DIGITS) lookup(digit, binding.meta, binding.ctrl, binding.alt, binding.shift)?.takeIf { it != shortcut }?.let { return BindingCheck.Taken(it) }
            return BindingCheck.Ok
        }
        lookup(binding.keyCode, binding.meta, binding.ctrl, binding.alt, binding.shift)?.takeIf { it != shortcut }?.let { return BindingCheck.Taken(it) }
        return BindingCheck.Ok
    }

    override fun equals(other: Any?): Boolean = other is ShortcutMap && other.bindings == bindings

    override fun hashCode(): Int = bindings.hashCode()

    companion object {
        /** What the user has not changed anything of. */
        val DEFAULT = ShortcutMap(AppShortcut.entries.associateWith { it.defaults })

        /** The value in the file of a shortcut that is unbound. */
        const val NONE = "none"

        private val DIGITS = Awt.VK_1..Awt.VK_9

        /**
         * The defaults with [overrides] (as [ShortcutMap.overrides] writes them) put on them. A name that is not a shortcut, a value that cannot be read, and a
         * binding that does not include Command (which would take a key that a terminal needs) are left out: that shortcut keeps its default.
         */
        fun of(overrides: Map<String, String>): ShortcutMap {
            if (overrides.isEmpty()) return DEFAULT
            val changed = LinkedHashMap<AppShortcut, List<KeyBinding>>()
            for ((name, value) in overrides) {
                val shortcut = AppShortcut.entries.firstOrNull { it.name == name } ?: continue
                if (value == NONE) {
                    changed[shortcut] = emptyList()
                } else {
                    KeyBinding.decode(value)?.takeIf { it.meta && !KeyBinding.isModifier(it.keyCode) }?.let { changed[shortcut] = listOf(it) }
                }
            }
            return ShortcutMap(DEFAULT.bindings + changed)
        }

        // What the terminal handles itself before the program, and what the system keeps: with Command and nothing else.
        private fun reservedReason(binding: KeyBinding): String? {
            if (binding.ctrl || binding.alt || binding.shift) return null
            return when (binding.keyCode) {
                Awt.VK_C, Awt.VK_V, Awt.VK_A -> "${binding.label} copies, pastes and selects all in a terminal."
                Awt.VK_UP, Awt.VK_DOWN -> "${binding.label} jumps from prompt to prompt in a terminal."
                Awt.VK_LEFT, Awt.VK_RIGHT, Awt.VK_BACK_SPACE -> "${binding.label} edits the line of the shell, as in any Mac terminal."
                Awt.VK_Q -> "${binding.label} quits the application, for the system."
                Awt.VK_H -> "${binding.label} hides the application, for the system."
                Awt.VK_M -> "${binding.label} minimizes the window, for the system."
                Awt.VK_TAB, Awt.VK_SPACE, Awt.VK_BACK_QUOTE -> "${binding.label} is kept by the system."
                else -> null
            }
        }
    }
}

/**
 * The shortcuts that are in use: one map for the whole process, which the window sets from the settings. It is snapshot state, so what shows a
 * label in a composition draws again when the user changes a shortcut. A test that changes it puts it back.
 */
internal object Shortcuts {
    var map: ShortcutMap by mutableStateOf(ShortcutMap.DEFAULT)
}
