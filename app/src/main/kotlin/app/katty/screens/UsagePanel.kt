package app.katty.screens

import app.katty.ui.uiScaleFactor
import app.katty.core.usageKey
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import app.katty.core.Agent
import app.katty.core.UsageReading
import app.katty.core.resetsIn
import app.katty.ui.AgentTile
import app.katty.ui.ButtonSize
import app.katty.ui.CheckSetting
import app.katty.ui.Katty
import app.katty.ui.Icon
import app.katty.ui.KattyIconButton
import app.katty.ui.KattyIcons
import app.katty.ui.SectionLabel
import app.katty.ui.TileSize
import app.katty.ui.UsageBar
import app.katty.ui.USAGE_WARNING_PERCENT
import app.katty.ui.glyph
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Tooltip
import java.time.Instant

/** Where the usage is shown, which decides how much of it is. */
enum class UsageVariant {
    /** The sidebar: each agent with its bar and when it starts again. */
    FULL,

    /** One line for each agent: its tile, its bar and its percent. */
    COMPACT,

    /** The rail: the tile and the percent, one under the other. */
    RAIL,
}

private fun UsageReading.summary(now: Instant): String = windows.joinToString("; ") { window ->
    "${window.label}: ${window.percentUsed}% used" + (resetsIn(now, window.resetsAt)?.let { ", resets in $it" } ?: "")
}

/**
 * The usage of the agents that the user chose to see: what each says it has used, from the agent's own command. An agent that is chosen
 * and has no meter (it has no command that says it, or it did not answer) is listed without one.
 *
 * @param agents The agents to show, in order.
 * @param readings What was read.
 * @param hidden The limits that are not shown, made by [usageKey].
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun UsageList(
    agents: List<Agent>,
    readings: Map<Agent, UsageReading>,
    now: Instant,
    variant: UsageVariant,
    modifier: Modifier = Modifier,
    hidden: Set<String> = emptySet(),
) {
    val colors = Katty.colors
    Column(modifier, verticalArrangement = Arrangement.spacedBy(if (variant == UsageVariant.FULL) 10.dp else 6.dp)) {
        for (agent in agents) {
            val reading = readings[agent]
            val reported = reading?.windows.orEmpty()
            val windows = reported.filter { usageKey(agent, it.label) !in hidden }
            // What it says when there is nothing to draw: the agent said nothing, or the user chose to see none of what it said.
            val none = if (reported.isEmpty()) "No meter" else "No limit shown"
            val description = reading?.copy(windows = windows)?.takeIf { windows.isNotEmpty() }?.summary(now)
                ?: if (reading == null) "${agent.displayName}: usage not available" else "${agent.displayName}: $none"
            when (variant) {
                UsageVariant.FULL -> Column(Modifier.semantics { contentDescription = "${agent.displayName} usage" }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AgentTile(agent.glyph(), size = TileSize.LIST)
                        Text(agent.displayName, Modifier.weight(1f), color = colors.text)
                        if (windows.isEmpty()) Text(none, color = colors.muted, fontSize = 12.sp)
                    }
                    // Every allowance has its bar: a session and a week are both what the user runs out of.
                    for (window in windows) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(window.label, Modifier.weight(1f), color = colors.muted, fontSize = 12.sp, maxLines = 1)
                            Text(
                                "${window.percentUsed}% used",
                                color = if (window.percentUsed >= USAGE_WARNING_PERCENT) colors.input else colors.muted,
                                fontSize = 12.sp,
                            )
                        }
                        UsageBar(window.percentUsed, Modifier.fillMaxWidth())
                        resetsIn(now, window.resetsAt)?.let { Text("Resets in $it", color = colors.muted, fontSize = 11.sp) }
                    }
                }
                UsageVariant.COMPACT -> Tooltip(tooltip = { Text(description) }) {
                    Row(Modifier.semantics { contentDescription = description }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AgentTile(agent.glyph(), size = TileSize.HEADER)
                        if (windows.isEmpty()) {
                            Text(none, Modifier.weight(1f), color = colors.muted, fontSize = 11.sp)
                        } else {
                            // A thin bar with its percent for each allowance, one under the other.
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                for (window in windows) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        UsageBar(window.percentUsed, Modifier.weight(1f), height = 3.dp)
                                        Text("${window.percentUsed}%", color = colors.text, fontSize = 11.sp)
                                    }
                                }
                            }
                        }
                    }
                }
                UsageVariant.RAIL -> Tooltip(tooltip = { Text(description) }) {
                    Column(Modifier.semantics { contentDescription = description }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        AgentTile(agent.glyph(), size = TileSize.RAIL)
                        if (windows.isEmpty()) Text("–", color = colors.muted, fontSize = 10.sp)
                        for (window in windows) Text("${window.percentUsed}%", color = colors.muted, fontSize = 10.sp)
                    }
                }
            }
        }
    }
}

// Above the button that opens it, with its right edge on the button's: the usage is at the bottom of the window.
private object AboveEnd : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset =
        IntOffset((anchorBounds.right - popupContentSize.width).coerceAtLeast(0), (anchorBounds.top - popupContentSize.height - 4).coerceAtLeast(0))
}

/**
 * The popover that chooses what the usage shows: compact mode, and the agents, which are the ones that are installed.
 *
 * @param detected The agents that are on the machine: the ones that can be chosen.
 * @param readings What the agents reported: under each agent that is chosen, the limits that it reported can be chosen too.
 * @param hidden The limits that are not shown, made by [usageKey]; [onHidden] is told which one is hidden (`true`) or shown again.
 */
@Composable
fun UsageChooser(
    compact: Boolean,
    onCompact: (Boolean) -> Unit,
    detected: List<Agent>,
    shown: Set<String>,
    onShown: (Agent, Boolean) -> Unit,
    onDismiss: () -> Unit,
    readings: Map<Agent, UsageReading> = emptyMap(),
    hidden: Set<String> = emptySet(),
    onHidden: (Agent, String, Boolean) -> Unit = { _, _, _ -> },
) {
    val colors = Katty.colors
    Popup(popupPositionProvider = AboveEnd, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        Column(
            Modifier.width(248.dp * uiScaleFactor()).shadow(16.dp, RoundedCornerShape(8.dp)).clip(RoundedCornerShape(8.dp)).background(colors.panel)
                .border(1.dp, colors.line, RoundedCornerShape(8.dp)).heightIn(max = 460.dp).verticalScroll(rememberScrollState()).padding(6.dp)
                .semantics { contentDescription = "Agents to show" },
        ) {
            CheckSetting("Compact mode", compact, onCompact, Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
            Divider(org.jetbrains.jewel.ui.Orientation.Horizontal, Modifier.padding(vertical = 6.dp, horizontal = 4.dp))
            SectionLabel("SHOW USAGE FOR", Modifier.padding(start = 10.dp, top = 2.dp, bottom = 4.dp))
            for (agent in detected) {
                CheckSetting(agent.displayName, agent.id in shown, { onShown(agent, it) }, Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
                // The limits that it reported, each of which can be left out: a limit for one model is told by the agent in its label.
                if (agent.id in shown) {
                    for (window in readings[agent]?.windows.orEmpty()) {
                        CheckSetting(
                            window.label, usageKey(agent, window.label) !in hidden, { onHidden(agent, window.label, !it) },
                            Modifier.padding(start = 28.dp, end = 8.dp, top = 2.dp, bottom = 2.dp),
                        )
                    }
                }
            }
            Divider(org.jetbrains.jewel.ui.Orientation.Horizontal, Modifier.padding(vertical = 6.dp, horizontal = 4.dp))
            Text("Agents are detected from the CLIs installed on this machine.", Modifier.padding(horizontal = 8.dp, vertical = 2.dp), color = colors.muted, fontSize = 11.sp)
        }
    }
}

/**
 * The usage at the bottom of the sidebar: its label, the button that opens the chooser, and the list of the agents that were chosen.
 */
@Composable
fun UsageSection(
    detected: List<Agent>,
    readings: Map<Agent, UsageReading>,
    shown: Set<String>,
    compact: Boolean,
    now: Instant,
    onCompact: (Boolean) -> Unit,
    onShown: (Agent, Boolean) -> Unit,
    modifier: Modifier = Modifier,
    hidden: Set<String> = emptySet(),
    onHidden: (Agent, String, Boolean) -> Unit = { _, _, _ -> },
    refreshing: Boolean = false,
    onRefresh: () -> Unit = {},
) {
    val colors = Katty.colors
    var choosing by remember { mutableStateOf(false) }
    val agents = detected.filter { it.id in shown }
    Column(modifier.fillMaxWidth().border(1.dp, colors.line).padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("AGENT USAGE", Modifier.weight(1f))
            RefreshButton(refreshing, onRefresh, enabled = agents.isNotEmpty())
            androidx.compose.foundation.layout.Box {
                KattyIconButton(KattyIcons.Kebab, "Choose agents to show", { choosing = true }, size = ButtonSize.COMPACT)
                if (choosing) UsageChooser(compact, onCompact, detected, shown, onShown, onDismiss = { choosing = false }, readings = readings, hidden = hidden, onHidden = onHidden)
            }
        }
        if (agents.isEmpty()) {
            Text("No agents selected.", color = colors.muted, fontSize = 11.sp)
        } else {
            UsageList(agents, readings, now, if (compact) UsageVariant.COMPACT else UsageVariant.FULL, hidden = hidden)
        }
    }
}

/** The usage at the bottom of the vertical layout: one line with an item for each agent, compact or full as the user chose, with the chooser. */
@Composable
fun UsageBarRow(
    detected: List<Agent>,
    readings: Map<Agent, UsageReading>,
    shown: Set<String>,
    compact: Boolean,
    now: Instant,
    onCompact: (Boolean) -> Unit,
    onShown: (Agent, Boolean) -> Unit,
    modifier: Modifier = Modifier,
    hidden: Set<String> = emptySet(),
    onHidden: (Agent, String, Boolean) -> Unit = { _, _, _ -> },
    refreshing: Boolean = false,
    onRefresh: () -> Unit = {},
) {
    val colors = Katty.colors
    var choosing by remember { mutableStateOf(false) }
    val agents = detected.filter { it.id in shown }
    Row(modifier.fillMaxWidth().background(colors.side).border(1.dp, colors.line).padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        // The full items are tall, so the label is on two lines and takes less of the width; the compact ones are a line and need no label.
        if (!compact) {
            Column {
                SectionLabel("AGENT")
                SectionLabel("USAGE")
            }
        }
        if (agents.isEmpty()) {
            Text("No agents selected.", Modifier.weight(1f), color = colors.muted, fontSize = 11.sp)
        } else {
            for (agent in agents) UsageList(listOf(agent), readings, now, if (compact) UsageVariant.COMPACT else UsageVariant.FULL, Modifier.weight(1f), hidden)
        }
        // The buttons are one under the other for the full items, which are tall, and side by side for the compact ones, which are not.
        val buttons: @Composable () -> Unit = {
            val menu: @Composable () -> Unit = {
                androidx.compose.foundation.layout.Box {
                    KattyIconButton(KattyIcons.Kebab, "Choose agents to show", { choosing = true }, size = ButtonSize.COMPACT)
                    if (choosing) UsageChooser(compact, onCompact, detected, shown, onShown, onDismiss = { choosing = false }, readings = readings, hidden = hidden, onHidden = onHidden)
                }
            }
            val refresh: @Composable () -> Unit = { RefreshButton(refreshing, onRefresh, enabled = agents.isNotEmpty()) }
            if (compact) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    refresh()
                    menu()
                }
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    menu()
                    refresh()
                }
            }
        }
        buttons()
    }
}

/**
 * Reads the usage of every agent again. It is disabled, grayed out, while they are being read, and does not move: an animation would draw
 * the eye to something that the user has no use for.
 */
@Composable
private fun RefreshButton(refreshing: Boolean, onRefresh: () -> Unit, enabled: Boolean) {
    KattyIconButton(KattyIcons.Restart, "Refresh usage", onRefresh, size = ButtonSize.COMPACT, enabled = enabled && !refreshing)
}

/**
 * What the Welcome screen says of the agents, in two sections: the ones that were found on the machine, and the ones that KaTTY supports and
 * that were not, which is folded until the user opens it.
 */
@Composable
fun DetectedAgents(detected: Map<Agent, java.nio.file.Path>, modifier: Modifier = Modifier) {
    val colors = Katty.colors
    var notFoundOpen by remember { mutableStateOf(false) }
    val found = Agent.entries.filter { it in detected }
    val missing = Agent.entries.filter { it !in detected }
    Column(modifier.widthIn(max = 480.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SectionLabel("DETECTED ON THIS MACHINE")
            Text(found.size.toString(), color = colors.muted, fontSize = 11.sp)
        }
        if (found.isEmpty()) Text("No agent was found on this machine.", color = colors.muted, fontSize = 12.sp)
        for (agent in found) AgentLine(agent, found = true)
        if (missing.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp).clickable(role = Role.Button) { notFoundOpen = !notFoundOpen }
                    .semantics { contentDescription = "Not found: ${missing.size}"; stateDescription = if (notFoundOpen) "Expanded" else "Collapsed" },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(if (notFoundOpen) KattyIcons.ChevronDown else KattyIcons.ChevronRight, colors.muted, size = 12.dp)
                SectionLabel("NOT FOUND")
                Text(missing.size.toString(), color = colors.muted, fontSize = 11.sp)
            }
            if (notFoundOpen) for (agent in missing) AgentLine(agent, found = false)
        }
        Text("Agents you install later appear here automatically.", Modifier.padding(top = 4.dp), color = colors.muted, fontSize = 11.sp)
    }
}

@Composable
private fun AgentLine(agent: Agent, found: Boolean) {
    val colors = Katty.colors
    Row(
        Modifier.fillMaxWidth().semantics { contentDescription = "${agent.displayName}: ${if (found) "Detected" else "Not found"}" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        AgentTile(agent.glyph(), size = TileSize.LIST)
        Text(agent.displayName, color = colors.text)
        Text(agent.executable, Modifier.weight(1f), color = colors.muted, fontSize = 12.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
        Text(if (found) "Detected" else "Not found", color = if (found) colors.done else colors.muted, fontSize = 12.sp)
    }
}

