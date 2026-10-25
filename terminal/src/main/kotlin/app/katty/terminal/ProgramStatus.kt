package app.katty.terminal

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** What a program says it is doing in a program status report (OSC 7501). */
public enum class TerminalProgramState {
    /** At rest, waiting for the user's next instruction. */
    IDLE,

    /** Running on its own. */
    WORKING,

    /** Finished a piece of work, and the result is ready for the user to look at. */
    DONE,

    /** Cannot continue until the user does something: [TerminalProgramStatus.kind] says what, and the message why. */
    BLOCKED,

    /** Failed and stopped. */
    ERROR,
}

/** What a blocked program needs from the user. */
public enum class TerminalProgramKind {
    /** The program did not say, or it is not blocked. */
    NONE,

    /** Approval to do something. */
    PERMISSION,

    /** An answer that the user has to type. */
    QUESTION,

    /** A login, a password, a token or another credential. */
    AUTH,
}

/**
 * What a program reports of itself or of a part of what it does (OSC 7501): a build, a deploy, a coding agent.
 *
 * @param id Which record it is: empty for the program itself, and `build/test` is a child of `build`.
 * @param app A stable name for the program that a machine can match on, the nearest ancestor's when the record has none.
 * @param progress From 0 to 100, `null` when the program did not say.
 * @param title A short label, empty when the program gave none. It is the program's text: do not read more in it than what it says.
 * @param message One line for people, empty when the program gave none. The program's text too.
 * @param updatedAt When it was reported, in milliseconds since the epoch.
 */
public data class TerminalProgramStatus(
    val id: String,
    val app: String,
    val state: TerminalProgramState,
    val kind: TerminalProgramKind,
    val progress: Int?,
    val title: String,
    val message: String,
    val updatedAt: Long,
)

/**
 * The records of the program status reports, kept as the specification asks: one for each id, replaced by the next report of the id; a
 * clear removes the record and the ones beneath it; `working` and `blocked` do not outlive the prompt that follows nor the program;
 * at most [limit], and the one that was updated the longest ago goes first.
 *
 * It is not told what a report means beyond that: the records are the program's word.
 */
internal class ProgramStatusStore(
    private val limit: Int = MAX_RECORDS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    // Raw, as reported, in the order of their last report: the first is the one that was updated the longest ago.
    private val records = LinkedHashMap<String, TerminalProgramStatus>()

    private val mutableState = MutableStateFlow<List<TerminalProgramStatus>>(emptyList())

    /**
     * The records as [snapshot] gives them, published by each change in the same lock as the change: a change on one thread (the reader) and
     * another (the host clearing what it showed) cannot publish what the other changed in the wrong order.
     */
    val state: StateFlow<List<TerminalProgramStatus>> get() = mutableState

    // Publishes the records when [changed]. Under the monitor.
    private fun published(changed: Boolean): Boolean {
        if (changed) mutableState.value = snapshot()
        return changed
    }

    /** Applies a report. Whether the records are now different is the result. */
    @Synchronized
    fun report(
        id: String,
        clear: Boolean,
        state: TerminalProgramState,
        kind: TerminalProgramKind,
        progress: Int?,
        app: String,
        title: String,
        message: String,
    ): Boolean {
        if (clear) {
            if (id.isEmpty()) return published(records.isNotEmpty().also { records.clear() })
            return published(records.keys.removeAll { it == id || it.startsWith("$id/") })
        }
        // Replaced as a whole, and last in the order of use.
        records.remove(id)
        records[id] = TerminalProgramStatus(id, app, state, kind, progress, clean(title), clean(message), clock())
        while (records.size > limit) records.remove(records.keys.first())
        return published(true)
    }

    /** A new prompt starts: what was being worked on or waited for is over. */
    @Synchronized
    fun promptStarted(): Boolean =
        published(records.values.removeAll { it.state == TerminalProgramState.WORKING || it.state == TerminalProgramState.BLOCKED })

    /** The program that was reporting exited: what it was working on or waiting for is over. */
    @Synchronized
    fun programExited(): Boolean = promptStarted()

    /** The records, by id (a record comes before the ones beneath it), with the app that a record without one takes from its nearest ancestor. */
    @Synchronized
    fun snapshot(): List<TerminalProgramStatus> = records.values.sortedBy { it.id }.map { record ->
        if (record.app.isNotEmpty()) record else record.copy(app = inheritedApp(record.id))
    }

    private fun inheritedApp(id: String): String {
        var parent = id
        while (parent.isNotEmpty()) {
            parent = parent.substringBeforeLast('/', missingDelimiterValue = "")
            records[parent]?.app?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        return ""
    }

    private companion object {
        const val MAX_RECORDS = 256
    }
}

// How long a title or a message may be: what is shown is a line, and a program could send megabytes.
private const val MAX_TEXT = 256

// What a program writes in its title and its message is shown to a person, on one line, so what changes the order of what they read, or what
// cannot be seen, is taken out: the direction marks, the zero width spaces and the joiners that hold no word together, the characters that
// draw nothing (the soft hyphen, the Hangul fillers…) and the controls. A line break is a space. The joiners that emoji and some scripts need
// are kept. What is past [MAX_TEXT] is cut, not in the middle of a character that takes two.
private fun clean(text: String): String {
    val cleaned = if (text.none { isHidden(it) || isBreak(it) }) text else buildString {
        for (c in text) {
            when {
                isBreak(c) -> append(' ')
                !isHidden(c) -> append(c)
            }
        }
    }
    if (cleaned.length <= MAX_TEXT) return cleaned
    val end = if (cleaned[MAX_TEXT - 1].isHighSurrogate()) MAX_TEXT - 1 else MAX_TEXT
    return cleaned.substring(0, end)
}

private fun isBreak(c: Char): Boolean = c == '\n' || c == '\r' || c.code == 0x85 || c.code == 0x2028 || c.code == 0x2029

private fun isHidden(c: Char): Boolean = when (c.code) {
    in 0x00..0x1F, 0x7F, in 0x80..0x9F -> !isBreak(c)
    0x00AD, 0x034F, 0x061C, 0x115F, 0x1160, 0x180E, 0x200B, 0x200E, 0x200F, in 0x202A..0x202E, in 0x2060..0x2064, in 0x2066..0x2069, 0x3164,
    0xFEFF, 0xFFA0 -> true
    else -> false
}
