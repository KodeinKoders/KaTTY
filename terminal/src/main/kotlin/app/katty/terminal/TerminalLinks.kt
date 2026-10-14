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
