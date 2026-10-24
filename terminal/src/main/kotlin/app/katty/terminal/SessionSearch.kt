package app.katty.terminal

import app.katty.ghostty.GhosttyScrollViewport
import app.katty.ghostty.GhosttyScrollbar
import app.katty.ghostty.GhosttySearch
import app.katty.ghostty.GhosttySearchStatus
import app.katty.ghostty.GhosttyTerminal
import app.katty.ghostty.GhosttyViewportMatch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.time.Duration.Companion.nanoseconds

// The least time between two looks for the matches of a regular expression, which reads the last rows of the history.
private const val REGEX_REFRESH_NS = 200_000_000L

// How long a look for the matches of a regular expression may take, and what the search says when it took longer.
private const val REGEX_BUDGET_NS = 250_000_000L
private const val REGEX_TOO_SLOW = "This expression takes too long to search with"

/**
 * The search of a session ([TerminalSearch] is its face): libghostty's, for a text, and ours, for a regular expression, with what each keeps.
 * The frame producer gives it each frame: [look] under the session's [lock], with the update of the render state, so that the matches are where
 * they are on that frame, then [matches] outside it.
 *
 * @param isClosed Whether the session is closed, which the calls that take the lock look at.
 * @param requestFrame Asks for a frame: a search that is not complete, or a look that comes later, needs another.
 * @param scope The producer's, where a look that is too soon is asked for later.
 */
internal class SessionSearch(
    private val terminal: GhosttyTerminal,
    private val lock: ReentrantLock,
    private val isClosed: () -> Boolean,
    private val requestFrame: () -> Unit,
    private val scope: CoroutineScope,
) {
    // The search, which is made when the first query is set, and what it was asked for. Under the lock.
    private var searcher: GhosttySearch? = null
    private var searchQuery = ""

    private val searchStateFlow = MutableStateFlow(TerminalSearchState())

    // The search with a regular expression, when that is what was asked for: its pattern, what it found in the last rows as of the last time
    // it looked, the match that is selected, and when it looked. It is ours and not libghostty's, which is then idle. Under the lock.
    private var regexQuery: RegexQuery? = null
    private var regexMatchList: List<RegexMatch> = emptyList()
    private var regexSelected: RegexMatch? = null
    private var regexStale = true
    private var regexLookedAtNanos = 0L

    // The screen changed since the last look, too soon after it to look again: the next look is for it, whatever the screen does then. And
    // whether a frame is asked for, for that look.
    private var regexChangedSince = false
    private val regexRefreshAsked = AtomicBoolean(false)

    // What changes with each query: matches that were found for another are not published. A move that was asked for before there were matches
    // to move in (toward older content, or newer), which the producer does once it has them. And whether the last look gave up.
    private var regexGeneration = 0
    private var regexPendingMove: Boolean? = null
    private var regexTooSlow = false

    val state: StateFlow<TerminalSearchState> get() = searchStateFlow

    /** Searches for [query] from the start. Under the lock. */
    fun setQuery(query: String, regex: Boolean, caseSensitive: Boolean) {
        searchQuery = query
        regexMatchList = emptyList()
        regexSelected = null
        regexStale = true
        regexGeneration++
        regexPendingMove = null
        regexTooSlow = false
        if (query.isEmpty()) {
            regexQuery = null
            searcher?.setNeedle("")
            searchStateFlow.value = TerminalSearchState()
        } else if (regex) {
            // libghostty's search is idle: this one is ours.
            searcher?.setNeedle("")
            val compiled = RegexQuery.of(query, caseSensitive).also { regexQuery = it }
            searchStateFlow.value = TerminalSearchState(query = query, searching = compiled.pattern != null, error = compiled.error)
        } else {
            regexQuery = null
            val active = searcher ?: GhosttySearch(terminal).also { searcher = it }
            active.setNeedle(query)
            searchStateFlow.value = TerminalSearchState(query = query, searching = true)
        }
    }

    /** Selects the match toward older content (libghostty's next: up the screen), or toward newer. Under the lock. */
    fun move(towardOlder: Boolean) {
        if (searchQuery.isEmpty()) return
        if (regexQuery != null) {
            // Not looked for here (it is the caller's thread, the UI's for the keys, and under the lock): in the matches of the last look,
            // or once the producer has some.
            if (regexStale) regexPendingMove = towardOlder else moveRegexSelection(towardOlder)
            return
        }
        val active = searcher ?: return
        if (towardOlder) active.selectNext() else active.selectPrevious()
    }

    /** What is freed with the session. Under the lock. */
    fun close() {
        searcher?.close()
        searcher = null
    }

    // The match after the selected one (toward newer content), or before it, round at the ends; the first one is the top-most. It scrolls
    // to bring it into view. Under the lock.
    private fun moveRegexSelection(towardOlder: Boolean) {
        val matches = regexMatchList
        if (matches.isEmpty()) return
        val at = matches.indexOfMatch(regexSelected)
        val index = when {
            at == null -> if (towardOlder) matches.lastIndex else 0
            towardOlder -> (at - 1 + matches.size) % matches.size
            else -> (at + 1) % matches.size
        }
        val match = matches[index]
        regexSelected = match
        val position = terminal.scrollbar
        if (match.row < position.offset || match.row >= position.offset + position.len) {
            terminal.scroll(GhosttyScrollViewport.Row((match.row - position.len / 2).coerceIn(0, maxOf(0, position.total - position.len))))
        }
    }

    /** What the search takes of a frame, under the lock, for [matches] to finish outside it. */
    class Look internal constructor(internal val found: SearchFound?, internal val regexActive: Boolean, internal val regex: RegexLook?)

    /**
     * Feeds libghostty's search and ticks it, or reads the rows that a regular expression is to be looked for in again, as of this frame. Under the
     * lock, in the same section as the update of the render state.
     *
     * @param changed Whether the screen changed since the last frame.
     */
    fun look(changed: Boolean): Look {
        val found = searchOnFrame()
        val regexActive = regexQuery?.pattern != null && searchQuery.isNotEmpty()
        return Look(found, regexActive, if (regexActive) regexLookOnFrame(changed) else null)
    }

    /**
     * The matches that are on the [rows] of the viewport, which is at [position] in a screen [cols] wide, and what the host is shown of the
     * search. Not under the lock: the matching of a regular expression is done here, and takes it to publish.
     */
    fun matches(look: Look, rows: List<TerminalRow>, position: GhosttyScrollbar, cols: Int): List<TerminalSearchMatch> {
        look.found?.let { found ->
            searchStateFlow.value = found.state
            // A search goes through the history a bounded amount at a time.
            if (found.status != GhosttySearchStatus.COMPLETE) requestFrame()
            return searchMatches(found, cols)
        }
        if (!look.regexActive) return emptyList()
        // The matches of a regular expression, looked for again outside the lock when it is time, and what is selected among them.
        look.regex?.let(::lookForRegexMatches)
        val view = lock.withLock { RegexView(regexMatchList, regexSelected, regexTooSlow) }
        return regexFrameMatches(view, rows, position)
    }

    // What a look for the matches searches: the text of the last rows, for the query of [generation].
    internal class RegexLook(val pattern: java.util.regex.Pattern, val lines: List<String>, val first: Long, val generation: Int)

    // The text of the last rows, to look for the matches in again, when the screen changed since the last look or the query did. Under the lock:
    // the matching is not, since a pattern can take long and the program's output and the keys wait for the lock.
    private fun regexLook(): RegexLook? {
        val pattern = regexQuery?.pattern ?: return null
        val total = terminal.totalRows
        val first = maxOf(0L, total - REGEX_MAX_ROWS)
        val lines = if (total == 0L) emptyList() else terminal.readRows(first, total - 1, unwrap = false).split('\n')
        regexStale = false
        regexChangedSince = false
        regexLookedAtNanos = System.nanoTime()
        return RegexLook(pattern, lines, first, regexGeneration)
    }

    // Looks for the matches of [look], with a time budget, and publishes them, unless the query changed meanwhile. Not under the lock but for the
    // publishing; on the producer.
    private fun lookForRegexMatches(look: RegexLook) {
        val found = try {
            regexMatches(look.lines, look.first, look.pattern, System.nanoTime() + REGEX_BUDGET_NS)
        } catch (_: RegexTooSlowException) {
            null
        } catch (_: StackOverflowError) {
            // Some patterns recurse in the matcher as they backtrack: too slow as well.
            null
        }
        lock.withLock {
            if (isClosed() || look.generation != regexGeneration) return
            regexTooSlow = found == null
            regexMatchList = found.orEmpty()
            // What is selected goes on being so if it is still there, and is not any more if not.
            regexSelected = regexMatchList.indexOfMatch(regexSelected)?.let(regexMatchList::get)
            regexPendingMove?.let { towardOlder ->
                regexPendingMove = null
                moveRegexSelection(towardOlder)
                // It may have scrolled: the frame is made again.
                requestFrame()
            }
        }
    }

    // What the matches of the regular expression are on a frame, read under the lock.
    private class RegexView(val matches: List<RegexMatch>, val selected: RegexMatch?, val tooSlow: Boolean)

    // The text to look for the matches of the regular expression in again, when the screen changed since the last look, and not more often than
    // every so often: a long history is read for it; `null` when the matches of the last look stay. Under the lock.
    private fun regexLookOnFrame(changed: Boolean): RegexLook? {
        val sinceNanos = System.nanoTime() - regexLookedAtNanos
        if (changed) regexChangedSince = true
        if (regexStale || (regexChangedSince && sinceNanos >= REGEX_REFRESH_NS)) {
            return regexLook()
        } else if (regexChangedSince && regexRefreshAsked.compareAndSet(false, true)) {
            // Too soon: it looks when it is time, once, even if nothing changes after this.
            scope.launch {
                delay((REGEX_REFRESH_NS - sinceNanos).nanoseconds)
                regexRefreshAsked.set(false)
                requestFrame()
            }
        }
        return null
    }

    // The matches that are on the rows of the viewport, by the cells that they cover, and what the host shows of the search.
    private fun regexFrameMatches(view: RegexView, rows: List<TerminalRow>, position: GhosttyScrollbar): List<TerminalSearchMatch> {
        val matches = view.matches
        val selected = view.selected
        if (view.tooSlow) {
            searchStateFlow.value = TerminalSearchState(searchQuery, error = REGEX_TOO_SLOW)
            return emptyList()
        }
        val index = matches.indexOfMatch(selected)
        searchStateFlow.value = TerminalSearchState(searchQuery, matches.size, index?.let { it + 1 }, searching = false)
        val visible = ArrayList<TerminalSearchMatch>()
        // The columns of the characters of a row, once for all the matches on it.
        val columnsOfRow = HashMap<Int, IntArray>()
        for (match in matches) {
            val y = (match.row - position.offset).takeIf { it >= 0 && it < rows.size }?.toInt() ?: continue
            val cells = rows[y].cells
            val columns = columnsOfRow.getOrPut(y) { columnsOfCharacters(cells) }
            val first = columns.getOrNull(match.start) ?: continue
            val lastChar = columns.getOrNull(match.end - 1) ?: continue
            val last = (lastChar + cellSpan(cells, lastChar) - 1).coerceAtMost(cells.size - 1)
            visible += TerminalSearchMatch(y, first..last, current = match.isAt(selected))
        }
        return visible
    }

    // What the search has for one frame.
    internal class SearchFound(
        val state: TerminalSearchState,
        val status: GhosttySearchStatus,
        val matches: List<GhosttyViewportMatch>,
        val selected: GhosttyViewportMatch?,
    )

    // Under the lock.
    private fun searchOnFrame(): SearchFound? {
        val active = searcher ?: return null
        if (searchQuery.isEmpty() || regexQuery != null) return null
        active.feed()
        val status = active.tick()
        val total = active.totalMatches
        val index = active.selectedIndex
        return SearchFound(
            // The newest is 0 for libghostty, and the last for a person.
            state = TerminalSearchState(searchQuery, total, index?.let { total - it }, status != GhosttySearchStatus.COMPLETE),
            status = status,
            matches = active.viewportMatches(),
            selected = active.selectedMatch(),
        )
    }

    // A match that wraps is one for each of its rows.
    private fun searchMatches(found: SearchFound, cols: Int): List<TerminalSearchMatch> =
        found.matches.flatMap { match ->
            (match.startRow..match.endRow).map { row ->
                val first = if (row == match.startRow) match.startColumn else 0
                val last = if (row == match.endRow) match.endColumn else cols - 1
                TerminalSearchMatch(row, first..last, current = match == found.selected)
            }
        }
}
