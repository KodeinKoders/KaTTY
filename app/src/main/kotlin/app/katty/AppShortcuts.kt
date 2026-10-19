package app.katty

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type

/**
 * The shortcuts of the window: all of them with Command, which a program in a terminal never sees. They are the single source of what the
 * menus and the palette show next to a command ([label]) and of what the window does when the keys are pressed ([appShortcut]).
 */
internal enum class AppShortcut(private val text: String) {
    ADD_PROJECT("⌘O"),
    NEW_WORKSPACE("⌘N"),
    CLOSE_WORKSPACE("⌘⇧W"),

    /** Splits in the main direction of the layout: right in the horizontal one, down in the vertical one. */
    SPLIT_MAIN("⌘D"),

    /** Splits across the main direction. */
    SPLIT_OTHER("⌘⇧D"),
    CLOSE_TERMINAL("⌘W"),
    RESTART_TERMINAL("⌘R"),
    JUMP_TO_WAITING("⌘⇧J"),

    /** Opens the list of the agents that need the user, and closes it. */
    ATTENTION("⌘J"),

    /** Shows the agent board in place of the terminals, and the terminals in place of the board. */
    BOARD("⌘⇧B"),

    /** Shows the sidebar of the projects, or folds it into the rail. */
    SIDEBAR("⌘B"),

    /** Maximizes the pane over its workspace, or puts it back. */
    MAXIMIZE("⌘⇧↩"),

    /** Move the nearest divider of the pane's splits that is along the arrow. */
    RESIZE_LEFT("⌘⌃←"),
    RESIZE_RIGHT("⌘⌃→"),
    RESIZE_UP("⌘⌃↑"),
    RESIZE_DOWN("⌘⌃↓"),

    /** The next and the previous match of the search that is open. */
    FIND_NEXT("⌘G"),
    FIND_PREVIOUS("⌘⇧G"),
    FONT_BIGGER("⌘="),
    FONT_SMALLER("⌘-"),
    FONT_RESET("⌘0"),

    /** Opens the main menu, and closes it. */
    MENU("⌘,"),
    PALETTE("⌘K"),
    FOCUS_LEFT("⌘⌥←"),
    FOCUS_RIGHT("⌘⌥→"),
    FOCUS_UP("⌘⌥↑"),
    FOCUS_DOWN("⌘⌥↓"),
    CLEAR_SCREEN("⌘L"),
    FIND("⌘F"),
    RENAME_TERMINAL("⌘F2"),
    RENAME_WORKSPACE("⌘⇧F2"),

    /** Previous workspace, in the layout that lists them from top to bottom (the sidebar). */
    WORKSPACE_UP("⌘⌥⇧↑"),
    WORKSPACE_DOWN("⌘⌥⇧↓"),

    /** Previous and next workspace, in the layout that lists them from left to right (the tabs). */
    WORKSPACE_LEFT("⌘⌥⇧←"),
    WORKSPACE_RIGHT("⌘⌥⇧→"),

    /** Previous and next workspace, whatever the layout. */
    WORKSPACE_PREVIOUS("⌘⇧["),
    WORKSPACE_NEXT("⌘⇧]"),

    /** The workspace of the project that has the number of the key, 9 for the last one: see [shortcutNumber]. */
    WORKSPACE_NUMBER("⌘1…9"),

    /** The pane that has the number of the key, in the order they are drawn. */
    PANE_NUMBER("⌘⌥1…9"),

    /** The previous and the next project, wrapping around. */
    PROJECT_PREVIOUS("⌘⌥["),
    PROJECT_NEXT("⌘⌥]"),
    ;

    /** What is shown for it: the keys, as a Mac writes them. */
    val label: String get() = text
}

/**
 * What [this] event is for the window, or `null` when it is not one of its shortcuts. Only a key going down with Command is one; with Option
 * only the arrows are (the neighbouring pane), and the ones with Shift are not the ones without it.
 */
internal fun KeyEvent.appShortcut(): AppShortcut? {
    if (type != KeyEventType.KeyDown || !isMetaPressed) return null
    if (isCtrlPressed) {
        // Command and Control and an arrow: the nearest divider moves. Nothing else with Control is the window's (⌘⌃F is the system's full screen).
        if (isShiftPressed || isAltPressed) return null
        return when (key) {
            Key.DirectionLeft -> AppShortcut.RESIZE_LEFT
            Key.DirectionRight -> AppShortcut.RESIZE_RIGHT
            Key.DirectionUp -> AppShortcut.RESIZE_UP
            Key.DirectionDown -> AppShortcut.RESIZE_DOWN
            else -> null
        }
    }
    if (isAltPressed) {
        // Command and Option and an arrow: the neighbouring pane. With Shift too, the previous or the next workspace.
        if (isShiftPressed) {
            return when (key) {
                Key.DirectionLeft -> AppShortcut.WORKSPACE_LEFT
                Key.DirectionRight -> AppShortcut.WORKSPACE_RIGHT
                Key.DirectionUp -> AppShortcut.WORKSPACE_UP
                Key.DirectionDown -> AppShortcut.WORKSPACE_DOWN
                else -> null
            }
        }
        return when (key) {
            Key.DirectionLeft -> AppShortcut.FOCUS_LEFT
            Key.DirectionRight -> AppShortcut.FOCUS_RIGHT
            Key.DirectionUp -> AppShortcut.FOCUS_UP
            Key.DirectionDown -> AppShortcut.FOCUS_DOWN
            Key.LeftBracket -> AppShortcut.PROJECT_PREVIOUS
            Key.RightBracket -> AppShortcut.PROJECT_NEXT
            else -> if (shortcutNumber() != null) AppShortcut.PANE_NUMBER else null
        }
    }
    return when {
        key == Key.F2 -> if (isShiftPressed) AppShortcut.RENAME_WORKSPACE else AppShortcut.RENAME_TERMINAL
        key == Key.O && !isShiftPressed -> AppShortcut.ADD_PROJECT
        key == Key.N && !isShiftPressed -> AppShortcut.NEW_WORKSPACE
        key == Key.W && isShiftPressed -> AppShortcut.CLOSE_WORKSPACE
        key == Key.J && isShiftPressed -> AppShortcut.JUMP_TO_WAITING
        key == Key.J -> AppShortcut.ATTENTION
        (key == Key.Enter || key == Key.NumPadEnter) && isShiftPressed -> AppShortcut.MAXIMIZE
        key == Key.G && isShiftPressed -> AppShortcut.FIND_PREVIOUS
        key == Key.G -> AppShortcut.FIND_NEXT
        // The plus is the key of the equal sign with Shift: either way it is bigger.
        key == Key.Equals || key == Key.Plus || key == Key.NumPadAdd -> AppShortcut.FONT_BIGGER
        (key == Key.Minus || key == Key.NumPadSubtract) && !isShiftPressed -> AppShortcut.FONT_SMALLER
        key == Key.Zero && !isShiftPressed -> AppShortcut.FONT_RESET
        key == Key.Comma && !isShiftPressed -> AppShortcut.MENU
        key == Key.B && isShiftPressed -> AppShortcut.BOARD
        key == Key.B -> AppShortcut.SIDEBAR
        key == Key.K && !isShiftPressed -> AppShortcut.PALETTE
        key == Key.W -> AppShortcut.CLOSE_TERMINAL
        key == Key.R && !isShiftPressed -> AppShortcut.RESTART_TERMINAL
        key == Key.L && !isShiftPressed -> AppShortcut.CLEAR_SCREEN
        key == Key.F && !isShiftPressed -> AppShortcut.FIND
        key == Key.LeftBracket && isShiftPressed -> AppShortcut.WORKSPACE_PREVIOUS
        key == Key.RightBracket && isShiftPressed -> AppShortcut.WORKSPACE_NEXT
        !isShiftPressed && shortcutNumber() != null -> AppShortcut.WORKSPACE_NUMBER
        key == Key.D && isShiftPressed -> AppShortcut.SPLIT_OTHER
        key == Key.D -> AppShortcut.SPLIT_MAIN
        else -> null
    }
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
