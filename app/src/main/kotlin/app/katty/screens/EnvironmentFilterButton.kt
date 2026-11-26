package app.katty.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.jewel.ui.component.Text
import app.katty.ui.KattyTextButton
import app.katty.core.AgentInfo
import app.katty.core.BoardCard
import app.katty.core.BoardColumn
import app.katty.core.ProjectId
import app.katty.core.TerminalId
import app.katty.core.board
import app.katty.core.shownProjects
import app.katty.core.EnvironmentFilter
import app.katty.core.KattyState
import app.katty.core.environmentNames
import app.katty.ui.Icon
import app.katty.ui.Katty
import app.katty.ui.KattyIconButton
import app.katty.ui.KattyIcons
import app.katty.ui.KattyMenu
import app.katty.ui.LocalEnvironmentColors
import app.katty.ui.MenuEntry
import app.katty.ui.environmentColor
import app.katty.ui.menu

/**
 * The button that filters the lists of projects and the board by environment: a funnel, with a dot while the filter hides something of what it
 * lists. Its menu has a check box for Standard (what has no environment) and for each environment, which stays open while they are checked, and
 * "Show all".
 *
 * There is no button when there is nothing to choose: no environment exists or is used, and Standard is not hidden (a filter that was left hiding it
 * can be undone).
 *
 * @param available The environments that exist.
 * @param onChange Called with the filter that the user made.
 */
@Composable
fun EnvironmentFilterButton(state: KattyState, available: Set<String>, onChange: (EnvironmentFilter) -> Unit, modifier: Modifier = Modifier) {
    val filter = state.settings.environmentFilter
    val names = state.environmentNames(available)
    if (names.isEmpty() && !filter.standardHidden) return
    val colors = Katty.colors
    val chosen = LocalEnvironmentColors.current
    val active = filter.isActive(names)
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        KattyIconButton(
            KattyIcons.Funnel, "Filter environments", { open = !open },
            Modifier.semantics {
                contentDescription = "Filter environments"
                stateDescription = if (active) "Some environments are hidden" else "All environments are shown"
            },
        )
        // The dot at the corner of the button, only while something is hidden.
        if (active) {
            Box(Modifier.align(Alignment.TopEnd).offset(x = (-4).dp, y = 4.dp).size(7.dp).background(colors.accent, CircleShape))
        }
        if (open) {
            val entries: List<MenuEntry> = menu {
                action("Standard", checked = filter.shows(null), swatch = colors.muted, keepOpen = true) { onChange(filter.toggled(null)) }
                for (name in names.sorted()) {
                    action(
                        if (name in available) name else "$name (not found)",
                        checked = filter.shows(name),
                        swatch = if (name in available) environmentColor(name, colors, chosen) else colors.err,
                        keepOpen = true,
                    ) { onChange(filter.toggled(name)) }
                }
                divider()
                // They stay open, so that what they do is seen.
                action("Show all", enabled = active, keepOpen = true) { onChange(EnvironmentFilter.NONE) }
                action("Hide all", enabled = !filter.hidesAll(names), keepOpen = true) { onChange(filter.hidingAll(names)) }
            }
            KattyMenu(entries, onDismiss = { open = false })
        }
    }
}

/** What a screen says when the environment filter leaves nothing in it: [what] is "project" or "agent". */
internal fun emptyBecauseOfFilter(allHidden: Boolean, what: String): String =
    if (allHidden) "No environment is shown" else "No $what in the environments that are shown"

/**
 * What the lists of projects say when the filter leaves none (the selected workspace, which stays listed whatever the filter is, does not count),
 * `null` when they are not empty because of it.
 */
internal fun KattyState.emptyListMessage(available: Set<String>): String? {
    val filter = settings.environmentFilter
    val names = environmentNames(available)
    if (!filter.isActive(names) || projects.isEmpty() || shownProjects(filter).isNotEmpty()) return null
    return emptyBecauseOfFilter(filter.hidesAll(names), "project")
}

/**
 * What the board says when it has no card ([columns], made with the filter) and the filter is why: it hides every environment, or the agents that
 * the board would show without it (of the project [projectFilter]) are in the environments that it hides. `null` otherwise.
 */
internal fun KattyState.emptyBoardMessage(
    available: Set<String>,
    columns: Map<BoardColumn, List<BoardCard>>,
    infos: Map<TerminalId, AgentInfo>,
    projectFilter: ProjectId?,
): String? {
    val filter = settings.environmentFilter
    val names = environmentNames(available)
    if (!filter.isActive(names) || columns.values.any { it.isNotEmpty() }) return null
    val hidesAll = filter.hidesAll(names)
    if (!hidesAll && board(projectFilter, infos).values.all { it.isEmpty() }) return null
    return emptyBecauseOfFilter(hidesAll, "agent")
}

/** The message of a screen that the filter left empty, with the button that shows every environment again. */
@Composable
fun EnvironmentFilterEmpty(message: String, onShowAll: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(message, color = Katty.colors.muted, fontSize = 11.sp)
        KattyTextButton("Show all", onShowAll, description = "Show all environments")
    }
}

/** What the area of the terminals shows when the filter hides the workspace that is selected and shows none to go to: [message], and the way back. */
@Composable
fun EmptyBecauseOfFilter(message: String, onShowAll: () -> Unit, modifier: Modifier = Modifier) {
    val colors = Katty.colors
    Column(
        modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(KattyIcons.Funnel, colors.muted, size = 32.dp)
        Text(message, color = colors.muted)
        KattyTextButton("Show all", onShowAll, description = "Show all environments")
    }
}
