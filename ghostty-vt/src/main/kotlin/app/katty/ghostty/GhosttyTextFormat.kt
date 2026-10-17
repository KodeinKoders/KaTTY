package app.katty.ghostty

/** How the text of a selection is written. */
public enum class GhosttyTextFormat {
    /** The characters only. */
    PLAIN,

    /** The characters with the escape sequences that give them their styles and colors: what a terminal that reads them would show. */
    VT,

    /** HTML, with the styles and colors of the cells. */
    HTML,
}
