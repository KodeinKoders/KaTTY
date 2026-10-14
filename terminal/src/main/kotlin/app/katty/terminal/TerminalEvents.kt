package app.katty.terminal

/** Where a program asked to put text with OSC 52. */
public enum class TerminalClipboardLocation {
    /** The system clipboard. */
    STANDARD,

    /** The selection. */
    SELECTION,

    /** The primary selection: what a middle click pastes. */
    PRIMARY,
}

/**
 * What happens in a session that is not on the screen, and that the host may want to show or do something about.
 * They are published by [TerminalSession.events]; the title, the working directory and the progress are state, so they have
 * their own flows.
 */
public sealed interface TerminalEvent {
    /** A program wrote a BEL character. */
    public data object Bell : TerminalEvent

    /** A program asked for a desktop notification (OSC 9 or 777). The title is empty when it gave none. */
    public data class Notification(val title: String, val body: String) : TerminalEvent

    /**
     * A program asked to put [text] in the clipboard (OSC 52), and the session allowed it. [text] is empty to clear it.
     * `Terminal` puts it there, and the host that does not use it decides.
     */
    public data class ClipboardWrite(val location: TerminalClipboardLocation, val text: String) : TerminalEvent

    /** The shell is drawing its prompt: the previous command is over, and it is waiting for the next one. */
    public data object PromptStarted : TerminalEvent

    /** The user ran a command. [command] is its line when the shell said it. */
    public data class CommandStarted(val command: String?) : TerminalEvent

    /** The command ended. [exitCode] is its exit code when the shell said it. */
    public data class CommandFinished(val exitCode: Int?) : TerminalEvent

    /**
     * A program asked to resize the window's text area (CSI 8 ; rows ; columns t), to [columns] by [rows] cells, `null` for the one it
     * asked to keep. Nothing was resized: the host decides, and if it does it resizes its window, and the terminal follows it. Any program
     * can ask, so letting it is the host's choice to offer.
     */
    public data class ResizeRequested(val columns: Int?, val rows: Int?) : TerminalEvent
}

public enum class TerminalProgressState {
    SET,
    ERROR,
    INDETERMINATE,
    PAUSE,
}

/** What a program reports of its progress (OSC 9;4). [percent] is from 0 to 100, `null` when it did not say. */
public data class TerminalProgress(val state: TerminalProgressState, val percent: Int?)

/**
 * A link in the screen: an address that a program marked (OSC 8) or that is written in the text.
 *
 * @param segments Where it is: one for each row of the viewport that it is on, from the top (a link that goes on onto the next row,
 * which its line was too long for, has several). They are never empty.
 */
public data class TerminalLink(val url: String, val segments: List<TerminalLinkSegment>) {
    /** A link that is on one row. */
    public constructor(url: String, row: Int, columns: IntRange) : this(url, listOf(TerminalLinkSegment(row, columns)))

    /** Whether the cell at [column] and [row] is part of it. */
    public fun contains(column: Int, row: Int): Boolean = segments.any { it.row == row && column in it.columns }
}

/**
 * The part of a link that is on one [row] of the viewport.
 *
 * @param columns The cells it covers on that row, both ends included.
 */
public data class TerminalLinkSegment(val row: Int, val columns: IntRange)

/**
 * The directory that the shell reported (OSC 7), as a path: a `file://host/path` URI is turned into its path, with the
 * percent-encoding removed, and a bare path is kept. `null` when there is none.
 */
internal fun decodeWorkingDirectory(reported: String): String? {
    if (reported.isEmpty()) return null
    if (!reported.startsWith("file://")) return reported
    // After the host, which is empty or the machine's name.
    val path = reported.removePrefix("file://").let { rest -> rest.indexOf('/').let { if (it < 0) "" else rest.substring(it) } }
    if (path.isEmpty()) return null
    return percentDecode(path)
}

private fun percentDecode(text: String): String {
    if ('%' !in text) return text
    val bytes = java.io.ByteArrayOutputStream()
    var i = 0
    while (i < text.length) {
        val c = text[i]
        val value = if (c == '%' && i + 2 < text.length) text.substring(i + 1, i + 3).toIntOrNull(16) else null
        if (value != null) {
            bytes.write(value)
            i += 3
        } else {
            bytes.write(c.toString().toByteArray())
            i++
        }
    }
    return bytes.toString(Charsets.UTF_8)
}
