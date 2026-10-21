package app.katty.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.katty.core.Agent

/**
 * What an agent is drawn as: its logo ([AgentLogos]) in a color of its own, which is the brand's where the brand has one (Claude's orange) and
 * the design's otherwise.
 */
enum class AgentGlyph(val icon: KattyIcon, val tint: Color) {
    CLAUDE(AgentLogos.Claude, Color(0xFFD97757)),
    CODEX(AgentLogos.Codex, Color(0xFF2E9E6B)),
    ANTIGRAVITY(AgentLogos.Antigravity, Color(0xFF8A63D2)),
    COPILOT(AgentLogos.Copilot, Color(0xFF0E8A9A)),
    JUNIE(AgentLogos.Junie, Color(0xFF6AA51E)),
    OPENCODE(AgentLogos.OpenCode, Color(0xFFC9971C)),
    CRUSH(AgentLogos.Crush, Color(0xFFD6479C)),
    AIDER(AgentLogos.Aider, Color(0xFF3FA34D)),
    GOOSE(AgentLogos.Goose, Color(0xFF5B8DEF)),
    CLINE(AgentLogos.Cline, Color(0xFF7C8CFF)),
    KILO(AgentLogos.Kilo, Color(0xFFD4B300)),
    PI(AgentLogos.Pi, Color(0xFF38BDF8)),

    /** A shell, and a program that is not one of the agents. */
    SHELL(KattyIcons.Prompt, Color(0xFF6E7280)),
}

/** The sizes of the tiles of the design: in a pane header, in a list, in the rail. */
enum class TileSize(val dp: Dp, val icon: Dp, val radius: Dp) {
    HEADER(18.dp, 12.dp, 5.dp),
    LIST(20.dp, 13.dp, 5.dp),
    RAIL(24.dp, 16.dp, 6.dp),
}

/** The glyph of an agent on a tile of the hover color, tinted. Decoration: the name of the agent is written next to it. */
@Composable
fun AgentTile(glyph: AgentGlyph, modifier: Modifier = Modifier, size: TileSize = TileSize.HEADER) {
    Box(
        modifier.size(size.dp).clip(RoundedCornerShape(size.radius)).background(Katty.colors.hover).clearAndSetSemantics { },
        contentAlignment = Alignment.Center,
    ) {
        Icon(glyph.icon, glyph.tint, size = size.icon)
    }
}

/** The glyph of an agent, and the neutral one for a program that is not one of the agents that are known. */
fun Agent?.glyph(): AgentGlyph = when (this) {
    Agent.CLAUDE -> AgentGlyph.CLAUDE
    Agent.CODEX -> AgentGlyph.CODEX
    Agent.ANTIGRAVITY -> AgentGlyph.ANTIGRAVITY
    Agent.COPILOT -> AgentGlyph.COPILOT
    Agent.JUNIE -> AgentGlyph.JUNIE
    Agent.OPENCODE -> AgentGlyph.OPENCODE
    Agent.CRUSH -> AgentGlyph.CRUSH
    Agent.AIDER -> AgentGlyph.AIDER
    Agent.GOOSE -> AgentGlyph.GOOSE
    Agent.CLINE -> AgentGlyph.CLINE
    Agent.KILO -> AgentGlyph.KILO
    Agent.PI -> AgentGlyph.PI
    null -> AgentGlyph.SHELL
}
