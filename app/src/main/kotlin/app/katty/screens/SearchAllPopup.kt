package app.katty.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.katty.AppShortcut
import app.katty.core.LineMatch
import app.katty.core.TerminalId
import app.katty.terminals.AllTerminalsResults
import app.katty.terminals.TerminalMatches
import app.katty.ui.Icon
import app.katty.ui.Katty
import app.katty.ui.KattyIcons
import app.katty.ui.KeyCap
import app.katty.ui.ListKey
import app.katty.ui.OneLine
import app.katty.ui.hoverHighlight
import app.katty.ui.listKey
import app.katty.ui.stepped
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField

/** What the popup says of a terminal whose rows it lists: its title, and where it is (`project / workspace`). */
data class SearchedTerminal(val title: String, val place: String)

/** How long a query that is being typed waits before it is searched: not at each letter. */
internal const val SEARCH_ALL_DELAY_MS = 150L

// The rows of the list: a terminal, then its rows where the text is.
private sealed interface SearchItem {
    val key: String
}

private data class TerminalItem(val matches: TerminalMatches) : SearchItem {
    override val key: String get() = "terminal:${matches.terminal.value}"
}

private data class MatchItem(val terminal: TerminalId, val match: LineMatch) : SearchItem {
    override val key: String get() = "row:${terminal.value}:${match.row}"
}

/** What the popup says of the search: how many rows in how many terminals, a `+` when there are more than it kept, and whether it goes on. */
internal fun AllTerminalsResults.summary(): String {
    if (query.isEmpty()) return ""
    if (terminals.isEmpty()) return if (searching) "Searching…" else "No results"
    val more = if (capped || terminals.any { it.more }) "+" else ""
    val rows = "$total$more ${if (total == 1 && more.isEmpty()) "result" else "results"}"
    val where = "in ${terminals.size} ${if (terminals.size == 1) "terminal" else "terminals"}"
    return "$rows $where" + if (searching) " · Searching…" else ""
}

/**
 * What is shown of a row where the text was found: the row, with the text in [accent] and bold. A row whose first match is far from its start
 * begins a little before it, after an ellipsis, so that the match is seen; its end is cut where it is drawn.
 */
internal fun excerpt(match: LineMatch, accent: Color, before: Int = 24): AnnotatedString {
    val first = match.ranges.firstOrNull()?.first ?: 0
    val start = if (first > before + 8) first - before else 0
    val prefix = if (start > 0) "…" else ""
    return buildAnnotatedString {
        append(prefix)
        var at = start
        for (range in match.ranges) {
            if (range.last < start) continue
            val from = maxOf(range.first, start)
            append(match.line.substring(at, from))
            withStyle(SpanStyle(color = accent, fontWeight = FontWeight.Bold)) { append(match.line.substring(from, range.last + 1)) }
            at = range.last + 1
        }
        append(match.line.substring(at))
    }
}

/**
 * Searches the history of every terminal for a text: a field, and under it the rows where it is, under their terminal, the last rows of each
 * first. The arrows move, Enter or a click goes to the row ([onOpen], with the query, which the find bar of the terminal is given), Escape or a
 * click outside closes it. The query is searched once it has not changed for [SEARCH_ALL_DELAY_MS], and a new one stops the one that runs.
 *
 * @param search What is found of a query, as it is found.
 * @param terminalOf What a terminal is called and where it is; `null` for one that is gone, which is not listed.
 * @param initialQuery What the field has when it opens, and [initialResults] what is shown until its search gives something.
 */
@Composable
fun SearchAllPopup(
    search: (String) -> Flow<AllTerminalsResults>,
    terminalOf: (TerminalId) -> SearchedTerminal?,
    onOpen: (terminal: TerminalId, row: Long, query: String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    initialQuery: String = "",
    initialResults: AllTerminalsResults = AllTerminalsResults(),
) {
    val colors = Katty.colors
    val query = rememberTextFieldState(initialQuery)
    var results by remember { mutableStateOf(initialResults) }
    LaunchedEffect(search) {
        snapshotFlow { query.text.toString() }.collectLatest { text ->
            if (text.isNotEmpty()) delay(SEARCH_ALL_DELAY_MS)
            search(text).collect { results = it }
        }
    }
    // A terminal that is gone meanwhile is not listed, nor counted.
    val listed = remember(results) { results.copy(terminals = results.terminals.filter { terminalOf(it.terminal) != null }) }
    val items = remember(listed) { listed.terminals.flatMap { found -> listOf(TerminalItem(found)) + found.matches.map { MatchItem(found.terminal, it) } } }
    val rows = remember(items) { items.filterIsInstance<MatchItem>() }
    // The row that is selected, by its key: the list grows as the terminals are searched, and what is highlighted is what Enter opens.
    var selectedKey by remember { mutableStateOf<String?>(null) }
    val selected = rows.indexOfFirst { it.key == selectedKey }.takeIf { it >= 0 } ?: 0
    val select = { index: Int -> selectedKey = rows.getOrNull(index)?.key }
    LaunchedEffect(results.query) { selectedKey = null }
    val open = { row: MatchItem -> onOpen(row.terminal, row.match.row, results.query) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val listState = rememberLazyListState()

    // The keys are the frame's, wherever the focus is in it: a row that was clicked may have it.
    val keys = Modifier.onPreviewKeyEvent { event ->
        when (event.listKey()) {
            ListKey.DOWN -> { select(selected.stepped(1, rows.size)); true }
            ListKey.UP -> { select(selected.stepped(-1, rows.size)); true }
            ListKey.ENTER -> { rows.getOrNull(selected)?.let(open); true }
            ListKey.ESCAPE -> { onDismiss(); true }
            null -> false
        }
    }
    PaletteFrame("Search in all terminals", onDismiss, modifier.then(keys), maxWidth = 720.dp) {
        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(KattyIcons.Search, colors.muted, size = 18.dp)
            TextField(query, Modifier.weight(1f).focusRequester(focus), placeholder = { Text("Search in all terminals") })
            Text(listed.summary(), color = colors.muted, fontSize = 12.sp, maxLines = 1)
            KeyCap("esc")
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.line))
        LazyColumn(Modifier.heightIn(max = 460.dp).padding(horizontal = 6.dp, vertical = 4.dp), state = listState) {
            if (results.query.isEmpty()) {
                item { Text("Type a text to find it in the history of every terminal.", Modifier.padding(12.dp), color = colors.muted) }
            } else if (items.isEmpty() && !listed.searching) {
                item { Text("No results.", Modifier.padding(12.dp), color = colors.muted) }
            }
            items(items, key = { it.key }) { item ->
                when (item) {
                    is TerminalItem -> TerminalHeader(item.matches, terminalOf(item.matches.terminal) ?: return@items)
                    is MatchItem -> MatchRow(item.match, selected = rows.getOrNull(selected) == item) { open(item) }
                }
            }
        }
        Row(Modifier.fillMaxWidth().heightIn(min = 38.dp).background(colors.side).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            FooterHint(listOf("↑", "↓"), "Navigate")
            FooterHint(listOf("⏎"), "Go to the line")
            Box(Modifier.weight(1f))
            AppShortcut.SEARCH_ALL.label.takeIf { it.isNotEmpty() }?.let { FooterHint(shortcutKeys(it).split(' '), "Toggle search") }
        }
    }
    // The selected row stays in view as the arrows move it.
    LaunchedEffect(selected, items) {
        val index = rows.getOrNull(selected)?.let(items::indexOf) ?: return@LaunchedEffect
        if (selected > 0) listState.animateScrollToItem(index)
    }
}

// A terminal of the list: its title, where it is, and how many rows of it are listed.
@Composable
private fun TerminalHeader(matches: TerminalMatches, terminal: SearchedTerminal) {
    val colors = Katty.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 26.dp).padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(KattyIcons.Prompt, colors.muted, size = 14.dp)
        Text(terminal.title, color = colors.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        OneLine(terminal.place, Modifier.weight(1f), color = colors.muted, fontSize = 12.sp)
        Text("${matches.matches.size}${if (matches.more) "+" else ""}", color = colors.muted, fontSize = 12.sp)
    }
}

@Composable
private fun MatchRow(match: LineMatch, selected: Boolean, onClick: () -> Unit) {
    val colors = Katty.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 28.dp).clip(RoundedCornerShape(6.dp)).hoverHighlight(enabled = !selected).background(if (selected) colors.sel else Color.Transparent)
            .semantics { contentDescription = match.line; this.selected = selected }
            .clickable(role = Role.Button, onClick = onClick).padding(start = 34.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(excerpt(match, colors.accent), color = colors.text, fontSize = 12.sp, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
