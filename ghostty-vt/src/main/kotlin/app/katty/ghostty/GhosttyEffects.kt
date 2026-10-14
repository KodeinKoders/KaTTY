package app.katty.ghostty

// What the terminal tells its embedder, besides the screen: the events of the escape sequences that are not drawn.

/** Where a program asked to put text with OSC 52, or to read it from. */
public enum class GhosttyClipboardLocation {
    /** The system clipboard (`c`). */
    STANDARD,

    /** The selection (`s`). */
    SELECTION,

    /** The primary selection (`p`). */
    PRIMARY,
}

public enum class GhosttyProgressState {
    REMOVE,
    SET,
    ERROR,
    INDETERMINATE,
    PAUSE,
}

/** A progress report (OSC 9;4). [percent] is from 0 to 100, `null` when the program did not say. */
public data class GhosttyProgress(val state: GhosttyProgressState, val percent: Int?)

public enum class GhosttyPromptEventKind {
    /** The shell starts to draw its prompt (OSC 133;A). */
    PROMPT_START,

    /** The prompt is drawn: what follows is what the user types (OSC 133;B). */
    INPUT_START,

    /** The user ran the command: what follows is its output (OSC 133;C). */
    OUTPUT_START,

    /** The command ended (OSC 133;D). */
    COMMAND_END,
}

/**
 * A step that the shell reported with OSC 133.
 *
 * @param exitCode Of the command, for [GhosttyPromptEventKind.COMMAND_END] when the shell said it.
 * @param command The command line, for [GhosttyPromptEventKind.OUTPUT_START] when the shell said it, else empty.
 */
public data class GhosttySemanticPrompt(val kind: GhosttyPromptEventKind, val exitCode: Int?, val command: String)
