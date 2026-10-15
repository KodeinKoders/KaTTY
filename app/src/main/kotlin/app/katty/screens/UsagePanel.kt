package app.katty.screens

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
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun UsageList(agents: List<Agent>, readings: Map<Agent, UsageReading>, now: Instant, variant: UsageVariant, modifier: Modifier = Modifier) {
    val colors = Katty.colors
    Column(modifier, verticalArrangement = Arrangement.spacedBy(if (variant == UsageVariant.FULL) 10.dp else 6.dp)) {
        for (agent in agents) {
            val reading = readings[agent]
            val primary = reading?.primary
            val description = reading?.summary(now) ?: "${agent.displayName}: usage not available"
            when (variant) {
                UsageVariant.FULL -> Column(Modifier.semantics { contentDescription = "${agent.displayName} usage" }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AgentTile(agent.glyph(), size = TileSize.LIST)
                        Text(agent.displayName, Modifier.weight(1f), color = colors.text)
                        Text(
                            if (primary != null) "${primary.percentUsed}% used" else "No meter",
                            color = if (primary != null && primary.percentUsed >= USAGE_WARNING_PERCENT) colors.input else colors.muted,
                            fontSize = 12.sp,
                        )
                    }
                    if (primary != null) {
                        UsageBar(primary.percentUsed, Modifier.fillMaxWidth())
                        resetsIn(now, primary.resetsAt)?.let { Text("Resets in $it", color = colors.muted, fontSize = 11.sp) }
                    }
                }
                UsageVariant.COMPACT -> Tooltip(tooltip = { Text(description) }) {
                    Row(Modifier.semantics { contentDescription = description }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AgentTile(agent.glyph(), size = TileSize.HEADER)
                        if (primary != null) UsageBar(primary.percentUsed, Modifier.weight(1f)) else Text("No meter", Modifier.weight(1f), color = colors.muted, fontSize = 11.sp)
                        if (primary != null) Text("${primary.percentUsed}%", color = colors.text, fontSize = 11.sp)
                    }
                }
                UsageVariant.RAIL -> Tooltip(tooltip = { Text(description) }) {
                    Column(Modifier.semantics { contentDescription = description }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        AgentTile(agent.glyph(), size = TileSize.RAIL)
                        Text(primary?.let { "${it.percentUsed}%" } ?: "–", color = colors.muted, fontSize = 10.sp)
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
 */
@Composable
fun UsageChooser(compact: Boolean, onCompact: (Boolean) -> Unit, detected: List<Agent>, shown: Set<String>, onShown: (Agent, Boolean) -> Unit, onDismiss: () -> Unit) {
    val colors = Katty.colors
    Popup(popupPositionProvider = AboveEnd, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        Column(
            Modifier.width(248.dp).shadow(16.dp, RoundedCornerShape(8.dp)).clip(RoundedCornerShape(8.dp)).background(colors.panel)
                .border(1.dp, colors.line, RoundedCornerShape(8.dp)).padding(6.dp).semantics { contentDescription = "Agents to show" },
        ) {
            CheckSetting("Compact mode", compact, onCompact, Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
            Divider(org.jetbrains.jewel.ui.Orientation.Horizontal, Modifier.padding(vertical = 6.dp, horizontal = 4.dp))
            SectionLabel("SHOW USAGE FOR", Modifier.padding(start = 10.dp, top = 2.dp, bottom = 4.dp))
            for (agent in detected) {
                CheckSetting(agent.displayName, agent.id in shown, { onShown(agent, it) }, Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
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
) {
    val colors = Katty.colors
    var choosing by remember { mutableStateOf(false) }
    val agents = detected.filter { it.id in shown }
    Column(modifier.fillMaxWidth().border(1.dp, colors.line).padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("AGENT USAGE", Modifier.weight(1f))
            androidx.compose.foundation.layout.Box {
                KattyIconButton(KattyIcons.Kebab, "Choose agents to show", { choosing = true }, size = ButtonSize.COMPACT)
                if (choosing) UsageChooser(compact, onCompact, detected, shown, onShown, onDismiss = { choosing = false })
            }
        }
        if (agents.isEmpty()) {
            Text("No agents selected.", color = colors.muted, fontSize = 11.sp)
        } else {
            UsageList(agents, readings, now, if (compact) UsageVariant.COMPACT else UsageVariant.FULL)
        }
    }
}

/** The usage at the bottom of the vertical layout: one line of compact items, with the chooser. */
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
) {
    val colors = Katty.colors
    var choosing by remember { mutableStateOf(false) }
    val agents = detected.filter { it.id in shown }
    Row(modifier.fillMaxWidth().background(colors.side).border(1.dp, colors.line).padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        SectionLabel("AGENT USAGE")
        if (agents.isEmpty()) {
            Text("No agents selected.", Modifier.weight(1f), color = colors.muted, fontSize = 11.sp)
        } else {
            for (agent in agents) UsageList(listOf(agent), readings, now, UsageVariant.COMPACT, Modifier.weight(1f))
        }
        androidx.compose.foundation.layout.Box {
            KattyIconButton(KattyIcons.Kebab, "Choose agents to show", { choosing = true }, size = ButtonSize.COMPACT)
            if (choosing) UsageChooser(compact, onCompact, detected, shown, onShown, onDismiss = { choosing = false })
        }
    }
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

