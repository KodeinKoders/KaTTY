package app.katty.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.katty.core.AgentStates
import app.katty.core.KattyState
import app.katty.core.NoAgentStates
import app.katty.core.ProjectId
import app.katty.core.counts
import app.katty.ui.Icon
import app.katty.ui.Katty
import app.katty.ui.hoverHighlight
import app.katty.ui.KattyIcons
import app.katty.ui.OneLine
import app.katty.ui.SectionLabel
import app.katty.ui.StateCount
import org.jetbrains.jewel.ui.component.Text

/** What a count of agents says: `9 agents`. */
internal fun agentCount(count: Int): String = if (count == 1) "1 agent" else "$count agents"

/**
 * The list that filters the board by project, in the sidebar of the horizontal layout: "All projects" with how many agents there are, and
 * each project with its counts.
 *
 * @param filter The project that is shown, `null` for all of them.
 */
@Composable
fun BoardSidebar(
    state: KattyState,
    filter: ProjectId?,
    onFilter: (ProjectId?) -> Unit,
    modifier: Modifier = Modifier,
    states: AgentStates = NoAgentStates,
    usage: @Composable () -> Unit = {},
) {
    val colors = Katty.colors
    Column(modifier.width(SIDEBAR_WIDTH).fillMaxHeight().background(colors.side).border(1.dp, colors.line)) {
        SectionLabel("PROJECTS", Modifier.padding(start = 12.dp, top = 8.dp, bottom = 6.dp))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val total = state.allTerminals().size
            FilterRow("All projects", filter == null, { onFilter(null) }) { Text(agentCount(total), color = colors.muted, fontSize = 11.sp) }
            for (project in state.projects) {
                FilterRow(project.name, filter == project.id, { onFilter(project.id) }) {
                    for ((s, n) in states.counts(project)) StateCount(s, n)
                }
            }
        }
        usage()
    }
}

@Composable
private fun FilterRow(label: String, selected: Boolean, onClick: () -> Unit, trailing: @Composable () -> Unit) {
    val colors = Katty.colors
    Row(
        Modifier.fillMaxWidth().height(30.dp).clip(RoundedCornerShape(5.dp))
            .hoverHighlight(enabled = !selected)
            .background(if (selected) colors.sel else Color.Transparent)
            .semantics { contentDescription = label; this.selected = selected }
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(KattyIcons.Folder, colors.muted, size = 16.dp)
        OneLine(label, Modifier.weight(1f), color = colors.text)
        trailing()
    }
}

/** The same filter as tabs along the top, for the vertical layout. */
@Composable
fun BoardTabs(state: KattyState, filter: ProjectId?, onFilter: (ProjectId?) -> Unit, modifier: Modifier = Modifier, states: AgentStates = NoAgentStates) {
    val colors = Katty.colors
    Row(modifier.fillMaxWidth().height(TAB_BAR_HEIGHT).background(colors.side).border(1.dp, colors.line).horizontalScroll(rememberScrollState())) {
        BoardTab("All projects", filter == null, { onFilter(null) }) {}
        for (project in state.projects) {
            BoardTab(project.name, filter == project.id, { onFilter(project.id) }) { for ((s, n) in states.counts(project)) StateCount(s, n) }
        }
        Spacer(Modifier.width(8.dp))
    }
}

@Composable
private fun BoardTab(label: String, selected: Boolean, onClick: () -> Unit, trailing: @Composable () -> Unit) {
    val colors = Katty.colors
    Row(
        Modifier.height(TAB_BAR_HEIGHT).hoverHighlight(enabled = !selected).background(if (selected) colors.sel else Color.Transparent)
            .semantics { contentDescription = label; this.selected = selected }
            .clickable(role = Role.Tab, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OneLine(label, color = colors.text)
        trailing()
    }
}
