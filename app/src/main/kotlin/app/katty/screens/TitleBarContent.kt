package app.katty.screens

import app.katty.AppShortcut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
 * What is in the title bar: the sidebar's toggle, and where the user is, as `project / workspace`. In presentation mode ([presenting]) it says
 * so, with a button that stops it.
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
    presenting: Boolean = false,
    onStopPresenting: () -> Unit = {},
    trailing: @Composable () -> Unit = {},
) {
    val colors = Katty.colors
    Row(Modifier.align(Alignment.Start).padding(start = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (showSidebarToggle) KattyIconButton(KattyIcons.SidebarToggle, "Toggle project sidebar", onToggleSidebar, shortcut = AppShortcut.SIDEBAR.label)
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
        if (presenting) PresentingButton(onStopPresenting)
        PaletteButton(compactPalette, onPalette)
        trailing()
    }
}

/** Says that the window is in presentation mode, with its shortcut: a click stops it. */
@Composable
private fun PresentingButton(onClick: () -> Unit) {
    val colors = Katty.colors
    Row(
        Modifier.heightIn(min = 28.dp).clip(RoundedCornerShape(6.dp)).background(colors.accent)
            .semantics(mergeDescendants = true) { contentDescription = "Stop presentation mode" }
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Presenting", color = colors.onStatus, fontWeight = FontWeight.SemiBold)
        AppShortcut.PRESENTATION.label.takeIf { it.isNotEmpty() }?.let { Text(it, color = colors.onStatus.copy(alpha = .8f)) }
    }
}
