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

/** What a program says it is doing, in a program status report (OSC 7501). */
public enum class GhosttyProgramState {
    IDLE,
    WORKING,
    DONE,
    BLOCKED,
    ERROR,

    /** Not a state: the report asks to remove the record with its id, and the ones beneath it. With no id, all of them. */
    CLEAR,
}

/** What a blocked program needs from the user. */
public enum class GhosttyProgramKind {
    NONE,
    PERMISSION,
    QUESTION,
    AUTH,
}

/**
 * A program status report (OSC 7501), as libghostty validated it. [progress] is from 0 to 100, `null` when the program did not say.
 * [id] is empty for the program itself, and a `/` makes a record the child of another. The strings are empty when the report left them out.
 */
public data class GhosttyProgramStatus(
    val state: GhosttyProgramState,
    val kind: GhosttyProgramKind,
    val progress: Int?,
    val id: String,
    val app: String,
    val title: String,
    val message: String,
)

/** A program asked to resize the window's text area (CSI 8 ; rows ; columns t): `null` for what it asked to keep. */
public data class GhosttyResizeRequest(val columns: Int?, val rows: Int?)
