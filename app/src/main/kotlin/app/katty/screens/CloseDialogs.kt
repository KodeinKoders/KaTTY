package app.katty.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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

/**
 * A terminal as a list in a dialog tells it: what runs in it, and what it is doing.
 *
 * @param running Whether a program other than the shell has it, which closing it stops.
 * @param place Where it is (`project / workspace`), for a list of terminals of more than one workspace; `null` when it goes without saying.
 */
data class TerminalSummary(
    val id: TerminalId,
    val title: String,
    val glyph: AgentGlyph,
    val state: AgentState,
    val running: Boolean = false,
    val place: String? = null,
)

@Composable
private fun SummaryRow(summary: TerminalSummary) {
    val colors = Katty.colors
    Row(Modifier.padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        AgentTile(summary.glyph, size = TileSize.LIST)
        if (summary.place == null) {
            OneLine(summary.title, Modifier.weight(1f), color = colors.text)
        } else {
            Column(Modifier.weight(1f)) {
                OneLine(summary.title, color = colors.text)
                OneLine(summary.place, color = colors.muted)
            }
        }
        StateGlyph(summary.state)
        // A program that reports nothing (an editor, a build) is not at rest: it is what closing would stop.
        Text(if (summary.running && summary.state == AgentState.IDLE) "Running" else summary.state.label(), color = colors.muted)
    }
}

/**
 * Asked before a workspace is closed with a program running in it, or an agent that is active: its terminals are listed with their states,
 * and what is still running will be stopped.
 */
@Composable
fun CloseWorkspaceDialog(workspaceName: String, terminals: List<TerminalSummary>, onConfirm: () -> Unit, onCancel: () -> Unit) {
    ConfirmDialog(
        title = "Close “$workspaceName”?",
        message = "Closing this workspace ends all of its terminals. " + stoppedByClosing(terminals),
        confirmLabel = "Close workspace",
        onConfirm = onConfirm,
        onCancel = onCancel,
        details = { terminals.forEach { SummaryRow(it) } },
    )
}

/**
 * Asked before the application quits while it would stop something: the terminals where a program runs or an agent is active, in every
 * workspace, with where they are. They come back over their shells at the next launch, but what they were in the middle of is lost.
 */
@Composable
fun QuitDialog(terminals: List<TerminalSummary>, onConfirm: () -> Unit, onCancel: () -> Unit) {
    ConfirmDialog(
        title = "Quit KaTTY?",
        message = stoppedByClosing(terminals) + " They come back over their shells next time, but what they were doing is lost.",
        confirmLabel = "Quit",
        onConfirm = onConfirm,
        onCancel = onCancel,
        details = { terminals.forEach { SummaryRow(it) } },
    )
}

/** What closing [terminals] stops: the agents that are active, and the other programs that run. */
internal fun stoppedByClosing(terminals: List<TerminalSummary>): String {
    val agents = terminals.count { it.state.isActive() }
    val programs = terminals.count { it.running && !it.state.isActive() }
    val agentsPart = if (agents == 1) "1 agent is still active" else "$agents agents are still active"
    val programsPart = if (programs == 1) "1 program is still running" else "$programs programs are still running"
    return when {
        programs == 0 -> "$agentsPart and will be stopped."
        agents == 0 -> "$programsPart and will be stopped."
        else -> "$agentsPart and $programsPart: they will be stopped."
    }
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
