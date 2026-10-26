package app.katty

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.type
import java.awt.event.KeyEvent as Awt

// A binding with Command, which every shortcut of the window has, and the other modifiers that are asked for.
private fun cmd(key: Int, ctrl: Boolean = false, alt: Boolean = false, shift: Boolean = false) = KeyBinding(key, meta = true, ctrl = ctrl, alt = alt, shift = shift)

/**
 * The shortcuts of the window: all of them with Command, which a program in a terminal never sees. They are the single source of what the
 * menus and the palette show next to a command ([label], which follows what the user bound it to) and of what the window does when the keys are
 * pressed ([appShortcut]).
 *
 * @param group Where the dialog of the shortcuts lists it, and [title] what it calls it.
 * @param defaults What it is bound to unless the user changed it: the first is what is shown, the others are keys that mean the same without being
 * shown (the plus is the equal sign with Shift, and the numeric keypad has its own).
 * @param takesDigit Whether it is the digits 1 to 9 that it is bound to, with the modifiers of its binding (a workspace, a pane by its number).
 */
internal enum class AppShortcut(val group: ShortcutGroup, val title: String, val defaults: List<KeyBinding>, val takesDigit: Boolean = false) {
    ADD_PROJECT(ShortcutGroup.PROJECTS, "Add a project folder", listOf(cmd(Awt.VK_O))),
    NEW_WORKSPACE(ShortcutGroup.PROJECTS, "New workspace", listOf(cmd(Awt.VK_N))),
    OPEN_RECENT(ShortcutGroup.PROJECTS, "Open a recent project", listOf(cmd(Awt.VK_O, shift = true))),
    CLOSE_WORKSPACE(ShortcutGroup.PROJECTS, "Close the workspace", listOf(cmd(Awt.VK_W, shift = true))),

    /** Splits in the main direction of the layout: right in the horizontal one, down in the vertical one. */
    SPLIT_MAIN(ShortcutGroup.PANES, "Split in the main direction of the layout", listOf(cmd(Awt.VK_D))),

    /** Splits across the main direction. */
    SPLIT_OTHER(ShortcutGroup.PANES, "Split across the main direction", listOf(cmd(Awt.VK_D, shift = true))),
    CLOSE_TERMINAL(ShortcutGroup.PANES, "Close the terminal", listOf(cmd(Awt.VK_W))),
    RESTART_TERMINAL(ShortcutGroup.PANES, "Restart the terminal", listOf(cmd(Awt.VK_R))),
    JUMP_TO_WAITING(ShortcutGroup.AGENTS, "Jump to the next agent that waits", listOf(cmd(Awt.VK_J, shift = true))),

    /** Opens the list of the agents that need the user, and closes it. */
    ATTENTION(ShortcutGroup.AGENTS, "Show the agents that need you", listOf(cmd(Awt.VK_J))),

    /** Shows the agent board in place of the terminals, and the terminals in place of the board. */
    BOARD(ShortcutGroup.AGENTS, "Switch between the board and the terminals", listOf(cmd(Awt.VK_B, shift = true))),

    /** Shows the sidebar of the projects, or folds it into the rail. */
    SIDEBAR(ShortcutGroup.AGENTS, "Show the project sidebar, or fold it", listOf(cmd(Awt.VK_B))),

    /** Maximizes the pane over its workspace, or puts it back. */
    MAXIMIZE(ShortcutGroup.PANES, "Maximize the pane, or put it back", listOf(cmd(Awt.VK_ENTER, shift = true))),

    /** Move the nearest divider of the pane's splits that is along the arrow. */
    RESIZE_LEFT(ShortcutGroup.PANES, "Move the divider to the left", listOf(cmd(Awt.VK_LEFT, ctrl = true))),
    RESIZE_RIGHT(ShortcutGroup.PANES, "Move the divider to the right", listOf(cmd(Awt.VK_RIGHT, ctrl = true))),
    RESIZE_UP(ShortcutGroup.PANES, "Move the divider up", listOf(cmd(Awt.VK_UP, ctrl = true))),
    RESIZE_DOWN(ShortcutGroup.PANES, "Move the divider down", listOf(cmd(Awt.VK_DOWN, ctrl = true))),

    /** The next and the previous match of the search that is open. */
    FIND_NEXT(ShortcutGroup.TERMINAL, "Next match of the search", listOf(cmd(Awt.VK_G))),
    FIND_PREVIOUS(ShortcutGroup.TERMINAL, "Previous match of the search", listOf(cmd(Awt.VK_G, shift = true))),
    FONT_BIGGER(
        ShortcutGroup.LOOK, "Bigger terminal font",
        listOf(
            cmd(Awt.VK_EQUALS), cmd(Awt.VK_EQUALS, shift = true), cmd(Awt.VK_PLUS), cmd(Awt.VK_PLUS, shift = true),
            cmd(Awt.VK_ADD), cmd(Awt.VK_ADD, shift = true),
        ),
    ),
    FONT_SMALLER(ShortcutGroup.LOOK, "Smaller terminal font", listOf(cmd(Awt.VK_MINUS), cmd(Awt.VK_SUBTRACT))),
    FONT_RESET(ShortcutGroup.LOOK, "Default terminal font size", listOf(cmd(Awt.VK_0))),

    /** Opens the main menu, and closes it. */
    MENU(ShortcutGroup.LOOK, "Open the main menu", listOf(cmd(Awt.VK_COMMA))),
    PALETTE(ShortcutGroup.LOOK, "Open the command palette", listOf(cmd(Awt.VK_K))),
    FOCUS_LEFT(ShortcutGroup.PANES, "Focus the pane on the left", listOf(cmd(Awt.VK_LEFT, alt = true))),
    FOCUS_RIGHT(ShortcutGroup.PANES, "Focus the pane on the right", listOf(cmd(Awt.VK_RIGHT, alt = true))),
    FOCUS_UP(ShortcutGroup.PANES, "Focus the pane above", listOf(cmd(Awt.VK_UP, alt = true))),
    FOCUS_DOWN(ShortcutGroup.PANES, "Focus the pane below", listOf(cmd(Awt.VK_DOWN, alt = true))),
    CLEAR_SCREEN(ShortcutGroup.TERMINAL, "Clear the screen and the scrollback", listOf(cmd(Awt.VK_L))),
    FIND(ShortcutGroup.TERMINAL, "Find in the terminal", listOf(cmd(Awt.VK_F))),
    RENAME_TERMINAL(ShortcutGroup.PANES, "Rename the terminal", listOf(cmd(Awt.VK_F2))),
    RENAME_WORKSPACE(ShortcutGroup.PROJECTS, "Rename the workspace", listOf(cmd(Awt.VK_F2, shift = true))),

    /** Previous and next workspace, crossing projects. */
    WORKSPACE_PREVIOUS(ShortcutGroup.PROJECTS, "Previous workspace", listOf(cmd(Awt.VK_OPEN_BRACKET, shift = true))),
    WORKSPACE_NEXT(ShortcutGroup.PROJECTS, "Next workspace", listOf(cmd(Awt.VK_CLOSE_BRACKET, shift = true))),

    /** The workspace of the project that has the number of the key, 9 for the last one: see [shortcutNumber]. */
    WORKSPACE_NUMBER(ShortcutGroup.PROJECTS, "Workspace of the project by its number", listOf(cmd(Awt.VK_1)), takesDigit = true),

    /** The pane that has the number of the key, in the order they are drawn. */
    PANE_NUMBER(ShortcutGroup.PANES, "Pane by its number", listOf(cmd(Awt.VK_1, alt = true)), takesDigit = true),

    /** The previous and the next project, wrapping around. */
    PROJECT_PREVIOUS(ShortcutGroup.PROJECTS, "Previous project", listOf(cmd(Awt.VK_OPEN_BRACKET, alt = true))),
    PROJECT_NEXT(ShortcutGroup.PROJECTS, "Next project", listOf(cmd(Awt.VK_CLOSE_BRACKET, alt = true))),
    ;

    /** What is shown for it: the keys, as a Mac writes them, as the user bound it; nothing when it is unbound. */
    val label: String get() = Shortcuts.map.label(this)
}

/**
 * What [this] event is for the window, or `null` when it is not one of its shortcuts: a key going down whose modifiers are exactly those of the
 * binding of a shortcut (see [Shortcuts]).
 */
internal fun KeyEvent.appShortcut(): AppShortcut? {
    if (type != KeyEventType.KeyDown) return null
    return Shortcuts.map.lookup(key.nativeKeyCode, isMetaPressed, isCtrlPressed, isAltPressed, isShiftPressed)
}

/** The digit of the key of [this] event, from 1 to 9, or `null` when it is another key. */
internal fun KeyEvent.shortcutNumber(): Int? = when (key) {
    Key.One -> 1
    Key.Two -> 2
    Key.Three -> 3
    Key.Four -> 4
    Key.Five -> 5
    Key.Six -> 6
    Key.Seven -> 7
    Key.Eight -> 8
    Key.Nine -> 9
    else -> null
}

/**
 * Where the window's shortcuts are handled: on the window itself, which sees the keys before whatever has the focus, so that they work
 * when nothing does (the board, the welcome, a popup). The content of the window gives the handler, since it holds the state.
 */
class WindowKeys {
    @Volatile
    internal var handler: (KeyEvent) -> Boolean = { false }

    /** Handles [event], and says whether it was a shortcut. */
    fun handle(event: KeyEvent): Boolean = handler(event)
}

/**
 * The keys that a terminal handles itself, before the program sees them (see `Terminal`): the palette and the context menu show them next to the
 * same commands. They are not [AppShortcut]s, which are the window's; every one of them is also a command of the palette.
 */
internal object TerminalKeys {
    const val COPY = "⌘C"
    const val PASTE = "⌘V"
    const val SELECT_ALL = "⌘A"
    const val PREVIOUS_PROMPT = "⌘↑"
    const val NEXT_PROMPT = "⌘↓"
    const val SCROLL_PAGE_UP = "⇧PgUp"
    const val SCROLL_PAGE_DOWN = "⇧PgDn"
    const val SCROLL_TO_TOP = "⇧Home"
    const val SCROLL_TO_BOTTOM = "⇧End"
}

/**
 * What the two ways of splitting are called and shown with, in a layout: the main direction is right in the horizontal one and down in the
 * vertical one, and has the shortcut without Shift.
 */
internal class SplitLabels(val rightShortcut: String, val downShortcut: String) {
    companion object {
        fun of(vertical: Boolean) =
            if (vertical) SplitLabels(AppShortcut.SPLIT_OTHER.label, AppShortcut.SPLIT_MAIN.label)
            else SplitLabels(AppShortcut.SPLIT_MAIN.label, AppShortcut.SPLIT_OTHER.label)
    }
}
