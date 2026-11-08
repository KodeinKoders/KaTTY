package app.katty.terminal

// An address starts with its scheme and goes on until what cannot be in one.
private val URL = Regex("""(?:https?|ftp|file)://[^\s<>"`]+|mailto:[^\s<>"`]+""")

private const val TRAILING = ".,;:!?'\""

/**
 * The address in [text] that has the character at [index], or its range of characters, both ends included; `null` when the
 * character is not part of one.
 *
 * What ends a sentence is not part of the address: `see https://kodein.net.` is `https://kodein.net`. Nor is a closing
 * parenthesis or bracket that has no opening one in it, so `(https://kodein.net)` is the address and `https://x/a_(b)` still has its own.
 */
internal fun findUrl(text: String, index: Int): IntRange? {
    for (match in URL.findAll(text)) {
        if (match.range.first > index) return null
        val range = match.range.first until match.range.first + trimmedLength(match.value)
        if (index in range) return range
    }
    return null
}

// A path as tools print it: the characters of a file name but a space and a colon, after what cannot be in one (or nothing), and then a line
// and a column.
private val PATH = Regex("""(?<![^\s(\[<{"'`=,])([A-Za-z0-9._~@%+\-/]+)(?::(\d{1,9})(?::(\d{1,9}))?)?""")

/**
 * A path that [findPath] found: its characters, [path] as it is written (absolute, from the home, or relative to a directory that is not known
 * here), and the [line] and [column] written after it, `null` when there are none.
 */
internal class PathMatch(val range: IntRange, val path: String, val line: Int?, val column: Int?)

/**
 * The path of a file or a folder in [text] that has the character at [index], with the line and the column that follow it, as compilers, test
 * runners and `grep -n` print them (`src/Foo.kt:42:5`, `Foo.java:42: error`), or a path alone (`git status`, `ls`: `src`, `Makefile`); `null`
 * when the character is not part of one. Whether it is there is not known here: any word with a letter may be one (not `12:34:56` nor `1.2.3`),
 * and only the ones that are on the disk are links. A space ends it (a path that has one is not found), and so does what ends a sentence, as for
 * an address.
 */
internal fun findPath(text: String, index: Int): PathMatch? {
    for (match in PATH.findAll(text)) {
        if (match.range.first > index) return null
        val written = match.groups[1]!!.value
        val lineGroup = match.groups[2]
        // A path with no line may end a sentence: `see src/Foo.kt.`. A line ends the path, so what follows it is not part of it.
        val path = if (lineGroup == null) written.substring(0, trimmedLength(written)) else written
        val end = lineGroup?.let { match.range.last } ?: (match.range.first + path.length - 1)
        if (index > end || path.none { it.isLetter() }) continue
        val line = lineGroup?.value?.toInt()?.takeIf { it > 0 }
        val column = match.groups[3]?.value?.toInt()?.takeIf { it > 0 && line != null }
        return PathMatch(match.range.first..end, path, line, column)
    }
    return null
}

private fun trimmedLength(url: String): Int {
    var end = url.length
    while (end > 0) {
        val last = url[end - 1]
        val trim = last in TRAILING ||
            (last == ')' && url.count(0, end, ')') > url.count(0, end, '(')) ||
            (last == ']' && url.count(0, end, ']') > url.count(0, end, '['))
        if (!trim) break
        end--
    }
    return end
}

private fun String.count(from: Int, to: Int, char: Char) = substring(from, to).count { it == char }
