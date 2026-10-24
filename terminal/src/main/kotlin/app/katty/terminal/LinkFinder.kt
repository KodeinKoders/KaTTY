package app.katty.terminal

// Finding the link at a cell, from the rows of a frame: pure, so that it is tested without a terminal. The session gives it the rows and the
// addresses of the marked cells.

// How many rows from the one that was asked a link that wraps is followed.
private const val LINK_ROWS = 16

// The last cell of a row that is a character of it: a wide character that did not fit leaves a blank one at the end.
private fun lastColumn(cells: List<TerminalCell>) =
    if (cells.last().width == TerminalCellWidth.SPACER_HEAD) cells.lastIndex - 1 else cells.lastIndex

/**
 * A link that the program marked (OSC 8) at [column] and [row] of [rows]: the cells around it that have the same address, on its row and on the
 * rows that it goes on onto. [hyperlinkAt] is the address of a cell, which the terminal knows and the rows do not.
 */
internal fun markedLink(rows: List<TerminalRow>, column: Int, row: Int, hyperlinkAt: (column: Int, row: Int) -> String?): TerminalLink? {
    if (!rows[row].cells[column].hyperlink) return null
    val url = hyperlinkAt(column, row) ?: return null
    fun same(r: Int, c: Int) = rows[r].cells[c].hyperlink && hyperlinkAt(c, r) == url
    fun left(r: Int, from: Int): Int {
        var start = from
        while (start > 0 && same(r, start - 1)) start--
        return start
    }
    fun right(r: Int, from: Int): Int {
        val last = lastColumn(rows[r].cells)
        var end = from
        while (end < last && same(r, end + 1)) end++
        return end
    }

    var start = left(row, column)
    var end = right(row, column)
    val segments = ArrayDeque<TerminalLinkSegment>()
    segments += TerminalLinkSegment(row, start..end)
    // It starts at the left edge, and the row above goes on into this one, and ends in the link: the link is there too.
    var top = row
    while (start == 0 && top > 0 && top > row - LINK_ROWS && rows[top - 1].wrapped) {
        val above = top - 1
        val last = lastColumn(rows[above].cells)
        if (!same(above, last)) break
        start = left(above, last)
        segments.addFirst(TerminalLinkSegment(above, start..last))
        top = above
    }
    // And the same the other way.
    var bottom = row
    while (end == lastColumn(rows[bottom].cells) && rows[bottom].wrapped && bottom + 1 < rows.size && bottom < row + LINK_ROWS) {
        val below = bottom + 1
        if (!same(below, 0)) break
        end = right(below, 0)
        segments += TerminalLinkSegment(below, 0..end)
        bottom = below
    }
    return TerminalLink(url, segments.toList())
}

/** A link that is written in the text at [column] and [row] of [rows]: of the row, and of the rows that it wraps onto, which are one line. */
internal fun writtenLink(rows: List<TerminalRow>, column: Int, row: Int): TerminalLink? {
    var first = row
    while (first > 0 && first > row - LINK_ROWS && rows[first - 1].wrapped) first--
    var last = row
    while (last + 1 < rows.size && last < row + LINK_ROWS && rows[last].wrapped) last++

    val text = StringBuilder()
    // The row and the column of each character: the second half of a wide character has none of its own.
    val rowOf = ArrayList<Int>()
    val columnOf = ArrayList<Int>()
    for (r in first..last) {
        for ((c, cell) in rows[r].cells.withIndex()) {
            if (cell.width == TerminalCellWidth.SPACER_TAIL || cell.width == TerminalCellWidth.SPACER_HEAD) continue
            val part = cell.text.ifEmpty { " " }
            text.append(part)
            repeat(part.length) {
                rowOf += r
                columnOf += c
            }
        }
    }
    var index = -1
    for (i in rowOf.indices) if (rowOf[i] == row && columnOf[i] == column) { index = i; break }
    if (index < 0) return null
    val range = findUrl(text.toString(), index) ?: return null

    // The cells of each row that the characters of the address are on.
    val segments = ArrayList<TerminalLinkSegment>()
    var i = range.first
    while (i <= range.last) {
        val segmentRow = rowOf[i]
        val startColumn = columnOf[i]
        var endColumn = startColumn
        while (i <= range.last && rowOf[i] == segmentRow) {
            endColumn = columnOf[i]
            i++
        }
        segments += TerminalLinkSegment(segmentRow, startColumn..endColumn)
    }
    return TerminalLink(text.substring(range.first, range.last + 1), segments)
}
