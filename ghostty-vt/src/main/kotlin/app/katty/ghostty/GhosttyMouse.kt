package app.katty.ghostty

public enum class GhosttyMouseAction {
    PRESS,
    RELEASE,
    MOTION,
}

/**
 * A mouse button, with the numbers that the protocols give them: the wheel is the buttons four to seven.
 */
public enum class GhosttyMouseButton {
    UNKNOWN,
    LEFT,
    RIGHT,
    MIDDLE,
    WHEEL_UP,
    WHEEL_DOWN,
    WHEEL_LEFT,
    WHEEL_RIGHT,
    EIGHT,
    NINE,
    TEN,
    ELEVEN,
}

/**
 * A mouse event, to be encoded for the program running in the terminal.
 *
 * @param button `null` for a motion with no button held.
 * @param x The position in pixels from the top left of the screen.
 */
public data class GhosttyMouseEvent(
    public val action: GhosttyMouseAction,
    public val button: GhosttyMouseButton?,
    public val mods: GhosttyMods = GhosttyMods.NONE,
    public val x: Float,
    public val y: Float,
)

/**
 * The shape of the mouse pointer that a program asked for (OSC 22), which are the W3C cursor names.
 */
public enum class GhosttyMouseShape {
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
