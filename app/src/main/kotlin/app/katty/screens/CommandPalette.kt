package app.katty.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.katty.core.PaletteEntry
import app.katty.core.PaletteGroup
import app.katty.core.PaletteMatch
import app.katty.core.searchPalette
import app.katty.ui.Icon
import app.katty.ui.Katty
import app.katty.ui.hoverHighlight
import app.katty.ui.KattyIcons
import app.katty.ui.KeyCap
import app.katty.ui.OneLine
import app.katty.ui.SectionLabel
import app.katty.ui.Shortcut
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField

/** The label of an entry with the letters that the query matched in the accent color and bold. */
internal fun highlighted(label: String, indices: List<Int>, accent: Color): AnnotatedString = buildAnnotatedString {
    val marked = indices.toSet()
    for ((i, c) in label.withIndex()) {
        if (i in marked) withStyle(SpanStyle(color = accent, fontWeight = FontWeight.SemiBold)) { append(c) } else append(c)
    }
}

/** The field that opens the palette, in the title bar: its text says what it does, and the shortcut is next to it. */
@Composable
fun PaletteButton(compact: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = Katty.colors
    Row(
        modifier.width(if (compact) 140.dp else 280.dp).height(28.dp).clip(RoundedCornerShape(6.dp)).background(colors.panel).border(1.dp, colors.line, RoundedCornerShape(6.dp))
            .semantics { contentDescription = "Open command palette" }
            // It looks like a text field, and the pointer says so, although a click opens the palette, which is where the text is typed.
            .pointerHoverIcon(PointerIcon.Text)
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(KattyIcons.Search, colors.muted, size = 14.dp)
        Text(if (compact) "Commands" else "Search or run a command…", Modifier.weight(1f), color = colors.muted, maxLines = 1)
        Shortcut("⌘ K")
    }
}

/**
 * The command palette: a field, and under it the workspaces, the terminals and the commands that match, with the letters that matched in
 * the accent color. The arrows move, Enter does what is selected and Escape closes it.
 *
 * @param entries Everything that can be gone to or done.
 * @param onRun Called with the entry that was chosen.
 */
@Composable
fun CommandPalette(entries: List<PaletteEntry>, onRun: (PaletteEntry) -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val colors = Katty.colors
    val query = rememberTextFieldState()
    var text by remember { mutableStateOf("") }
    LaunchedEffect(query) { snapshotFlow { query.text.toString() }.collect { text = it } }
    val matches = remember(entries, text) { searchPalette(entries, text) }
    var selected by remember { mutableStateOf(0) }
    // A new query starts from the top again.
    LaunchedEffect(text) { selected = 0 }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val listState = rememberLazyListState()
    val run: (PaletteMatch) -> Unit = { match -> onRun(match.entry) }

    Box(modifier.fillMaxSize().background(Color(0xFF0E0F12).copy(alpha = .3f)).clickable(interactionSource = null, indication = null, onClick = onDismiss), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.padding(top = 90.dp).widthIn(max = 640.dp).fillMaxWidth().shadow(24.dp, RoundedCornerShape(12.dp)).clip(RoundedCornerShape(12.dp)).background(colors.panel)
                .border(1.dp, colors.line, RoundedCornerShape(12.dp)).clickable(interactionSource = null, indication = null) {}
                .semantics { contentDescription = "Command palette" },
        ) {
            Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(KattyIcons.Search, colors.muted, size = 18.dp)
                TextField(
                    query,
                    Modifier.weight(1f).focusRequester(focus).onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (event.key) {
                            Key.DirectionDown -> { if (matches.isNotEmpty()) selected = (selected + 1) % matches.size; true }
                            Key.DirectionUp -> { if (matches.isNotEmpty()) selected = (selected - 1 + matches.size) % matches.size; true }
                            Key.Enter, Key.NumPadEnter -> { matches.getOrNull(selected)?.let(run); true }
                            Key.Escape -> { onDismiss(); true }
                            else -> false
                        }
                    },
                    placeholder = { Text("Search or run a command…") },
                )
                KeyCap("esc")
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(colors.line))
            LazyColumn(Modifier.heightIn(max = 420.dp).padding(horizontal = 6.dp, vertical = 4.dp), state = listState) {
                if (matches.isEmpty()) item { Text("No results.", Modifier.padding(12.dp), color = colors.muted) }
                itemsIndexed(matches, key = { _, match -> match.entry.id }) { index, match ->
                    if (index == 0 || matches[index - 1].entry.group != match.entry.group) {
                        SectionLabel(match.entry.group.title, Modifier.padding(start = 12.dp, top = 10.dp, bottom = 4.dp))
                    }
                    PaletteRow(match, selected == index) { run(match) }
                }
            }
            Row(Modifier.fillMaxWidth().height(38.dp).background(colors.side).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                FooterHint(listOf("↑", "↓"), "Navigate")
                FooterHint(listOf("⏎"), "Open")
                Box(Modifier.weight(1f))
                FooterHint(listOf("⌘", "K"), "Toggle palette")
            }
        }
    }
    // The selected row stays in view as the arrows move it.
    LaunchedEffect(selected, matches) {
        if (selected > 0 && matches.isNotEmpty()) listState.animateScrollToItem(selected.coerceAtMost(listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0))
    }
}

@Composable
private fun FooterHint(keys: List<String>, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        keys.forEach { KeyCap(it) }
        Text(label, color = Katty.colors.muted, fontSize = 12.sp)
    }
}

@Composable
private fun PaletteRow(match: PaletteMatch, selected: Boolean, onClick: () -> Unit) {
    val colors = Katty.colors
    val entry = match.entry
    Row(
        Modifier.fillMaxWidth().height(36.dp).clip(RoundedCornerShape(6.dp)).hoverHighlight(enabled = !selected).background(if (selected) colors.sel else Color.Transparent)
            .semantics { contentDescription = "${entry.label} ${entry.context}".trim(); this.selected = selected }
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            when (entry.group) {
                PaletteGroup.WORKSPACES -> KattyIcons.Folder
                PaletteGroup.TERMINALS -> KattyIcons.Prompt
                PaletteGroup.COMMANDS -> KattyIcons.ArrowRight
            },
            colors.muted, size = 16.dp,
        )
        Text(highlighted(entry.label, match.indices, colors.accent), Modifier.weight(1f, fill = false), color = colors.text, maxLines = 1)
        if (entry.context.isNotEmpty()) OneLine(entry.context, Modifier.weight(1f), color = colors.muted, fontSize = 12.sp)
        entry.shortcut?.let { Shortcut(shortcutKeys(it)) }
    }
}

/** A shortcut written as the menus write it, `⌘⇧J` or `F2`, as the keys for [Shortcut]: each modifier is a key, and what follows is one. */
internal fun shortcutKeys(shortcut: String): String {
    val modifiers = shortcut.takeWhile { it in "⌘⌥⇧⌃" }
    return (modifiers.map { it.toString() } + shortcut.drop(modifiers.length)).filter { it.isNotEmpty() }.joinToString(" ")
}
