package app.katty.screens

import app.katty.ui.uiScaleFactor
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusable
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import app.katty.AppShortcut
import app.katty.core.AgentState
import app.katty.core.Attention
import app.katty.core.AttentionItem
import app.katty.core.FinishedCommand
import app.katty.core.TerminalId
import app.katty.core.relativeTime
import app.katty.ui.AgentTile
import app.katty.ui.BelowEnd
import app.katty.ui.Icon
import app.katty.ui.Katty
import app.katty.ui.hoverHighlight
import app.katty.ui.KattyIcons
import app.katty.ui.ListKey
import app.katty.ui.listKey
import app.katty.ui.stepped
import app.katty.ui.OneLine
import app.katty.ui.SectionLabel
import app.katty.ui.Shortcut
import app.katty.ui.StateGlyph
import app.katty.ui.TileSize
import app.katty.ui.glyph
import app.katty.terminal.TerminalCommandOutcome
import app.katty.terminal.compose.formatCommandDuration
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text

/** What the command that goes to the next terminal that needs the user, one that waits or one that finished, is called. */
internal const val JUMP_LABEL = "Jump to next terminal that needs you"

/** What the bell and its list are called: they list agents and commands that ended. */
internal const val ATTENTION_LABEL = "Terminals needing attention"

/**
 * What an item of attention is called: the agent's name, or what the program calls itself, or its terminal's name; for a command that ended, its
 * line, or its terminal's name when it is not known.
 */
internal fun AttentionItem.title(): String {
    command?.let { return it.line ?: terminal.name ?: "Command" }
    return info?.report?.agent?.displayName ?: info?.report?.app ?: terminal.name ?: "Terminal"
}

/** How a command that ended went, and how long it ran: `Exit 2 · 3 min 12 s`, `Succeeded · 42 s`. */
internal fun FinishedCommand.summary(): String {
    val how = when (outcome) {
        TerminalCommandOutcome.SUCCEEDED -> "Succeeded"
        TerminalCommandOutcome.FAILED -> "Exit $exitCode"
        TerminalCommandOutcome.STOPPED -> "Stopped"
        TerminalCommandOutcome.ENDED, TerminalCommandOutcome.RUNNING -> "Ended"
    }
    return "$how · ${formatCommandDuration(java.time.Duration.ofMillis(durationMs))}"
}

/** The line under an item of attention: what its agent says, or how its command went. */
internal fun AttentionItem.note(): String? = command?.summary() ?: info?.report?.note

/**
 * What a terminal that needs the user, or that finished, says it is doing in the words of a notification. For a command, what happened comes
 * first: its line may be long, and is cut where it is drawn.
 */
internal fun AttentionItem.headline(): String {
    command?.let { command ->
        val how = when (command.outcome) {
            TerminalCommandOutcome.FAILED -> "Failed"
            TerminalCommandOutcome.STOPPED -> "Stopped"
            else -> "Finished"
        }
        return "$how: ${title()}"
    }
    return when (state) {
        AgentState.NEEDS_INPUT -> "${title()} needs input"
        AgentState.ERROR -> "${title()} failed"
        AgentState.DONE -> "${title()} finished"
        else -> title()
    }
}

/**
 * The bell of the title bar, with the number of the terminals that need the user ([count]: the agents that wait, and the agents and the commands
 * that finished): amber while one waits ([waiting]), green when they only finished.
 */
@Composable
fun AttentionBell(count: Int, onClick: () -> Unit, modifier: Modifier = Modifier, waiting: Boolean = true) {
    val colors = Katty.colors
    Box(modifier) {
        app.katty.ui.KattyIconButton(
            KattyIcons.Bell,
            if (count == 0) ATTENTION_LABEL else "$ATTENTION_LABEL: $count",
            onClick,
            shortcut = AppShortcut.ATTENTION.label,
        )
        if (count > 0) {
            Box(
                Modifier.align(Alignment.TopEnd).padding(top = 2.dp, end = 0.dp).heightIn(min = 14.dp).defaultMinSize(minWidth = 14.dp).clip(RoundedCornerShape(7.dp))
                    .background(if (waiting) colors.input else colors.done).padding(horizontal = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(count.toString(), color = androidx.compose.ui.graphics.Color.White, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/**
 * The list of attention that the bell opens: the agents that need the user, then the agents and the commands that finished, with where they are
 * and what they said, how long ago, a button to take them all off, and the shortcut to go to the next one.
 *
 * It has the keyboard when it opens: the arrows move the selected row, Enter goes to its terminal and Escape closes the list.
 *
 * @param now The time that ages are told from.
 */
@Composable
fun AttentionPopover(
    attention: Attention,
    now: Long,
    onOpen: (TerminalId) -> Unit,
    onMarkAllSeen: () -> Unit,
    onJumpToNext: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = Katty.colors
    // Every row, the ones that need the user first: what the arrows go through.
    val items = attention.needsYou + attention.finished
    var selected by remember { mutableIntStateOf(0) }
    LaunchedEffect(items.size) { selected = selected.coerceIn(0, (items.size - 1).coerceAtLeast(0)) }
    val focus = remember { FocusRequester() }
    val listState = rememberLazyListState()
    // The selected row stays in view: its place among the lazy items is after the line that says nobody needs the user, and the label of the finished.
    LaunchedEffect(selected) {
        val at = (if (attention.needsYou.isEmpty()) 1 else 0) + selected + (if (selected >= attention.needsYou.size && attention.finished.isNotEmpty()) 1 else 0)
        // Once the list has been measured: before that nothing is visible, and the first row would be scrolled to the top over what is above it.
        val visible = listState.layoutInfo.visibleItemsInfo
        if (visible.isNotEmpty() && visible.none { it.index == at }) listState.animateScrollToItem(at)
    }
    Popup(popupPositionProvider = BelowEnd, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        // Here and not outside: the content of a popup is composed after the one that holds it, and has the node that takes the focus.
        LaunchedEffect(Unit) { focus.requestFocus() }
        Column(
            Modifier.width(380.dp * uiScaleFactor()).shadow(24.dp, RoundedCornerShape(10.dp)).clip(RoundedCornerShape(10.dp)).background(colors.panel)
                .border(1.dp, colors.line, RoundedCornerShape(10.dp)).semantics { contentDescription = ATTENTION_LABEL }
                .focusRequester(focus).focusable()
                // Not a preview: the button in the header handles its own Enter first.
                .onKeyEvent { event ->
                    when (event.listKey()) {
                        ListKey.DOWN -> { selected = selected.stepped(1, items.size); true }
                        ListKey.UP -> { selected = selected.stepped(-1, items.size); true }
                        ListKey.ENTER -> { items.getOrNull(selected)?.let { onOpen(it.terminal.id) }; true }
                        ListKey.ESCAPE -> { onDismiss(); true }
                        null -> false
                    }
                },
        ) {
            Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 10.dp, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionLabel("NEEDS YOU")
                // The ones that wait: the ones that finished have their own section.
                if (attention.needsYou.isNotEmpty()) {
                    Box(Modifier.heightIn(min = 18.dp).clip(RoundedCornerShape(9.dp)).background(colors.input).padding(horizontal = 6.dp), contentAlignment = Alignment.Center) {
                        Text(attention.needsYou.size.toString(), color = androidx.compose.ui.graphics.Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
                Spacer(Modifier.weight(1f))
                OutlinedButton(onClick = onMarkAllSeen) { Text("Mark all seen", fontSize = 12.sp) }
            }
            LazyColumn(Modifier.heightIn(max = 460.dp).padding(horizontal = 6.dp), state = listState) {
                if (attention.needsYou.isEmpty()) item { Text("Nothing needs you.", Modifier.padding(12.dp), color = colors.muted) }
                itemsIndexed(attention.needsYou) { index, item -> AttentionRow(item, now, selected == index) { onOpen(item.terminal.id) } }
                if (attention.finished.isNotEmpty()) {
                    item { SectionLabel("FINISHED", Modifier.padding(start = 8.dp, top = 12.dp, bottom = 4.dp)) }
                    itemsIndexed(attention.finished) { index, item ->
                        AttentionRow(item, now, selected == attention.needsYou.size + index) { onOpen(item.terminal.id) }
                    }
                }
            }
            Row(
                Modifier.padding(top = 6.dp).fillMaxWidth().background(colors.side).clickable(role = Role.Button, onClick = onJumpToNext).padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(JUMP_LABEL, Modifier.weight(1f), color = colors.muted, fontSize = 12.sp)
                AppShortcut.JUMP_TO_WAITING.label.takeIf { it.isNotEmpty() }?.let { Shortcut(shortcutKeys(it)) }
            }
        }
    }
}

@Composable
private fun AttentionRow(item: AttentionItem, now: Long, selected: Boolean, onClick: () -> Unit) {
    val colors = Katty.colors
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).hoverHighlight(enabled = !selected).background(if (selected) colors.sel else androidx.compose.ui.graphics.Color.Transparent)
            .semantics { contentDescription = "${item.title()} in ${item.project.name} / ${item.workspace.name}"; this.selected = selected }
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        StateGlyph(item.state, Modifier.padding(top = 3.dp))
        AgentTile(item.info?.report?.agent.glyph(), size = TileSize.HEADER)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OneLine(item.title(), Modifier.weight(1f), color = colors.text)
                Text(relativeTime(now, item.since), color = colors.muted, fontSize = 11.sp)
            }
            OneLine("${item.project.name} / ${item.workspace.name}", color = colors.muted, fontSize = 12.sp)
            item.note()?.let { Text(it, color = colors.text, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
    }
}

/**
 * A notice in a corner of the window for an agent that started needing the user, finished or failed, or a command that ended, in a terminal that
 * the user is not looking at. A click goes to the terminal.
 */
@Composable
fun AgentToast(item: AttentionItem, now: Long, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = Katty.colors
    Row(
        modifier.width(340.dp * uiScaleFactor()).shadow(24.dp, RoundedCornerShape(14.dp)).clip(RoundedCornerShape(14.dp)).background(colors.panel).border(1.dp, colors.line, RoundedCornerShape(14.dp))
            .semantics { contentDescription = item.headline() }
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(9.dp)).background(colors.accent), contentAlignment = Alignment.Center) {
            Icon(KattyIcons.Prompt, androidx.compose.ui.graphics.Color.White, size = 20.dp)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OneLine(item.headline(), Modifier.weight(1f), color = colors.text)
                Text(relativeTime(now, item.since), color = colors.muted, fontSize = 11.sp)
            }
            OneLine("${item.project.name} / ${item.workspace.name}", color = colors.muted, fontSize = 12.sp)
            item.note()?.let { OneLine(it, color = colors.text, fontSize = 12.sp) }
        }
    }
}

