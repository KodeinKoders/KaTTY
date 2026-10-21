package app.katty.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.katty.core.AgentState
import app.katty.core.TerminalId
import app.katty.core.isActive
import app.katty.ui.AgentGlyph
import app.katty.ui.AgentTile
import app.katty.ui.ConfirmDialog
import app.katty.ui.Katty
import app.katty.ui.OneLine
import app.katty.ui.StateGlyph
import app.katty.ui.TileSize
import org.jetbrains.jewel.ui.component.Text

/** A terminal as a list in a dialog tells it: what runs in it, and what it is doing. */
data class TerminalSummary(val id: TerminalId, val title: String, val glyph: AgentGlyph, val state: AgentState)

@Composable
private fun SummaryRow(summary: TerminalSummary) {
    val colors = Katty.colors
    Row(Modifier.padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        AgentTile(summary.glyph, size = TileSize.LIST)
        OneLine(summary.title, Modifier.weight(1f), color = colors.text)
        StateGlyph(summary.state)
        Text(summary.state.label(), color = colors.muted)
    }
}

/**
 * Asked before a workspace is closed with an agent that is active in it: its terminals are listed with their states, and the agents that
 * are still active will be stopped.
 */
@Composable
fun CloseWorkspaceDialog(workspaceName: String, terminals: List<TerminalSummary>, onConfirm: () -> Unit, onCancel: () -> Unit) {
    val active = terminals.count { it.state.isActive() }
    ConfirmDialog(
        title = "Close “$workspaceName”?",
        message = "Closing this workspace ends all of its terminals. " +
            if (active == 1) "1 agent is still active and will be stopped." else "$active agents are still active and will be stopped.",
        confirmLabel = "Close workspace",
        onConfirm = onConfirm,
        onCancel = onCancel,
        details = { terminals.forEach { SummaryRow(it) } },
    )
}

/** Asked before the last terminal of a workspace is closed with a program in it: closing it deletes the workspace. */
@Composable
fun CloseLastTerminalDialog(workspaceName: String, terminal: TerminalSummary, onConfirm: () -> Unit, onCancel: () -> Unit) {
    ConfirmDialog(
        title = "Close the last terminal?",
        message = "This is the only terminal in “$workspaceName”. Closing it deletes the workspace and stops “${terminal.title}”, which is still running.",
        confirmLabel = "Delete workspace",
        onConfirm = onConfirm,
        onCancel = onCancel,
        details = { SummaryRow(terminal) },
    )
}

/** Asked before a terminal is closed while a program runs in it: the program is stopped. */
@Composable
fun CloseTerminalDialog(terminal: TerminalSummary, onConfirm: () -> Unit, onCancel: () -> Unit) {
    ConfirmDialog(
        title = "Close the terminal?",
        message = "“${terminal.title}” is still running in it and will be stopped.",
        confirmLabel = "Close terminal",
        onConfirm = onConfirm,
        onCancel = onCancel,
        details = { SummaryRow(terminal) },
    )
}

/** Asked before a terminal is restarted while a program runs in it: the program is stopped and a new shell takes its place. */
@Composable
fun RestartTerminalDialog(terminal: TerminalSummary, onConfirm: () -> Unit, onCancel: () -> Unit) {
    ConfirmDialog(
        title = "Restart the terminal?",
        message = "“${terminal.title}” is still running in it and will be stopped, and a new shell starts in its place.",
        confirmLabel = "Restart terminal",
        onConfirm = onConfirm,
        onCancel = onCancel,
        details = { SummaryRow(terminal) },
    )
}
