package app.katty.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.katty.AppShortcut
import app.katty.BindingCheck
import app.katty.KeyBinding
import app.katty.ShortcutGroup
import app.katty.ShortcutMap
import app.katty.TerminalKeys
import app.katty.ui.DialogScrim
import app.katty.ui.Katty
import app.katty.ui.KattyIconButton
import app.katty.ui.KattyIcons
import app.katty.ui.KattyTextButton
import app.katty.ui.ListKey
import app.katty.ui.OneLine
import app.katty.ui.SectionLabel
import app.katty.ui.Shortcut
import app.katty.ui.listKey
import app.katty.ui.stepped
import app.katty.ui.uiScaleFactor
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField
import java.awt.event.KeyEvent as Awt

/** A line of the list of the dialog: a group, a shortcut that can be changed, or a key that is fixed. */
internal sealed interface ShortcutRow {
    data class Header(val title: String) : ShortcutRow

    data class Item(val shortcut: AppShortcut) : ShortcutRow

    data class Fixed(val title: String, val keys: String) : ShortcutRow
}

/** The keys that the terminal and the screens handle themselves: they are listed, and they cannot be changed. */
internal val FIXED_KEYS: List<ShortcutRow.Fixed> = listOf(
    ShortcutRow.Fixed("Copy, paste and select all in a terminal", "${TerminalKeys.COPY} ${TerminalKeys.PASTE} ${TerminalKeys.SELECT_ALL}"),
    ShortcutRow.Fixed("Jump to the previous or the next prompt", "${TerminalKeys.PREVIOUS_PROMPT} ${TerminalKeys.NEXT_PROMPT}"),
    ShortcutRow.Fixed("Scroll the history by a page", "${TerminalKeys.SCROLL_PAGE_UP} ${TerminalKeys.SCROLL_PAGE_DOWN}"),
    ShortcutRow.Fixed("Scroll the history to its ends", "${TerminalKeys.SCROLL_TO_TOP} ${TerminalKeys.SCROLL_TO_BOTTOM}"),
    ShortcutRow.Fixed("Edit the line of the shell: by word, to its ends, delete to its start", "⌥← ⌥→ ⌘← ⌘→ ⌘⌫"),
    ShortcutRow.Fixed("Rename the project or the workspace that has the focus in the sidebar or the tabs", "F2"),
    ShortcutRow.Fixed("On the board: move from card to card, go to its terminal", "↑ ↓ ← → ↩"),
    ShortcutRow.Fixed("In a list or a dialog: choose, confirm, cancel", "↑ ↓ ↩ ⎋"),
)

/**
 * The lines of the dialog for [query]: the shortcuts of each group whose title, keys or group the query is in, each group under its title, and
 * then the fixed keys that it is in (all of them for no query). Case does not matter.
 */
internal fun shortcutRows(map: ShortcutMap, query: String): List<ShortcutRow> {
    val needle = query.trim().lowercase()
    fun matches(vararg texts: String) = needle.isEmpty() || texts.any { needle in it.lowercase() }
    val rows = ArrayList<ShortcutRow>()
    for (group in ShortcutGroup.entries) {
        val shortcuts = AppShortcut.entries.filter { it.group == group && matches(it.title, map.label(it), group.title) }
        if (shortcuts.isEmpty()) continue
        rows += ShortcutRow.Header(group.title)
        rows += shortcuts.map { ShortcutRow.Item(it) }
    }
    val fixed = FIXED_KEYS.filter { matches(it.title, it.keys) }
    if (fixed.isNotEmpty()) {
        rows += ShortcutRow.Header("Fixed keys")
        rows += fixed
    }
    return rows
}

/** What a key that is pressed while a shortcut is being changed does. */
internal sealed interface RecordingResult {
    /** Nothing: a modifier that is the start of a combination. */
    data object Wait : RecordingResult

    /** The change is given up. */
    data object Cancel : RecordingResult

    /** The shortcuts are now these. */
    data class Done(val map: ShortcutMap) : RecordingResult

    /** The keys are not accepted, or need a second press: [message] says why. [pending] is what a second press of the same keys confirms. */
    data class Refused(val message: String, val pending: KeyBinding? = null) : RecordingResult
}

/**
 * What [pressed] does while [shortcut] is being changed in [map]: Escape alone gives up, Backspace or Delete alone unbinds it, a modifier alone waits,
 * and anything else is the new keys when [ShortcutMap.check] accepts them. Keys that another shortcut has are not taken at once: they are the [pending]
 * of the answer, and pressing the same keys again takes them (the other shortcut is unbound).
 */
internal fun record(map: ShortcutMap, shortcut: AppShortcut, pending: KeyBinding?, pressed: KeyBinding): RecordingResult {
    val code = pressed.keyCode
    if (KeyBinding.isModifier(code)) return RecordingResult.Wait
    val alone = !pressed.meta && !pressed.ctrl && !pressed.alt && !pressed.shift
    if (alone && code == Awt.VK_ESCAPE) return RecordingResult.Cancel
    if (alone && (code == Awt.VK_BACK_SPACE || code == Awt.VK_DELETE)) return RecordingResult.Done(map.with(shortcut, null))
    return when (val check = map.check(shortcut, pressed)) {
        BindingCheck.Ok -> RecordingResult.Done(map.with(shortcut, pressed))
        BindingCheck.NeedsCommand -> RecordingResult.Refused("A shortcut has to include ⌘, so that the programs in your terminals never see it.")
        is BindingCheck.Reserved -> RecordingResult.Refused(check.reason)
        is BindingCheck.Taken ->
            if (pressed == pending) {
                RecordingResult.Done(map.with(check.by, null).with(shortcut, pressed))
            } else {
                val keys = if (shortcut.takesDigit) pressed.modifiersText + "1…9" else pressed.label
                RecordingResult.Refused("$keys is used by “${check.by.title}”. Press it again to take it, or Esc to cancel.", pending = pressed)
            }
    }
}

private fun KeyEvent.binding() = KeyBinding(key.nativeKeyCode, isMetaPressed, isCtrlPressed, isAltPressed, isShiftPressed)

/**
 * The dialog where the shortcuts of the window are changed: every one with its keys, grouped, and under them the keys that are fixed. Each change
 * applies at once, and the dialog stays open until it is closed.
 *
 * It is driven from the keyboard: the arrows choose a shortcut, Enter changes it (then the next keys are the new ones), ⌘⌫ puts it back to its
 * default and Escape closes. While one is being changed every key goes to the dialog: Escape gives up, Backspace unbinds, and a combination that
 * another shortcut has asks for a second press.
 *
 * @param map What the shortcuts are bound to now.
 * @param onChange Called with the shortcuts as they become.
 */
@Composable
internal fun ShortcutsDialog(map: ShortcutMap, onChange: (ShortcutMap) -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val colors = Katty.colors
    val query = rememberTextFieldState()
    var text by remember { mutableStateOf("") }
    LaunchedEffect(query) { snapshotFlow { query.text.toString() }.collect { text = it } }
    val rows = remember(map, text) { shortcutRows(map, text) }
    val shortcuts = remember(rows) { rows.filterIsInstance<ShortcutRow.Item>().map { it.shortcut } }
    var chosen by remember { mutableStateOf<AppShortcut?>(null) }
    // A new query starts from the top again; a choice that the query hides is the first that is left.
    LaunchedEffect(text) { chosen = null }
    val selected = chosen?.takeIf { it in shortcuts } ?: shortcuts.firstOrNull()
    var editing by remember { mutableStateOf<AppShortcut?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<KeyBinding?>(null) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val listState = rememberLazyListState()
    // The chosen line stays in view: when the list has been measured (before that nothing is visible, and the first line would be scrolled to the top, over
    // its group), and with its group's title when it is the first of it.
    LaunchedEffect(selected, rows) {
        val at = rows.indexOfFirst { it is ShortcutRow.Item && it.shortcut == selected }
        val visible = listState.layoutInfo.visibleItemsInfo
        if (at >= 0 && visible.isNotEmpty() && visible.none { it.index == at }) {
            listState.animateScrollToItem(if (rows.getOrNull(at - 1) is ShortcutRow.Header) at - 1 else at)
        }
    }
    val startEditing: (AppShortcut) -> Unit = { shortcut ->
        chosen = shortcut
        editing = shortcut
        message = null
        pending = null
        // The keys go to the dialog wherever the focus is, but a click took it to a button.
        runCatching { focus.requestFocus() }
    }

    DialogScrim(
        modifier.onPreviewKeyEvent { event ->
            val changing = editing
            if (changing != null) {
                // Everything goes to the shortcut that is being changed, the characters that are typed too.
                if (event.type == KeyEventType.KeyDown) {
                    when (val result = record(map, changing, pending, event.binding())) {
                        RecordingResult.Wait -> Unit
                        RecordingResult.Cancel -> { editing = null; message = null; pending = null }
                        is RecordingResult.Done -> { onChange(result.map); editing = null; message = null; pending = null }
                        is RecordingResult.Refused -> { message = result.message; pending = result.pending }
                    }
                }
                return@onPreviewKeyEvent true
            }
            when (event.listKey()) {
                ListKey.DOWN -> { chosen = shortcuts.getOrNull(shortcuts.indexOf(selected).stepped(1, shortcuts.size)); true }
                ListKey.UP -> { chosen = shortcuts.getOrNull(shortcuts.indexOf(selected).stepped(-1, shortcuts.size)); true }
                ListKey.ENTER -> { selected?.let(startEditing); true }
                ListKey.ESCAPE -> { onClose(); true }
                null -> if (event.type == KeyEventType.KeyDown && event.isMetaPressed && event.key.nativeKeyCode == Awt.VK_BACK_SPACE) {
                    selected?.let { onChange(map.reset(it)) }
                    true
                } else {
                    false
                }
            }
        },
        width = 820.dp * uiScaleFactor(),
    ) {
        Column(Modifier.height(640.dp).semantics { contentDescription = "Keyboard shortcuts" }) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Keyboard shortcuts", Modifier.weight(1f), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = colors.text)
                KattyTextButton("Reset all", { onChange(map.resetAll()) }, description = "Reset all shortcuts", enabled = map.overrides.isNotEmpty())
                KattyIconButton(KattyIcons.Close, "Close", onClose, shortcut = "Esc")
            }
            TextField(
                query,
                Modifier.padding(horizontal = 16.dp).fillMaxWidth().focusRequester(focus).semantics { contentDescription = "Search shortcuts" },
                placeholder = { Text("Search by action or keys") },
            )
            Box(Modifier.padding(top = 8.dp).fillMaxWidth().height(1.dp).background(colors.line))
            LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), state = listState) {
                if (rows.isEmpty()) item { Text("No shortcut matches your search.", Modifier.padding(12.dp), color = colors.muted) }
                items(rows) { row ->
                    when (row) {
                        is ShortcutRow.Header -> SectionLabel(row.title, Modifier.padding(start = 12.dp, top = 12.dp, bottom = 4.dp))
                        is ShortcutRow.Item -> ShortcutLine(
                            shortcut = row.shortcut,
                            map = map,
                            selected = row.shortcut == selected,
                            editing = editing == row.shortcut,
                            message = message.takeIf { editing == row.shortcut },
                            onSelect = { chosen = row.shortcut },
                            onEdit = { startEditing(row.shortcut) },
                            onReset = { onChange(map.reset(row.shortcut)) },
                        )
                        is ShortcutRow.Fixed -> FixedLine(row)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().heightIn(min = 38.dp).background(colors.side).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Hint(listOf("↑", "↓"), "Choose")
                Hint(listOf("↩"), "Change")
                Hint(listOf("⌘", "⌫"), "Back to the default")
                Box(Modifier.weight(1f))
                Hint(listOf("⎋"), "Close")
            }
        }
    }
}

@Composable
private fun Hint(keys: List<String>, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        keys.forEach { app.katty.ui.KeyCap(it) }
        Text(label, color = Katty.colors.muted, fontSize = 12.sp, maxLines = 1)
    }
}

@Composable
private fun ShortcutLine(
    shortcut: AppShortcut,
    map: ShortcutMap,
    selected: Boolean,
    editing: Boolean,
    message: String?,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onReset: () -> Unit,
) {
    val colors = Katty.colors
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(if (selected) colors.sel else androidx.compose.ui.graphics.Color.Transparent)
            .semantics { contentDescription = shortcut.title; this.selected = selected }
            .clickable(role = Role.Button, onClick = onSelect)
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Row(Modifier.fillMaxWidth().heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OneLine(shortcut.title, Modifier.weight(1f), color = colors.text)
            when {
                editing -> Text("Press the new keys…", color = colors.accent, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                map.label(shortcut).isEmpty() -> Text("—", Modifier.semantics { contentDescription = "No shortcut" }, color = colors.muted)
                else -> Shortcut(app.katty.screens.shortcutKeys(map.label(shortcut)))
            }
            KattyTextButton("Change", onEdit, description = "Change ${shortcut.title}")
            // A place is kept for the button, so that the keys stay where they are when it comes and goes.
            Box(Modifier.width(64.dp * uiScaleFactor())) {
                if (!map.isDefault(shortcut)) {
                    KattyTextButton("Reset", onReset, description = "Reset ${shortcut.title}")
                }
            }
        }
        if (editing) {
            Text(message ?: "Esc gives up, ⌫ removes the shortcut.", Modifier.padding(bottom = 6.dp), color = if (message != null) colors.err else colors.muted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun FixedLine(row: ShortcutRow.Fixed) {
    val colors = Katty.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 32.dp).padding(horizontal = 12.dp).semantics { contentDescription = "Fixed: ${row.title}" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        OneLine(row.title, Modifier.weight(1f), color = colors.muted)
        Shortcut(row.keys)
    }
}
