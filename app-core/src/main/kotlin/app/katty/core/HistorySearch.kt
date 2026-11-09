package app.katty.core

/**
 * A row of a terminal where a text was found: [row] in the space of the terminal's history (0 is its top), its [line], and where the text is in
 * it, every time it is there, in characters.
 */
public data class LineMatch(val row: Long, val line: String, val ranges: List<IntRange>)

/**
 * Where [query] is in [lines], the line at an index being the row [firstRow] plus that index: one [LineMatch] for each line that has it, **the last
 * line first** (what was written last is what is looked for most), [limit] at most. Nothing for an empty query.
 *
 * The case of ASCII letters is ignored, and nothing else is: it is what libghostty's search does, which the find bar of the terminal runs on the
 * same text, so that what is found here is what the bar shows.
 */
public fun matchLines(lines: List<String>, firstRow: Long, query: String, limit: Int): List<LineMatch> {
    if (query.isEmpty() || limit <= 0) return emptyList()
    val needle = query.asciiLowercase()
    val found = ArrayList<LineMatch>()
    for (index in lines.indices.reversed()) {
        val line = lines[index]
        if (line.length < needle.length) continue
        val ranges = occurrences(line.asciiLowercase(), needle)
        if (ranges.isEmpty()) continue
        found += LineMatch(firstRow + index, line, ranges)
        if (found.size == limit) break
    }
    return found
}

// Where [needle] is in [text], one after the other without overlapping.
private fun occurrences(text: String, needle: String): List<IntRange> {
    var from = text.indexOf(needle)
    if (from < 0) return emptyList()
    val ranges = ArrayList<IntRange>(1)
    while (from >= 0) {
        ranges += from until from + needle.length
        from = text.indexOf(needle, from + needle.length)
    }
    return ranges
}

// [this] with its ASCII capitals made small, and nothing else changed: the length and every other character stay.
private fun String.asciiLowercase(): String {
    if (none { it in 'A'..'Z' }) return this
    val chars = toCharArray()
    for (i in chars.indices) if (chars[i] in 'A'..'Z') chars[i] = chars[i] + ('a' - 'A')
    return String(chars)
}
