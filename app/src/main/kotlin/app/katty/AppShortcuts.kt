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

// A binding of a Mac, with Command, which every shortcut of the window has there, and the other modifiers that are asked for.
private fun cmd(key: Int, ctrl: Boolean = false, alt: Boolean = false, shift: Boolean = false) = KeyBinding(key, meta = true, ctrl = ctrl, alt = alt, shift = shift)

// The bindings of a PC: Control and Shift for what has Command alone on a Mac, Alt and Shift for the second family, and Control or Alt alone for
// the few that a terminal has no use of (the size of the font, the pages, the arrows of the panes), as Windows Terminal does. Never Control and
// Alt, which is AltGr.
private fun ctrlShift(key: Int) = KeyBinding(key, meta = false, ctrl = true, shift = true)
private fun altShift(key: Int) = KeyBinding(key, meta = false, alt = true, shift = true)
private fun ctrl(key: Int, shift: Boolean = false) = KeyBinding(key, meta = false, ctrl = true, shift = shift)
private fun alt(key: Int) = KeyBinding(key, meta = false, alt = true)

/**
 * The shortcuts of the window: all of them with Command on a Mac, which a program in a terminal never sees, and with Control and Shift or Alt and
 * Shift on a PC (see [KeyboardStyle]). They are the single source of what the
 * menus and the palette show next to a command ([label], which follows what the user bound it to) and of what the window does when the keys are
 * pressed ([appShortcut]).
 *
 * @param group Where the dialog of the shortcuts lists it, and [title] what it calls it.
 * @param macDefaults What it is bound to on a Mac unless the user changed it: the first is what is shown, the others are keys that mean the same
 * without being shown (the plus is the equal sign with Shift, and the numeric keypad has its own).
 * @param pcDefaults The same on a PC (Windows, Linux): none for a shortcut whose keys have no equivalent there that is free.
 * @param takesDigit Whether it is the digits 1 to 9 that it is bound to, with the modifiers of its binding (a workspace, a pane by its number).
 */
internal enum class AppShortcut(
    val group: ShortcutGroup,
    val title: String,
    private val macDefaults: List<KeyBinding>,
    private val pcDefaults: List<KeyBinding>,
    val takesDigit: Boolean = false,
) {
    ADD_PROJECT(ShortcutGroup.PROJECTS, "Add a project folder", listOf(cmd(Awt.VK_O)), listOf(ctrlShift(Awt.VK_O))),
    NEW_WORKSPACE(ShortcutGroup.PROJECTS, "New workspace", listOf(cmd(Awt.VK_N)), listOf(ctrlShift(Awt.VK_N))),
    OPEN_RECENT(ShortcutGroup.PROJECTS, "Open a recent project", listOf(cmd(Awt.VK_O, shift = true)), listOf(altShift(Awt.VK_O))),
    CLOSE_WORKSPACE(ShortcutGroup.PROJECTS, "Close the workspace", listOf(cmd(Awt.VK_W, shift = true)), listOf(altShift(Awt.VK_W))),

    /** Removes the selected project, which asks first: it comes back as it was from the recent projects. */
    REMOVE_PROJECT(ShortcutGroup.PROJECTS, "Remove the project", listOf(cmd(Awt.VK_W, alt = true)), emptyList()),

    /** Splits in the main direction of the layout: right in the horizontal one, down in the vertical one. */
    SPLIT_MAIN(ShortcutGroup.PANES, "Split in the main direction of the layout", listOf(cmd(Awt.VK_D)), listOf(ctrlShift(Awt.VK_D))),

    /** Splits across the main direction. */
    SPLIT_OTHER(ShortcutGroup.PANES, "Split across the main direction", listOf(cmd(Awt.VK_D, shift = true)), listOf(altShift(Awt.VK_D))),
    CLOSE_TERMINAL(ShortcutGroup.PANES, "Close the terminal", listOf(cmd(Awt.VK_W)), listOf(ctrlShift(Awt.VK_W))),
    RESTART_TERMINAL(ShortcutGroup.PANES, "Restart the terminal", listOf(cmd(Awt.VK_L, shift = true)), listOf(altShift(Awt.VK_L))),

    /** Asks for a command to run in the run terminal of the workspace, over its panes: see `RunTerminals`. */
    RUN(ShortcutGroup.RUN, "Run a command in the workspace", listOf(cmd(Awt.VK_R)), listOf(ctrlShift(Awt.VK_R))),

    /**
     * Shows the run terminal of the workspace over its panes, or minimizes it into the header of the workspace. While it is shown, the shortcuts that
     * act on a terminal act on it: see `WindowController.handleOnRun`.
     */
    RUN_TOGGLE(ShortcutGroup.RUN, "Show or minimize the run terminal", listOf(cmd(Awt.VK_R, shift = true)), listOf(altShift(Awt.VK_R))),

    /** Opens what was closed last again: a terminal at its place, or a whole workspace. */
    REOPEN_CLOSED(ShortcutGroup.PANES, "Reopen the last closed terminal or workspace", listOf(cmd(Awt.VK_T, shift = true)), listOf(ctrlShift(Awt.VK_T))),
    /** Goes to the next terminal that needs the user, one that waits or one that finished: the name is the one that the user's bindings are kept by. */
    JUMP_TO_WAITING(ShortcutGroup.AGENTS, "Jump to the next terminal that needs you", listOf(cmd(Awt.VK_J, shift = true)), listOf(altShift(Awt.VK_J))),

    /** Opens the list of the agents that need the user, and closes it. */
    ATTENTION(ShortcutGroup.AGENTS, "Show the agents that need you", listOf(cmd(Awt.VK_J)), listOf(ctrlShift(Awt.VK_J))),

    /** Shows the agent board in place of the terminals, and the terminals in place of the board. */
    BOARD(ShortcutGroup.AGENTS, "Switch between the board and the terminals", listOf(cmd(Awt.VK_B, shift = true)), listOf(altShift(Awt.VK_B))),

    /** Shows the sidebar of the projects, or folds it into the rail. */
    SIDEBAR(ShortcutGroup.AGENTS, "Show the project sidebar, or fold it", listOf(cmd(Awt.VK_B)), listOf(ctrlShift(Awt.VK_B))),

    /** Maximizes the pane over its workspace, or puts it back. */
    MAXIMIZE(ShortcutGroup.PANES, "Maximize the pane, or put it back", listOf(cmd(Awt.VK_ENTER, shift = true)), listOf(ctrlShift(Awt.VK_ENTER))),

    /** Move the nearest divider of the pane's splits that is along the arrow. */
    RESIZE_LEFT(ShortcutGroup.PANES, "Move the divider to the left", listOf(cmd(Awt.VK_LEFT, alt = true)), listOf(altShift(Awt.VK_LEFT))),
    RESIZE_RIGHT(ShortcutGroup.PANES, "Move the divider to the right", listOf(cmd(Awt.VK_RIGHT, alt = true)), listOf(altShift(Awt.VK_RIGHT))),
    RESIZE_UP(ShortcutGroup.PANES, "Move the divider up", listOf(cmd(Awt.VK_UP, alt = true)), listOf(altShift(Awt.VK_UP))),
    RESIZE_DOWN(ShortcutGroup.PANES, "Move the divider down", listOf(cmd(Awt.VK_DOWN, alt = true)), listOf(altShift(Awt.VK_DOWN))),

    /** The next and the previous match of the search that is open. */
    FIND_NEXT(ShortcutGroup.TERMINAL, "Next match of the search", listOf(cmd(Awt.VK_G)), listOf(ctrlShift(Awt.VK_G))),
    FIND_PREVIOUS(ShortcutGroup.TERMINAL, "Previous match of the search", listOf(cmd(Awt.VK_G, shift = true)), listOf(altShift(Awt.VK_G))),
    FONT_BIGGER(
        ShortcutGroup.LOOK, "Bigger terminal font",
        listOf(
            cmd(Awt.VK_EQUALS), cmd(Awt.VK_EQUALS, shift = true), cmd(Awt.VK_PLUS), cmd(Awt.VK_PLUS, shift = true),
            cmd(Awt.VK_ADD), cmd(Awt.VK_ADD, shift = true),
        ),
        listOf(ctrl(Awt.VK_EQUALS), ctrl(Awt.VK_EQUALS, shift = true), ctrl(Awt.VK_PLUS), ctrl(Awt.VK_PLUS, shift = true), ctrl(Awt.VK_ADD)),
    ),
    FONT_SMALLER(ShortcutGroup.LOOK, "Smaller terminal font", listOf(cmd(Awt.VK_MINUS), cmd(Awt.VK_SUBTRACT)), listOf(ctrl(Awt.VK_MINUS), ctrl(Awt.VK_SUBTRACT))),
    FONT_RESET(ShortcutGroup.LOOK, "Default terminal font size", listOf(cmd(Awt.VK_0), cmd(Awt.VK_NUMPAD0)), listOf(ctrl(Awt.VK_0), ctrl(Awt.VK_NUMPAD0))),

    /** Opens the settings window, or brings it to the front. */
    SETTINGS(ShortcutGroup.LOOK, "Open the settings", listOf(cmd(Awt.VK_COMMA)), listOf(ctrl(Awt.VK_COMMA))),
    PALETTE(ShortcutGroup.LOOK, "Open the command palette", listOf(cmd(Awt.VK_K)), listOf(ctrlShift(Awt.VK_P), ctrlShift(Awt.VK_K))),

    /** Shows only the terminals, in a bigger font, with the keys that are pressed: for a demo. Starts it, and stops it. */
    PRESENTATION(ShortcutGroup.LOOK, "Start or stop presentation mode", listOf(cmd(Awt.VK_P, alt = true)), listOf(altShift(Awt.VK_P))),

    /** Shows the keys that are pressed, as presentation mode does, and changes nothing else of the window: for teaching. Starts it, and stops it. */
    TEACH(ShortcutGroup.LOOK, "Start or stop teach mode", listOf(cmd(Awt.VK_T, alt = true)), listOf(altShift(Awt.VK_T))),
    FOCUS_LEFT(ShortcutGroup.PANES, "Focus the pane on the left", listOf(cmd(Awt.VK_LEFT, shift = true)), listOf(alt(Awt.VK_LEFT))),
    FOCUS_RIGHT(ShortcutGroup.PANES, "Focus the pane on the right", listOf(cmd(Awt.VK_RIGHT, shift = true)), listOf(alt(Awt.VK_RIGHT))),
    FOCUS_UP(ShortcutGroup.PANES, "Focus the pane above", listOf(cmd(Awt.VK_UP, shift = true)), listOf(alt(Awt.VK_UP))),
    FOCUS_DOWN(ShortcutGroup.PANES, "Focus the pane below", listOf(cmd(Awt.VK_DOWN, shift = true)), listOf(alt(Awt.VK_DOWN))),
    CLEAR_SCREEN(ShortcutGroup.TERMINAL, "Clear the screen and the scrollback", listOf(cmd(Awt.VK_L)), listOf(ctrlShift(Awt.VK_L))),
    FIND(ShortcutGroup.TERMINAL, "Find in the terminal", listOf(cmd(Awt.VK_F)), listOf(ctrlShift(Awt.VK_F))),

    /** Opens the search of every terminal's history, and closes it. */
    SEARCH_ALL(ShortcutGroup.TERMINAL, "Search in all terminals", listOf(cmd(Awt.VK_F, shift = true)), listOf(altShift(Awt.VK_F))),
    ASK_AI_COMMAND(ShortcutGroup.TERMINAL, "Ask AI for a command", listOf(cmd(Awt.VK_I)), listOf(ctrlShift(Awt.VK_I))),
    RENAME_TERMINAL(ShortcutGroup.PANES, "Rename the terminal", listOf(cmd(Awt.VK_F2)), listOf(ctrlShift(Awt.VK_F2))),
    RENAME_WORKSPACE(ShortcutGroup.PROJECTS, "Rename the workspace", listOf(cmd(Awt.VK_F2, shift = true)), listOf(altShift(Awt.VK_F2))),

    /** Previous and next workspace, crossing projects. */
    WORKSPACE_PREVIOUS(ShortcutGroup.PROJECTS, "Previous workspace", listOf(cmd(Awt.VK_OPEN_BRACKET, shift = true)), listOf(ctrl(Awt.VK_PAGE_UP))),
    WORKSPACE_NEXT(ShortcutGroup.PROJECTS, "Next workspace", listOf(cmd(Awt.VK_CLOSE_BRACKET, shift = true)), listOf(ctrl(Awt.VK_PAGE_DOWN))),

    /** The workspace of the project that has the number of the key, 9 for the last one: see [shortcutNumber]. */
    WORKSPACE_NUMBER(ShortcutGroup.PROJECTS, "Workspace of the project by its number", listOf(cmd(Awt.VK_1)), listOf(alt(Awt.VK_1)), takesDigit = true),

    /** The pane that has the number of the key, in the order they are drawn. */
    PANE_NUMBER(ShortcutGroup.PANES, "Pane by its number", listOf(cmd(Awt.VK_1, alt = true)), listOf(altShift(Awt.VK_1)), takesDigit = true),

    /** The previous and the next project, wrapping around. */
    PROJECT_PREVIOUS(ShortcutGroup.PROJECTS, "Previous project", listOf(cmd(Awt.VK_OPEN_BRACKET, alt = true)), listOf(ctrlShift(Awt.VK_PAGE_UP))),
    PROJECT_NEXT(ShortcutGroup.PROJECTS, "Next project", listOf(cmd(Awt.VK_CLOSE_BRACKET, alt = true)), listOf(ctrlShift(Awt.VK_PAGE_DOWN))),
    ;

    /** What it is bound to unless the user changed it, on the keyboard of [style]. */
    fun defaults(style: KeyboardStyle): List<KeyBinding> = if (style == KeyboardStyle.MAC) macDefaults else pcDefaults

    /** What it is bound to unless the user changed it, on the keyboard in use. */
    val defaults: List<KeyBinding> get() = defaults(KeyboardStyle.inUse)

    /** What is shown for it: the keys, as the keyboard of the system writes them, as the user bound it; nothing when it is unbound. */
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
    /** The keys that copy, paste, select all and jump between prompts: Command and the key on a Mac, Control and Shift and the key on a PC. */
    fun own(keyCode: Int, style: KeyboardStyle = KeyboardStyle.inUse): KeyBinding =
        if (style == KeyboardStyle.MAC) KeyBinding(keyCode, meta = true) else KeyBinding(keyCode, meta = false, ctrl = true, shift = true)

    private fun scroll(keyCode: Int) = KeyBinding(keyCode, meta = false, shift = true).label

    val COPY: String get() = own(Awt.VK_C).label
    val PASTE: String get() = own(Awt.VK_V).label
    val SELECT_ALL: String get() = own(Awt.VK_A).label
    val PREVIOUS_PROMPT: String get() = own(Awt.VK_UP).label
    val NEXT_PROMPT: String get() = own(Awt.VK_DOWN).label
    val SCROLL_PAGE_UP: String get() = scroll(Awt.VK_PAGE_UP)
    val SCROLL_PAGE_DOWN: String get() = scroll(Awt.VK_PAGE_DOWN)
    val SCROLL_TO_TOP: String get() = scroll(Awt.VK_HOME)
    val SCROLL_TO_BOTTOM: String get() = scroll(Awt.VK_END)
}

/**
 * Whether the modifier of the keys that the windows handle themselves (closing a window that has no terminal, confirming a dialog, running a command
 * minimized) is down, alone of Command and Control: Command on a Mac, Control on a PC, where no terminal has the keys there.
 */
internal fun KeyEvent.isCommandPressed(style: KeyboardStyle = KeyboardStyle.inUse): Boolean =
    if (style == KeyboardStyle.MAC) isMetaPressed && !isCtrlPressed else isCtrlPressed && !isMetaPressed

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

/**
 * Whether [this] key is one of the window's or the terminal's own and not one for the program: anything with Command on a Mac; on a PC, Control and
 * Shift (the terminal's copy and paste, and most shortcuts), or the keys of a shortcut of the window (Alt and an arrow, Ctrl+PgUp…).
 */
internal fun KeyEvent.isOwnKey(style: KeyboardStyle = KeyboardStyle.inUse): Boolean =
    if (style == KeyboardStyle.MAC) isMetaPressed
    else (isCtrlPressed && isShiftPressed) || Shortcuts.map.lookup(key.nativeKeyCode, isMetaPressed, isCtrlPressed, isAltPressed, isShiftPressed) != null

/** The modifier that is held to click a link in a terminal, as the keyboard writes it: `⌘` on a Mac, `Ctrl` on a PC. */
internal fun linkModifierName(style: KeyboardStyle = KeyboardStyle.inUse): String = if (style == KeyboardStyle.MAC) "⌘" else "Ctrl"
