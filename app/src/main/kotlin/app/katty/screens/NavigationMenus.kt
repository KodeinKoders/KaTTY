package app.katty.screens

import app.katty.AppShortcut
import app.katty.core.Ide
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import app.katty.ui.KattyMenu
import app.katty.ui.MenuEntry
import app.katty.ui.menu
import app.katty.ui.onSecondaryClick

/** The menu of a project, wherever it is listed: the sidebar, or the tabs of the vertical layout. */
fun projectMenu(
    onNewWorkspace: () -> Unit,
    onRename: () -> Unit,
    onReveal: () -> Unit,
    onRemove: () -> Unit,
    ides: List<Ide> = emptyList(),
    onOpenIn: (Ide) -> Unit = {},
): List<MenuEntry> = menu {
    action("New workspace", AppShortcut.NEW_WORKSPACE.label, onClick = onNewWorkspace)
    action("Rename project…", "F2", onClick = onRename)
    action("Reveal in file manager", onClick = onReveal)
    if (ides.isNotEmpty()) submenu("Open in") { for (ide in ides) action(ide.displayName) { onOpenIn(ide) } }
    divider()
    action("Remove project…", danger = true, onClick = onRemove)
}

/** The menu of a workspace. It has no split: splitting acts on a terminal, so it is in the menus of the terminals and in the header. */
fun workspaceMenu(onRename: () -> Unit, onClose: () -> Unit): List<MenuEntry> = menu {
    action("Rename workspace", "F2", onClick = onRename)
    divider()
    action("Close workspace", AppShortcut.CLOSE_WORKSPACE.label, danger = true, onClick = onClose)
}

/** The menu of the buttons that add a project: the folder chooser, or the list of the projects opened before. */
fun addProjectMenu(onAdd: () -> Unit, onOpenRecent: () -> Unit): List<MenuEntry> = menu {
    action("Add project folder…", AppShortcut.ADD_PROJECT.label, onClick = onAdd)
    action("Open recent project…", AppShortcut.OPEN_RECENT.label, onClick = onOpenRecent)
}

/** Shows [addProjectMenu] on a right click on what [content] draws: the button that adds a project, wherever it is. */
@Composable
fun AddProjectMenuHost(onAdd: () -> Unit, onOpenRecent: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    var menuAt by remember { mutableStateOf<IntOffset?>(null) }
    Box(modifier.onSecondaryClick { menuAt = IntOffset(it.x.toInt(), it.y.toInt()) }) {
        content()
        menuAt?.let { at -> KattyMenu(addProjectMenu(onAdd, onOpenRecent), onDismiss = { menuAt = null }, offset = at) }
    }
}
