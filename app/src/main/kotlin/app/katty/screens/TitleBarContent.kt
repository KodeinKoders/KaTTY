package app.katty.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.katty.core.KattyState
import app.katty.core.MainView
import app.katty.ui.Choice
import app.katty.ui.ChoiceOption
import app.katty.ui.Katty
import app.katty.ui.KattyIconButton
import app.katty.ui.KattyIcons
import app.katty.ui.OneLine
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.window.TitleBarScope

/**
 * What is in the title bar: the sidebar's toggle, and where the user is, as `project / workspace`.
 */
@Composable
fun TitleBarScope.TitleBarContent(
    state: KattyState,
    onToggleSidebar: () -> Unit,
    showSidebarToggle: Boolean = true,
    view: MainView = MainView.TERMINALS,
    onView: (MainView) -> Unit = {},
    boardTitle: String = "All projects",
    compactPalette: Boolean = false,
    onPalette: () -> Unit = {},
    trailing: @Composable () -> Unit = {},
) {
    val colors = Katty.colors
    Row(Modifier.align(Alignment.Start).padding(start = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (showSidebarToggle) KattyIconButton(KattyIcons.SidebarToggle, "Toggle project sidebar", onToggleSidebar)
        val project = state.selectedProject
        if (view == MainView.BOARD) {
            OneLine(boardTitle, color = colors.text)
        } else if (project == null) {
            Text("KaTTY", color = colors.text, fontWeight = FontWeight.SemiBold)
        } else {
            OneLine(project.name, color = colors.text, fontSize = androidx.compose.ui.unit.TextUnit.Unspecified)
            state.selectedWorkspace?.let { workspace ->
                Text("/", color = colors.muted)
                OneLine(workspace.name, color = colors.text)
            }
        }
    }
    // What the window shows: the terminals, or the board of the agents.
    Row(Modifier.align(Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
        Choice(listOf(ChoiceOption(MainView.TERMINALS, "Terminals"), ChoiceOption(MainView.BOARD, "Board")), view, onView)
    }
    Row(Modifier.align(Alignment.End).padding(end = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        PaletteButton(compactPalette, onPalette)
        trailing()
    }
}
