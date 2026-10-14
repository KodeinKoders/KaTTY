package app.katty.terminal

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
            if (id.isEmpty()) return records.isNotEmpty().also { records.clear() }
            return records.keys.removeAll { it == id || it.startsWith("$id/") }
        }
        // Replaced as a whole, and last in the order of use.
        records.remove(id)
        records[id] = TerminalProgramStatus(id, app, state, kind, progress, clean(title), clean(message), clock())
        while (records.size > limit) records.remove(records.keys.first())
        return true
    }

    /** A new prompt starts: what was being worked on or waited for is over. */
    @Synchronized
    fun promptStarted(): Boolean = records.values.removeAll { it.state == TerminalProgramState.WORKING || it.state == TerminalProgramState.BLOCKED }

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

// What a program writes in its title and its message is shown to a person, so what changes the order of what they read, or what cannot be
// seen, is taken out: the direction marks, the zero width spaces and the joiners that hold no word together. The ones that emoji and some
// scripts need are kept.
private fun clean(text: String): String {
    if (text.none { isHidden(it) }) return text
    return text.filterNot { isHidden(it) }
}

private fun isHidden(c: Char): Boolean = when (c.code) {
    0x061C, 0x200B, 0x200E, 0x200F, in 0x202A..0x202E, in 0x2060..0x2064, in 0x2066..0x2069, 0xFEFF -> true
    else -> false
}
