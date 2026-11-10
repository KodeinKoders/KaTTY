package app.katty

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.type
import java.awt.event.KeyEvent as Awt

/** A key that presentation mode shows: the keys as a Mac writes them (`⌘⇧D`, `⌃C`, `⎋`), and what they do, when it is known. */
internal data class PresentedKey(val keys: String, val caption: String? = null)

// What the terminal does with its own keys (`TerminalKeys`), as the palette calls it.
private val TERMINAL_KEY_CAPTIONS = mapOf(
    TerminalKeys.COPY to "Copy",
    TerminalKeys.PASTE to "Paste",
    TerminalKeys.SELECT_ALL to "Select all",
    TerminalKeys.PREVIOUS_PROMPT to "Jump to the previous prompt",
    TerminalKeys.NEXT_PROMPT to "Jump to the next prompt",
    TerminalKeys.SCROLL_PAGE_UP to "Scroll up a page",
    TerminalKeys.SCROLL_PAGE_DOWN to "Scroll down a page",
    TerminalKeys.SCROLL_TO_TOP to "Scroll to the top",
    TerminalKeys.SCROLL_TO_BOTTOM to "Scroll to the bottom",
)

// The keys that are shown without Command or Control: they type nothing.
private val SHOWN_ALONE = setOf(Awt.VK_ESCAPE, Awt.VK_TAB, Awt.VK_LEFT, Awt.VK_RIGHT, Awt.VK_UP, Awt.VK_DOWN)

/**
 * What presentation mode shows of a key that goes down, or `null` for a key that it does not show. **What is typed is never shown**, so that a
 * password typed during a demo is not on the screen: a key is shown only with Command or Control, or when it is Escape, Tab or an arrow (with
 * the modifiers that go with it), or one of the terminal's own keys (`⇧PgUp`…). A letter, a digit, Shift or Option and a key (the characters that
 * Option types), Return, Backspace and Space are not. A shortcut of the window says what it does, as the user bound it ([map]), and so does a
 * key of the terminal.
 *
 * @param vertical Whether the window has the vertical layout, where the main direction of a split is down.
 */
internal fun presentedKey(
    keyCode: Int,
    meta: Boolean,
    ctrl: Boolean,
    alt: Boolean,
    shift: Boolean,
    vertical: Boolean,
    map: ShortcutMap = Shortcuts.map,
): PresentedKey? {
    if (KeyBinding.isModifier(keyCode)) return null
    val keys = KeyBinding(keyCode, meta, ctrl, alt, shift).label
    map.lookup(keyCode, meta, ctrl, alt, shift)?.let { return PresentedKey(keys, it.caption(vertical, digitOf(keyCode))) }
    TERMINAL_KEY_CAPTIONS[keys]?.let { return PresentedKey(keys, it) }
    return if (meta || ctrl || keyCode in SHOWN_ALONE) PresentedKey(keys) else null
}

/** What presentation mode shows of [this] event: a key that goes down, by [presentedKey]. */
internal fun KeyEvent.presented(vertical: Boolean): PresentedKey? =
    if (type != KeyEventType.KeyDown) null else presentedKey(key.nativeKeyCode, isMetaPressed, isCtrlPressed, isAltPressed, isShiftPressed, vertical)

private fun digitOf(keyCode: Int): Int? = if (keyCode in Awt.VK_1..Awt.VK_9) keyCode - Awt.VK_0 else null

/**
 * What [this] shortcut is called when it is shown in presentation mode: its title, but the split that it makes as it is drawn in the layout, and
 * the workspace or the pane of its [number].
 */
internal fun AppShortcut.caption(vertical: Boolean, number: Int?): String = when (this) {
    AppShortcut.SPLIT_MAIN -> if (vertical) "Split down" else "Split right"
    AppShortcut.SPLIT_OTHER -> if (vertical) "Split right" else "Split down"
    AppShortcut.WORKSPACE_NUMBER -> number?.let { "Workspace $it" } ?: title
    AppShortcut.PANE_NUMBER -> number?.let { "Pane $it" } ?: title
    else -> title
}

/**
 * The keys that presentation mode shows, in snapshot state: the last [MAX_LINES] of them, each for [SHOWN_MS] after it was last pressed. The same
 * key pressed again within [REPEAT_MS] counts on its line (`↓ ×5`) instead of taking another.
 */
internal class PresentedKeys(private val now: () -> Long = System::currentTimeMillis) {
    /** A line of what is shown: a key, how many times it was pressed in a row, and when it was last. */
    data class Line(val id: Long, val key: PresentedKey, val count: Int, val at: Long) {
        /** The keys, with the count when there is one. */
        val text: String get() = if (count > 1) "${key.keys} ×$count" else key.keys
    }

    var lines: List<Line> by mutableStateOf(emptyList())
        private set
    private var nextId = 0L

    fun show(key: PresentedKey) {
        val time = now()
        val current = live(time)
        val last = current.lastOrNull()
        lines = if (last != null && last.key == key && time - last.at <= REPEAT_MS) {
            current.dropLast(1) + last.copy(count = last.count + 1, at = time)
        } else {
            (current + Line(nextId++, key, 1, time)).takeLast(MAX_LINES)
        }
    }

    /** Takes off the lines whose time is over. */
    fun expire() {
        val current = live(now())
        if (current.size != lines.size) lines = current
    }

    fun clear() {
        lines = emptyList()
    }

    private fun live(time: Long) = lines.filter { time - it.at < SHOWN_MS }

    companion object {
        const val MAX_LINES = 3
        const val SHOWN_MS = 2_500L
        const val REPEAT_MS = 1_000L
    }
}
