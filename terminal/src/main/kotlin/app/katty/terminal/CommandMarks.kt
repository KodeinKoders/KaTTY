package app.katty.terminal

import app.katty.ghostty.GhosttyScreenPoint
import app.katty.ghostty.GhosttyTerminal
import app.katty.ghostty.GhosttyTrackedRef
import java.time.Instant

/**
 * What the shell said of the commands it ran (OSC 133): where each one starts and ends, when, and its exit code. libghostty keeps where the
 * prompts and the outputs are, but not the code nor the time, and has no list of them.
 *
 * Each command has tracked references, which follow their rows as the terminal scrolls, reflows and prunes:
 * - where it **starts**: the end of its prompt (mark B) and how many rows the prompt has above it. Not the start of the prompt (mark A): the
 *   shell sends A once for a prompt, and the prompt is drawn again, elsewhere, by what clears the screen (Ctrl-L, ⌘L), which sends B again
 *   (the scripts put it in `PS1`) and not A;
 * - where its **output** starts (mark C), which selects it;
 * - where it **ends** (mark D), the cursor then.
 *
 * A command is gone when its start is (pruned, or erased by clearing the screen), and there are at most [MAX_COMMANDS], the oldest going first:
 * a reference costs the terminal some work whenever it prunes or reflows.
 *
 * Everything is done under the session's lock, on the terminal that [terminal] is.
 */
internal class CommandMarks(
    private val terminal: GhosttyTerminal,
    private val clock: () -> Instant = Instant::now,
) {
    private class Command(
        val id: Long,
        val start: GhosttyTrackedRef,
        val startUp: Int,
        val output: GhosttyTrackedRef,
        val startedAt: Instant,
        // Whether `start` is where what is typed starts (mark B), which is where the command line is read from, once, after the write.
        var lineToRead: Boolean,
        var line: String? = null,
        var end: GhosttyTrackedRef? = null,
        var exitCode: Int? = null,
        var finishedAt: Instant? = null,
    ) {
        fun close() {
            start.close()
            output.close()
            end?.close()
        }
    }

    // The prompt that the shell is showing: where it starts (mark A), where the line that is typed starts (mark B, again at each time that the
    // prompt is drawn) and how many rows the prompt has above that, known at the first B.
    private var promptStart: GhosttyTrackedRef? = null
    private var inputStart: GhosttyTrackedRef? = null
    private var promptHeight: Int? = null

    private val commands = ArrayDeque<Command>()
    private var lastId = 0L

    /** The shell draws a (primary) prompt. A prompt that no command follows, such as an empty line, makes no mark. */
    fun promptStarted() {
        promptStart?.close()
        promptStart = terminal.trackCursor()
        inputStart?.close()
        inputStart = null
        promptHeight = null
    }

    /** The prompt ends, and what is typed starts: also when the prompt is drawn again, which does not send the start of it. */
    fun inputStarted() {
        val here = terminal.trackCursor()
        if (promptHeight == null) {
            val promptRow = promptStart?.let(terminal::screenPoint)?.y
            val inputRow = terminal.screenPoint(here)?.y
            promptHeight = if (promptRow != null && inputRow != null) (inputRow - promptRow).toInt().coerceAtLeast(0) else 0
            promptStart?.close()
            promptStart = null
        }
        inputStart?.close()
        inputStart = here
    }

    /** The command that was typed at the prompt starts to run: the output starts at the cursor. */
    fun commandStarted() {
        val input = inputStart?.takeIf { it.hasValue }
        val prompt = promptStart?.takeIf { it.hasValue }
        val (start, up) = when {
            input != null -> input to (promptHeight ?: 0)
            // A shell that has no mark B (fish): the start of the prompt.
            prompt != null -> prompt to 0
            // The prompt was drawn again with no mark after it was erased: the command line is the row above the output.
            else -> terminal.trackCursor() to 1
        }
        // What was handed over is the command's now, and the rest of the prompt's is of no use.
        if (start !== inputStart) inputStart?.close()
        if (start !== promptStart) promptStart?.close()
        inputStart = null
        promptStart = null
        promptHeight = null
        commands.addLast(Command(++lastId, start, up, terminal.trackCursor(), clock(), lineToRead = start === input))
        while (commands.size > MAX_COMMANDS) commands.removeFirst().close()
    }

    /**
     * Reads the line that each command that started was typed on: the text from where the prompt ended to the end of the row, and of the rows
     * it wraps onto, as far as the row above the one where the output started. Not in the callback of the mark, where libghostty has not finished
     * with the screen. A line that cannot be read is none.
     */
    fun readCommandLines() {
        for (command in commands) {
            if (!command.lineToRead) continue
            command.lineToRead = false
            command.line = readLine(command)
        }
    }

    private fun readLine(command: Command): String? {
        val from = terminal.screenPoint(command.start) ?: return null
        val to = terminal.screenPoint(command.output) ?: return null
        val last = if (to.x == 0 && to.y > from.y) to.y - 1 else to.y
        if (last < from.y) return null
        val text = try {
            terminal.readRows(from.y, last, unwrap = true, firstColumn = from.x)
        } catch (e: IllegalArgumentException) {
            return null
        }
        // The first line of it. A prompt on the right of the line (zsh's RPROMPT) is far from the command: after a run of blanks that no command has.
        val line = text.lineSequence().firstOrNull().orEmpty().split(RIGHT_PROMPT_GAP).first().trim()
        return line.take(MAX_LINE_CHARS).ifEmpty { null }
    }

    /** The command that ran ends, with the exit code that the shell gave, or none. */
    fun commandFinished(exitCode: Int?) {
        val command = commands.lastOrNull { it.finishedAt == null } ?: return
        command.end?.close()
        command.end = terminal.trackCursor()
        command.exitCode = exitCode
        command.finishedAt = clock()
    }

    /** Where the commands are, in the rows of the whole screen. The commands that are gone are forgotten. */
    fun spans(): List<CommandSpan> {
        val gone = commands.filter { !it.start.hasValue }
        if (gone.isNotEmpty()) {
            gone.forEach { it.close() }
            commands.removeAll(gone.toSet())
        }
        return commands.mapNotNull { command ->
            val start = terminal.screenPoint(command.start) ?: return@mapNotNull null
            val end = command.end?.let(terminal::screenPoint)
            CommandSpan(command.id, (start.y - command.startUp).coerceAtLeast(0), end, command.exitCode, command.startedAt, command.finishedAt, command.line)
        }
    }

    /** Where the output of the command [id] starts, or `null` when it is forgotten. */
    fun outputStart(id: Long): GhosttyTrackedRef? = commands.firstOrNull { it.id == id }?.output

    fun close() {
        promptStart?.close()
        promptStart = null
        inputStart?.close()
        inputStart = null
        commands.forEach { it.close() }
        commands.clear()
    }

    companion object {
        const val MAX_COMMANDS = 500
        const val MAX_LINE_CHARS = 256
        private val RIGHT_PROMPT_GAP = Regex(" {4,}")
    }
}

/**
 * Where a command is, in the rows of the whole screen (0 is the top of the history).
 *
 * @param startRow Its first row: the first of its prompt, which may be blank.
 * @param end Where the cursor was when it ended, or `null` while it runs.
 * @param command The line that it was typed on, `null` when it is not known (yet).
 */
internal class CommandSpan(
    val id: Long,
    val startRow: Long,
    val end: GhosttyScreenPoint?,
    val exitCode: Int?,
    val startedAt: Instant,
    val finishedAt: Instant?,
    val command: String? = null,
)

/**
 * The marks of the commands in the viewport: the rows from the start of each command to its end, cut to the viewport.
 *
 * @param offset The row at the top of the viewport, in the rows of the whole screen.
 * @param rows The rows of the viewport.
 * @param cursor Where the cursor is in the viewport (column and row), `null` when it is not in it (then a command that runs goes on below).
 */
internal fun commandMarks(spans: List<CommandSpan>, offset: Long, rows: List<TerminalRow>, cursor: Pair<Int, Int>?): List<TerminalCommandMark> {
    val height = rows.size
    return spans.mapNotNull { span ->
        val first = span.startRow - offset
        // The last row: the one the cursor was on when it ended, or the one above it when the cursor was at the start of an empty row (the
        // output ended with a newline). A command that runs goes down to the cursor.
        val last = when {
            span.end != null -> lastRow(span.end.x, span.end.y - offset, first)
            span.finishedAt != null -> first
            cursor != null -> lastRow(cursor.first, cursor.second.toLong(), first)
            else -> Long.MAX_VALUE
        }
        if (last < 0 || first >= height) return@mapNotNull null
        val startsAbove = first < 0
        val endsBelow = last >= height
        var top = if (startsAbove) 0 else first.toInt()
        val bottom = if (endsBelow) height - 1 else last.toInt()
        // The prompt may start with an empty line (a theme's), which is not the command's.
        if (!startsAbove) while (top < bottom && rows[top].isBlank()) top++
        TerminalCommandMark(span.id, top, bottom, span.exitCode, span.startedAt, span.finishedAt, startsAbove, endsBelow, span.command, span.startRow)
    }
}

private fun lastRow(column: Int, row: Long, first: Long): Long = if (column == 0 && row > first) row - 1 else row

private fun TerminalRow.isBlank(): Boolean = cells.all { it.text.isBlank() }
