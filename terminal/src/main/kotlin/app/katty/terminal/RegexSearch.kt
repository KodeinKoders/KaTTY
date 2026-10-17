package app.katty.terminal

import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

// The search with a regular expression. libghostty's own search has none, so this is ours: it goes over the text of the last rows of the
// history and the screen, a row at a time, and it is pure so that it can be tested without a terminal.

/** A match, on the [row] of the history and the screen (0 is the top of the history), by the characters of that row's text: [end] is past its last. */
internal data class RegexMatch(val row: Long, val start: Int, val end: Int)

/** The pattern for [query], or `null` and the reason when it is not a regular expression. */
internal class RegexQuery(val pattern: Pattern?, val error: String?) {
    companion object {
        fun of(query: String, caseSensitive: Boolean): RegexQuery = try {
            RegexQuery(Pattern.compile(query, if (caseSensitive) 0 else Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE), null)
        } catch (e: PatternSyntaxException) {
            RegexQuery(null, e.description)
        }
    }
}

/**
 * The matches of [pattern] in [lines], which are the rows from [firstRow] on, one each. A match does not go over the end of a row: a line
 * that wraps is searched a row at a time. A match that is empty is not one, so that `a*` does not match everywhere.
 */
internal fun regexMatches(lines: List<String>, firstRow: Long, pattern: Pattern): List<RegexMatch> {
    val matches = ArrayList<RegexMatch>()
    for ((index, line) in lines.withIndex()) {
        if (line.isEmpty()) continue
        val matcher = pattern.matcher(line)
        var from = 0
        // `find(from)` looks in the whole line, so that `^` is only the start of it.
        while (from <= line.length && matcher.find(from)) {
            if (matcher.end() == matcher.start()) {
                from = matcher.end() + 1
                continue
            }
            matches += RegexMatch(firstRow + index, matcher.start(), matcher.end())
            from = matcher.end()
        }
    }
    return matches
}

/**
 * For each character of the text of a row (the cells' texts, one blank for a cell that has none, as the text of a row is read), the
 * column of the cell that it is in: the matches are in characters and the screen is in cells, which a wide character or a grapheme of
 * several characters makes different. It has one more entry than there are characters, the column after the last.
 */
internal fun columnsOfCharacters(cells: List<TerminalCell>): IntArray {
    val columns = ArrayList<Int>()
    for ((column, cell) in cells.withIndex()) {
        if (cell.width == TerminalCellWidth.SPACER_TAIL || cell.width == TerminalCellWidth.SPACER_HEAD) continue
        repeat(cell.text.ifEmpty { " " }.length) { columns += column }
    }
    columns += cells.size
    return columns.toIntArray()
}

/** How many columns the cell at [column] takes: 2 for a wide character. */
internal fun cellSpan(cells: List<TerminalCell>, column: Int): Int =
    if (cells.getOrNull(column)?.width == TerminalCellWidth.WIDE) 2 else 1
