package app.katty.terminal

public enum class TerminalMouseAction {
    PRESS,
    RELEASE,
    MOTION,
}

/**
 * A mouse button. The wheel is buttons, as the protocols have it: a notch of it is a press, and only that.
 */
public enum class TerminalMouseButton {
    LEFT,
    RIGHT,
    MIDDLE,
    WHEEL_UP,
    WHEEL_DOWN,
    WHEEL_LEFT,
    WHEEL_RIGHT,

    /** The button on the side of a mouse that goes back. */
    BACK,
    FORWARD,
}

/**
 * A mouse event, to send to the program running in the terminal if it asked to be told about the mouse.
 *
 * The position is in **cells**, from the top left of the screen, and fractional: `2.5` is the middle of the
 * third column. The session converts it with the size of a cell that it was given by [TerminalSession.resize],
 * which is what the program is told the size of a cell is, so that what it reads and what it is told agree.
 *
 * @param button The button that was pressed or released, or the one that is held for a motion, `null` for a
 * motion with no button held.
 * @param anyButtonPressed Whether a button is held, which is what a program that only asked for drags is told
 * motion for.
 */
public data class MouseInput(
    public val action: TerminalMouseAction,
    public val button: TerminalMouseButton?,
    public val column: Float,
    public val row: Float,
    public val mods: Set<TerminalModifier> = emptySet(),
    public val anyButtonPressed: Boolean = false,
)

/**
 * The shape of the mouse pointer that the program asked for (OSC 22), which are the W3C cursor names.
 */
public enum class TerminalPointerShape {
    DEFAULT,
    CONTEXT_MENU,
    HELP,
    POINTER,
    PROGRESS,
    WAIT,
    CELL,
    CROSSHAIR,
    TEXT,
    VERTICAL_TEXT,
    ALIAS,
    COPY,
    MOVE,
    NO_DROP,
    NOT_ALLOWED,
    GRAB,
    GRABBING,
    ALL_SCROLL,
    COL_RESIZE,
    ROW_RESIZE,
    N_RESIZE,
    E_RESIZE,
    S_RESIZE,
    W_RESIZE,
    NE_RESIZE,
    NW_RESIZE,
    SE_RESIZE,
    SW_RESIZE,
    EW_RESIZE,
    NS_RESIZE,
    NESW_RESIZE,
    NWSE_RESIZE,
    ZOOM_IN,
    ZOOM_OUT,
}
