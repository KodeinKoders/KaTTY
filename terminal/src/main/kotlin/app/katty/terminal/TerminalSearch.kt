package app.katty.terminal

import kotlinx.coroutines.flow.StateFlow

/**
 * Where a search is: what the host shows in its find bar.
 *
 * @param query The text that is searched for, empty when nothing is.
 * @param total How many matches there are, as of the last frame.
 * @param selected Which match is the selected one, from 1 at the **top** of the history to [total] at the bottom of the screen: the
 * number of "3 of 12". `null` when none is selected.
 * @param searching Whether the search is still going through the history: [total] may grow.
 * @param error Why the query is not a regular expression, when it was asked to be one and is not: nothing is searched then.
 */
public data class TerminalSearchState(
    val query: String = "",
    val total: Int = 0,
    val selected: Int? = null,
    val searching: Boolean = false,
    val error: String? = null,
)

/**
 * A match of the search on a row that is in view. A match that wraps onto the next row is one for each row.
 *
 * @param row The row of the viewport.
 * @param columns The cells it covers, both ends included.
 * @param current Whether it is the selected one.
 */
public data class TerminalSearchMatch(val row: Int, val columns: IntRange, val current: Boolean)

/** How many of the last rows of the history and the screen a search with a regular expression goes through. */
public const val REGEX_MAX_ROWS: Int = 5000

/**
 * The search of a session: for a text in its history and its screen, both screens of it. It is case-insensitive for ASCII letters and
 * byte for byte otherwise, or a regular expression over the last rows (see [setQuery]), with no whole word. The host has the find bar: it sets the [query], moves with [next]
 * and [previous], shows [state], and the terminal draws the matches that are in view.
 *
 * It goes on while a [query] is set: what the program writes is searched too, and a long history is searched over a number of frames. A
 * session that is not visible does not search: it catches up when it is shown.
 */
public class TerminalSearch internal constructor(private val session: TerminalSession) {
    /** Where the search is. */
    public val state: StateFlow<TerminalSearchState> get() = session.searchState

    /**
     * Searches for [query] from the start. An empty one stops the search and takes the matches off the screen.
     *
     * With [regex] it is a regular expression (Java's), which is case-insensitive unless [caseSensitive], and is searched in the last
     * [REGEX_MAX_ROWS] rows of the history and the screen, a row at a time: a match does not go over the end of a row. One that is not
     * a regular expression is a [TerminalSearchState.error], and there are no matches. Without it the search is libghostty's, which is
     * case-insensitive for ASCII letters, whatever [caseSensitive] says.
     */
    public fun setQuery(query: String, regex: Boolean = false, caseSensitive: Boolean = false): Unit =
        session.setSearchQuery(query, regex, caseSensitive)

    /**
     * Selects the next match, toward newer content: down the screen and, past the last, round to the first of the history. The
     * viewport scrolls to bring it into view. The first one after a new query is the top-most.
     */
    public fun next(): Unit = session.moveSearch(towardOlder = false)

    /** Selects the previous match, toward older content: up, and round to the last once past the first. */
    public fun previous(): Unit = session.moveSearch(towardOlder = true)
}

/**
 * How much native memory a session holds, which a host can add up to see which terminals cost the most.
 *
 * @param residentBytes What the history and the screens use in physical memory: compressed history counts what it takes compressed.
 * @param compressedBytes What compressed history takes, which is part of [residentBytes].
 * @param imageBytes What the images of the Kitty graphics protocol take, which is not.
 */
public data class TerminalMemoryUsage(val residentBytes: Long, val compressedBytes: Long, val imageBytes: Long)
