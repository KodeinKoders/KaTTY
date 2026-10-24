package app.katty.screens

import app.katty.AppShortcut
import app.katty.SplitLabels
import app.katty.TerminalKeys
import app.katty.core.KattyState
import app.katty.core.TerminalId
import app.katty.core.WorkspaceId
import app.katty.ui.MenuEntry
import app.katty.ui.menu

/** A workspace that a terminal can be moved to: [label] says where it is. */
class WorkspaceChoice(val id: WorkspaceId, val label: String)

/** The workspaces that [terminal] can be moved to: the other ones of its own project, so none when the project has only one. */
fun destinationsOf(state: KattyState, terminal: TerminalId): List<WorkspaceChoice> {
    val here = state.locate(terminal) ?: return emptyList()
    return here.project.workspaces.filter { it.id != here.workspace.id }.map { WorkspaceChoice(it.id, it.name) }
}

/** What the menu of a terminal does. */
class TerminalMenuActions(
    val rename: () -> Unit,
    /** Goes back to calling the terminal after what runs in it; `null` for a terminal that has no name of its own. */
    val useProcessName: (() -> Unit)? = null,
    val splitRight: () -> Unit,
    val splitDown: () -> Unit,
    val moveTo: (WorkspaceId) -> Unit,
    val colorScheme: () -> Unit,
    val copyDirectory: () -> Unit,
    /** Starts the terminal's shell again, which asks first while a program runs in it, as the shortcut does. */
    val restart: () -> Unit,
    val close: () -> Unit,
)

/**
 * The menu that the header of a pane opens: renaming, splitting, moving to another workspace, copying where the shell is, restarting, closing.
 *
 * @param destinations The other workspaces of the terminal's project. There is no entry to move to when there are none.
 * @param colorSchemeHint What the terminal's color scheme is: `Default`, or the name of the one that it has.
 * @param vertical Whether the layout is the vertical one, where the main direction of a split, which has the shortcut without Shift, is down.
 */
fun terminalMenu(destinations: List<WorkspaceChoice>, colorSchemeHint: String, actions: TerminalMenuActions, vertical: Boolean = false): List<MenuEntry> = menu {
    val split = SplitLabels.of(vertical)
    action("Rename terminal", AppShortcut.RENAME_TERMINAL.label, onClick = actions.rename)
    // Only a terminal that has a name of its own has one to give up.
    actions.useProcessName?.let { revert -> action("Use process name", onClick = revert) }
    divider()
    action("Split right", split.rightShortcut, onClick = actions.splitRight)
    action("Split down", split.downShortcut, onClick = actions.splitDown)
    divider()
    if (destinations.isNotEmpty()) {
        submenu("Move to workspace") {
            for (destination in destinations) action(destination.label) { actions.moveTo(destination.id) }
        }
    }
    action("Color scheme", hint = colorSchemeHint, onClick = actions.colorScheme)
    divider()
    action("Copy working directory", onClick = actions.copyDirectory)
    divider()
    action("Restart terminal", AppShortcut.RESTART_TERMINAL.label, onClick = actions.restart)
    action("Close terminal", AppShortcut.CLOSE_TERMINAL.label, danger = true, onClick = actions.close)
}

/** What the menu of right click in a terminal does. */
class ContextMenuActions(
    val copy: () -> Unit,
    val paste: () -> Unit,
    val selectAll: () -> Unit,
    val find: () -> Unit,
    val clearScreen: () -> Unit,
    val splitRight: () -> Unit,
    val splitDown: () -> Unit,
    /** Starts the terminal's shell again, which asks first while a program runs in it, as the shortcut does. */
    val restart: () -> Unit,
    val close: () -> Unit,
)

/**
 * The menu of a right click in a terminal. Copy is for what is selected, so it is there only to be disabled when nothing is.
 */
fun terminalContextMenu(hasSelection: Boolean, actions: ContextMenuActions, vertical: Boolean = false): List<MenuEntry> = menu {
    val split = SplitLabels.of(vertical)
    action("Copy", TerminalKeys.COPY, enabled = hasSelection, onClick = actions.copy)
    action("Paste", TerminalKeys.PASTE, onClick = actions.paste)
    action("Select all", TerminalKeys.SELECT_ALL, onClick = actions.selectAll)
    divider()
    action("Find…", AppShortcut.FIND.label, onClick = actions.find)
    action("Clear screen and scrollback", AppShortcut.CLEAR_SCREEN.label, onClick = actions.clearScreen)
    divider()
    action("Split right", split.rightShortcut, onClick = actions.splitRight)
    action("Split down", split.downShortcut, onClick = actions.splitDown)
    divider()
    action("Restart terminal", AppShortcut.RESTART_TERMINAL.label, onClick = actions.restart)
    action("Close terminal", AppShortcut.CLOSE_TERMINAL.label, danger = true, onClick = actions.close)
}
