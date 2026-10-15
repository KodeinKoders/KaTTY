package app.katty.screens

import app.katty.core.WorkspaceId
import app.katty.ui.MenuEntry
import app.katty.ui.menu

/** A workspace that a terminal can be moved to: [label] says where it is. */
class WorkspaceChoice(val id: WorkspaceId, val label: String)

/** What the menu of a terminal does. */
class TerminalMenuActions(
    val rename: () -> Unit,
    val splitRight: () -> Unit,
    val splitDown: () -> Unit,
    val moveTo: (WorkspaceId) -> Unit,
    val copyDirectory: () -> Unit,
    val close: () -> Unit,
)

/**
 * The menu that the header of a pane opens: renaming, splitting, moving to another workspace, copying where the shell is, closing.
 *
 * @param destinations The other workspaces of every project. There is no entry to move to when there are none.
 */
fun terminalMenu(destinations: List<WorkspaceChoice>, actions: TerminalMenuActions): List<MenuEntry> = menu {
    action("Rename terminal", onClick = actions.rename)
    divider()
    action("Split right", "⌘D", onClick = actions.splitRight)
    action("Split down", "⌘⇧D", onClick = actions.splitDown)
    divider()
    if (destinations.isNotEmpty()) {
        submenu("Move to workspace") {
            for (destination in destinations) action(destination.label) { actions.moveTo(destination.id) }
        }
    }
    divider()
    action("Copy working directory", onClick = actions.copyDirectory)
    divider()
    action("Close terminal", "⌘W", danger = true, onClick = actions.close)
}

/** What the menu of right click in a terminal does. */
class ContextMenuActions(
    val copy: () -> Unit,
    val paste: () -> Unit,
    val selectAll: () -> Unit,
    val clearScrollback: () -> Unit,
    val splitRight: () -> Unit,
    val splitDown: () -> Unit,
    val close: () -> Unit,
)

/**
 * The menu of a right click in a terminal. Copy is for what is selected, so it is there only to be disabled when nothing is.
 */
fun terminalContextMenu(hasSelection: Boolean, actions: ContextMenuActions): List<MenuEntry> = menu {
    action("Copy", "⌘C", enabled = hasSelection, onClick = actions.copy)
    action("Paste", "⌘V", onClick = actions.paste)
    action("Select all", "⌘A", onClick = actions.selectAll)
    divider()
    action("Clear scrollback", "⌘L", onClick = actions.clearScrollback)
    divider()
    action("Split right", "⌘D", onClick = actions.splitRight)
    action("Split down", "⌘⇧D", onClick = actions.splitDown)
    divider()
    action("Close terminal", "⌘W", danger = true, onClick = actions.close)
}
