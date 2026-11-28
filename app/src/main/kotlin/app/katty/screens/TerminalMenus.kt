package app.katty.screens

import app.katty.terminal.TerminalCommandOutcome
import app.katty.AppShortcut
import app.katty.SplitLabels
import app.katty.TerminalKeys
import app.katty.core.CommandQuestion
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
    /**
     * Maximizes the terminal over its workspace, or puts it back when [maximized]; `null` when it is alone in its workspace, with nothing to be
     * maximized over, as the button of its header.
     */
    val maximize: (() -> Unit)? = null,
    /** Whether the terminal is the one that is maximized: the item then puts it back. */
    val maximized: Boolean = false,
    val moveTo: (WorkspaceId) -> Unit,
    val colorScheme: () -> Unit,
    val copyDirectory: () -> Unit,
    /** Asks in which format the terminal's history is exported, then where. */
    val exportHistory: () -> Unit = {},
    /** Starts the terminal's shell again, which asks first while a program runs in it, as the shortcut does. */
    val restart: () -> Unit,
    val close: () -> Unit,
)

/**
 * The menu that the header of a pane opens: renaming, splitting, maximizing, moving to another workspace, copying where the shell is, restarting, closing.
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
    actions.maximize?.let { maximize ->
        action(if (actions.maximized) "Restore terminal" else "Maximize terminal", AppShortcut.MAXIMIZE.label, onClick = maximize)
    }
    divider()
    if (destinations.isNotEmpty()) {
        submenu("Move to workspace") {
            for (destination in destinations) action(destination.label) { actions.moveTo(destination.id) }
        }
    }
    action("Color scheme", hint = colorSchemeHint, onClick = actions.colorScheme)
    divider()
    action("Copy working directory", onClick = actions.copyDirectory)
    action("Export history…", onClick = actions.exportHistory)
    divider()
    action("Restart terminal", AppShortcut.RESTART_TERMINAL.label, onClick = actions.restart)
    action("Close terminal", AppShortcut.CLOSE_TERMINAL.label, danger = true, onClick = actions.close)
}

/** What the menu of right click in a terminal does, before what the menu of its pane does. */
class ContextMenuActions(
    val copy: () -> Unit,
    val paste: () -> Unit,
    val selectAll: () -> Unit,
    val find: () -> Unit,
    val clearScreen: () -> Unit,
    /**
     * What the AI can be asked about the last command ([commandQuestionLabel]): why it failed, or what its result says when it ended otherwise;
     * `null` when it has not ended (or there is none), or the AI is off.
     */
    val lastCommandQuestion: CommandQuestion? = null,
    /** Asks the AI [lastCommandQuestion]. */
    val askAboutLastCommand: () -> Unit = {},
    /** Asks the AI for a command described in plain words, to type at the prompt; `null` while the AI is off. */
    val askForCommand: (() -> Unit)? = null,
)

/**
 * The menu of a right click in a terminal: what is done with its text, then every item of the menu of its pane ([paneMenu], made by
 * [terminalMenu]), so that a right click offers all that the button of the header does. Copy is for what is selected, so it is there only to
 * be disabled when nothing is.
 */
fun terminalContextMenu(hasSelection: Boolean, actions: ContextMenuActions, paneMenu: List<MenuEntry>): List<MenuEntry> = menu {
    action("Copy", TerminalKeys.COPY, enabled = hasSelection, onClick = actions.copy)
    action("Paste", TerminalKeys.PASTE, onClick = actions.paste)
    action("Select all", TerminalKeys.SELECT_ALL, onClick = actions.selectAll)
    divider()
    action("Find…", AppShortcut.FIND.label, onClick = actions.find)
    action("Clear screen and scrollback", AppShortcut.CLEAR_SCREEN.label, onClick = actions.clearScreen)
    if (actions.askForCommand != null || actions.lastCommandQuestion != null) {
        divider()
        actions.askForCommand?.let { action("Ask AI for a command…", AppShortcut.ASK_AI_COMMAND.label, onClick = it) }
        actions.lastCommandQuestion?.let { action(commandQuestionLabel(it), onClick = actions.askAboutLastCommand) }
    }
    divider()
    include(paneMenu)
}

/** What the menu of a right click in the run terminal does. */
class RunMenuActions(
    val copy: () -> Unit,
    val paste: () -> Unit,
    val selectAll: () -> Unit,
    val find: () -> Unit,
    val clearScreen: () -> Unit,
    /** Asks the AI why its command failed; `null` while the AI is off. */
    val explain: (() -> Unit)? = null,
    val copyDirectory: () -> Unit,
    /** Runs its command again, which asks first while it runs, as the shortcut does. */
    val restart: () -> Unit,
    val minimize: () -> Unit,
    /** Stops its command, which asks first while it runs, or closes it once the command has ended. */
    val stop: () -> Unit,
)

/**
 * The menu of a right click in the run terminal: what is done with its text, as in a terminal's ([terminalContextMenu]), then what its header does,
 * with the shortcuts that act on it while it is shown. It is no shell and no pane: nothing to split, rename or move, and no command line for the AI
 * to give a command to.
 *
 * @param ended Whether its command has ended: then it is closed, else stopped.
 * @param failed Whether its command failed: the AI can then be asked why.
 */
fun runContextMenu(hasSelection: Boolean, ended: Boolean, failed: Boolean, actions: RunMenuActions): List<MenuEntry> = menu {
    action("Copy", TerminalKeys.COPY, enabled = hasSelection, onClick = actions.copy)
    action("Paste", TerminalKeys.PASTE, onClick = actions.paste)
    action("Select all", TerminalKeys.SELECT_ALL, onClick = actions.selectAll)
    divider()
    action("Find…", AppShortcut.FIND.label, onClick = actions.find)
    action("Clear screen and scrollback", AppShortcut.CLEAR_SCREEN.label, onClick = actions.clearScreen)
    if (failed) actions.explain?.let { explain ->
        divider()
        action(commandQuestionLabel(CommandQuestion.WHY_FAILED), onClick = explain)
    }
    divider()
    action("Copy working directory", onClick = actions.copyDirectory)
    divider()
    action("Run the command again", AppShortcut.RESTART_TERMINAL.label, onClick = actions.restart)
    action("Minimize the run terminal", AppShortcut.RUN_TOGGLE.label, onClick = actions.minimize)
    if (ended) action("Close the run terminal", AppShortcut.CLOSE_TERMINAL.label, danger = true, onClick = actions.stop)
    else action("Stop the run", AppShortcut.CLOSE_TERMINAL.label, danger = true, onClick = actions.stop)
}

/** What the item that asks the AI [question] about a command says. */
fun commandQuestionLabel(question: CommandQuestion): String = when (question) {
    CommandQuestion.WHY_FAILED -> "Ask AI why it failed"
    CommandQuestion.EXPLAIN -> "Explain result with AI"
}

/** What the AI is asked about a command that ended with [outcome]: why, when it failed, else what its result says; `null` while it runs. */
fun commandQuestion(outcome: TerminalCommandOutcome): CommandQuestion? = when (outcome) {
    TerminalCommandOutcome.RUNNING -> null
    TerminalCommandOutcome.FAILED -> CommandQuestion.WHY_FAILED
    else -> CommandQuestion.EXPLAIN
}

/**
 * The menu of the bar of a command (its command mark), right-clicked: selecting its output, as a click on the bar does ([selectText]), then, once it
 * has ended and while the AI is on ([ask] is not `null`), asking the AI about it: why, for a command that failed (red), else what its result says
 * (green, or stopped).
 */
fun commandMarkMenu(outcome: TerminalCommandOutcome, selectText: () -> Unit, ask: (() -> Unit)?): List<MenuEntry> = menu {
    action("Select text", onClick = selectText)
    val question = commandQuestion(outcome)
    if (ask != null && question != null) {
        divider()
        action(commandQuestionLabel(question), onClick = ask)
    }
}
