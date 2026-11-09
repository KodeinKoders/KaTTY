package app.katty.terminals

import app.katty.core.KattyState
import app.katty.core.LineMatch
import app.katty.core.TerminalId
import app.katty.core.matchLines
import app.katty.terminal.TerminalSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.util.concurrent.ConcurrentHashMap

/** A terminal that a search of every terminal goes through, with the session that it reads. */
class SearchTarget(val id: TerminalId, val session: TerminalSession)

/** What was found in a terminal: its rows, the last first, and whether there were more than the search keeps of one terminal. */
data class TerminalMatches(val terminal: TerminalId, val matches: List<LineMatch>, val more: Boolean)

/**
 * Where a search of every terminal is: the [query], what was found so far, in the order of the terminals, whether it goes on ([searching]), and
 * whether it stopped at the most that it keeps ([capped]).
 */
data class AllTerminalsResults(
    val query: String = "",
    val terminals: List<TerminalMatches> = emptyList(),
    val searching: Boolean = false,
    val capped: Boolean = false,
) {
    val total: Int get() = terminals.sumOf { it.matches.size }
}

/**
 * Searches the history of every terminal for a text, which the search of a terminal (libghostty's) does not do for the ones that are not on the
 * screen. It reads the text of each one with [TerminalSession.readLines], from the bottom, in pieces of [chunkRows] rows (each piece holds the lock
 * that the program's output waits for, for a few milliseconds), and finds the text in it with [matchLines]: the last rows of each terminal first,
 * [perTerminal] of them at most, and [maxTotal] in all.
 *
 * It keeps what it read of each terminal while it lives, with the version of its content then ([TerminalSession.contentVersion]): the next query
 * reads again only the terminals that changed. It is made for one popup and let go of with it, and what it kept with it. What it reads is the screen
 * that is active (a full-screen program's, without the shell's history), one line for each row: a text that goes on onto the next row is not found.
 */
class AllTerminalsSearch(
    private val perTerminal: Int = MAX_PER_TERMINAL,
    private val maxTotal: Int = MAX_TOTAL,
    private val chunkRows: Int = CHUNK_ROWS,
) {
    // What was read of each terminal. A search that is cancelled may still be reading when the next one starts: they can share it.
    private val read = ConcurrentHashMap<TerminalId, ReadLines>()

    private class ReadLines(val session: TerminalSession, val version: Long, val firstRow: Long, val lines: List<String>)

    /** What is found of [query] in [targets], in their order: what was found so far after each terminal, then all of it. Nothing for an empty query. */
    fun search(query: String, targets: List<SearchTarget>): Flow<AllTerminalsResults> = flow {
        // What is not a terminal any more is not kept.
        read.keys.retainAll(targets.map { it.id }.toSet())
        if (query.isEmpty()) {
            emit(AllTerminalsResults(query))
            return@flow
        }
        emit(AllTerminalsResults(query, searching = true))
        val found = ArrayList<TerminalMatches>()
        var left = maxTotal
        for (target in targets) {
            if (left == 0) break
            currentCoroutineContext().ensureActive()
            val lines = linesOf(target)
            val keep = minOf(perTerminal, left)
            // One more than is kept says whether there are more.
            val matches = matchLines(lines.lines, lines.firstRow, query, keep + 1)
            if (matches.isEmpty()) continue
            found += TerminalMatches(target.id, matches.take(keep), more = matches.size > keep)
            left -= minOf(keep, matches.size)
            emit(AllTerminalsResults(query, found.toList(), searching = true))
        }
        emit(AllTerminalsResults(query, found.toList(), searching = false, capped = left == 0))
    }.flowOn(Dispatchers.IO)

    // The lines of [target]: the ones that were read, while its content has not changed since.
    private suspend fun linesOf(target: SearchTarget): ReadLines {
        val version = target.session.contentVersion()
        read[target.id]?.takeIf { it.session === target.session && it.version == version }?.let { return it }
        return readAll(target.session, version).also { read[target.id] = it }
    }

    // The whole history and screen of [session], read from the bottom in pieces. Rows that are gone meanwhile (⌘L) end it: what is above is not
    // read, and the rows that were are kept with where they start.
    private suspend fun readAll(session: TerminalSession, version: Long): ReadLines {
        val pieces = ArrayList<List<String>>()
        var last = session.rowCount() - 1
        var first = last + 1
        while (last >= 0) {
            currentCoroutineContext().ensureActive()
            val start = maxOf(0L, last - chunkRows + 1)
            val piece = try {
                session.readLines(start, last)
            } catch (_: IllegalArgumentException) {
                break
            }
            // A session that closed meanwhile reads nothing.
            if (piece.isEmpty()) break
            pieces += piece
            first = start
            last = start - 1
        }
        return ReadLines(session, version, first, pieces.asReversed().flatten())
    }

    companion object {
        /** The most rows that are kept of a terminal, and of all of them. */
        const val MAX_PER_TERMINAL: Int = 100
        const val MAX_TOTAL: Int = 1_000

        /** How many rows are read at once. */
        const val CHUNK_ROWS: Int = 500
    }
}

/** The order in which a search of every terminal lists them: the one that is selected first, then as the sidebar lists them. */
fun KattyState.searchOrder(): List<TerminalId> {
    val all = allTerminals().map { it.terminal.id }
    val selected = selection.terminal?.takeIf { it in all } ?: return all
    return listOf(selected) + (all - selected)
}
