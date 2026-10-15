package app.katty.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.katty.core.AgentState
import app.katty.core.BoardCard
import app.katty.core.BoardColumn
import app.katty.core.GitInfo
import app.katty.core.GitKind
import app.katty.core.TerminalId
import app.katty.core.terminalIdentity
import app.katty.core.relativeTime
import app.katty.terminals.TerminalHandle
import app.katty.ui.AgentTile
import app.katty.ui.CountPill
import app.katty.ui.Icon
import app.katty.ui.Katty
import app.katty.ui.hoverHighlight
import app.katty.ui.KattyIcons
import app.katty.ui.OneLine
import app.katty.ui.StateGlyph
import app.katty.ui.TileSize
import app.katty.ui.glyph
import org.jetbrains.jewel.ui.component.Text

/** What a card says when its program said nothing: in words that say what the state is, since the card has no more to say. */
internal fun BoardCard.defaultNote(): String = when (info.state) {
    AgentState.IDLE -> if (info.report.reporting) "At rest." else "Idle shell, waiting at the prompt."
    AgentState.WORKING -> "Working."
    AgentState.NEEDS_INPUT -> "Waiting for you."
    AgentState.ERROR -> "Failed."
    AgentState.DONE -> "Finished."
}

/** What a card calls its terminal, and which agent's tile it has: as the pane does. */
internal fun BoardCard.identity(process: String?, shell: String) = terminalIdentity(terminal.name, info.report, process, shell)

/**
 * The board of the agents: a column for each state, and in it a card for each terminal, with what runs, where it is, since when it has been
 * in that state and what it said. It is for looking at: cards cannot be moved, and one that is clicked opens its terminal.
 *
 * @param vertical Whether the columns are stacked, as the vertical layout draws them.
 */
@Composable
fun AgentBoard(
    columns: Map<BoardColumn, List<BoardCard>>,
    handles: Map<TerminalId, TerminalHandle>,
    now: Long,
    vertical: Boolean,
    onOpen: (TerminalId) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Katty.colors
    val cards = columns.values.flatten()
    val projects = cards.map { it.project.id }.toSet().size
    Column(modifier.fillMaxSize().background(colors.bg)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Agent board", color = colors.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "${cards.size} ${if (cards.size == 1) "agent" else "agents"} across $projects ${if (projects == 1) "project" else "projects"}",
                color = colors.muted,
            )
        }
        if (vertical) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                for ((column, list) in columns) BoardSection(column, list, handles, now, onOpen)
            }
        } else {
            Row(Modifier.weight(1f).padding(horizontal = 12.dp).padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                for ((column, list) in columns) {
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        BoardHeader(column, list.size)
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (card in list) Card(card, handles[card.terminal.id], now, onOpen)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BoardSection(column: BoardColumn, cards: List<BoardCard>, handles: Map<TerminalId, TerminalHandle>, now: Long, onOpen: (TerminalId) -> Unit) {
    Column(Modifier.semantics { contentDescription = column.title }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BoardHeader(column, cards.size)
        for (card in cards) Card(card, handles[card.terminal.id], now, onOpen)
    }
}

@Composable
private fun BoardHeader(column: BoardColumn, count: Int) {
    val colors = Katty.colors
    Row(Modifier.fillMaxWidth().height(36.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StateGlyph(
            when (column) {
                BoardColumn.NOTHING -> AgentState.IDLE
                BoardColumn.WORKING -> AgentState.WORKING
                BoardColumn.NEEDS_INPUT -> AgentState.NEEDS_INPUT
                BoardColumn.DONE -> AgentState.DONE
            },
        )
        Text(column.title, Modifier.weight(1f), color = colors.text, fontWeight = FontWeight.SemiBold)
        CountPill(count)
    }
}

@Composable
private fun Card(card: BoardCard, handle: TerminalHandle?, now: Long, onOpen: (TerminalId) -> Unit) {
    val colors = Katty.colors
    val git: GitInfo? = handle?.git?.collectAsState()?.value
    val identity = card.identity(handle?.processName?.collectAsState()?.value, handle?.shellName ?: "shell")
    val title = identity.title
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(colors.panel).hoverHighlight().border(1.dp, colors.line, RoundedCornerShape(8.dp))
            .semantics { contentDescription = "$title in ${card.project.name} / ${card.workspace.name}" }
            .clickable(role = Role.Button) { onOpen(card.terminal.id) }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StateGlyph(card.info.state)
            AgentTile(identity.agent.glyph(), size = TileSize.HEADER)
            OneLine(title, Modifier.weight(1f), color = colors.text)
            Text(relativeTime(now, card.info.since), color = colors.muted, fontSize = 11.sp)
        }
        OneLine("${card.project.name} / ${card.workspace.name}", color = colors.muted, fontSize = 12.sp)
        if (git != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(if (git.kind == GitKind.WORKTREE) KattyIcons.Worktree else KattyIcons.Branch, colors.muted, size = 12.dp)
                Text(git.name, color = colors.muted, fontSize = 12.sp, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Text(card.info.report.note ?: card.defaultNote(), color = colors.text, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

