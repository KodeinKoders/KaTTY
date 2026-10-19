package app.katty.screens

import app.katty.AppShortcut
import app.katty.ui.MenuEntry
import app.katty.ui.menu

/** The menu of a project, wherever it is listed: the sidebar, or the tabs of the vertical layout. */
fun projectMenu(onNewWorkspace: () -> Unit, onRename: () -> Unit, onReveal: () -> Unit, onRemove: () -> Unit): List<MenuEntry> = menu {
    action("New workspace", AppShortcut.NEW_WORKSPACE.label, onClick = onNewWorkspace)
    action("Rename project…", "F2", onClick = onRename)
    action("Reveal in file manager", onClick = onReveal)
    divider()
    action("Remove project…", danger = true, onClick = onRemove)
}

/** The menu of a workspace. It has no split: splitting acts on a terminal, so it is in the menus of the terminals and in the header. */
fun workspaceMenu(onRename: () -> Unit, onClose: () -> Unit): List<MenuEntry> = menu {
    action("Rename workspace", "F2", onClick = onRename)
    divider()
    action("Close workspace", AppShortcut.CLOSE_WORKSPACE.label, danger = true, onClick = onClose)
}
