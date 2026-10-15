package app.katty.core

/** The groups of the command palette, in the order they are listed. */
public enum class PaletteGroup(public val title: String) {
    WORKSPACES("WORKSPACES"),
    TERMINALS("TERMINALS"),
    COMMANDS("COMMANDS"),
}

/**
 * Something the palette can go to or do.
 *
 * @param id Which it is, for the window to act on.
 * @param context What is written muted after the label: the project of a workspace, the state of a terminal.
 * @param shortcut The keys of a command that has them, as `⌘K`.
 */
public data class PaletteEntry(
    val id: String,
    val group: PaletteGroup,
    val label: String,
    val context: String = "",
    val shortcut: String? = null,
)

/**
 * An entry that matches what was typed.
 *
 * @param indices The characters of the label that the query matched, to be drawn in the accent color.
 */
public data class PaletteMatch(val entry: PaletteEntry, val indices: List<Int>, val score: Int)

/**
 * The characters of [text] that the letters of [query] are, in order but not next to each other: `rn` is in `Rename`. The case does not
 * matter. A match that has its letters together, and at the start of the text or of a word, is better.
 *
 * @return The score and the positions, `null` when the query is not in the text.
 */
public fun fuzzyMatch(query: String, text: String): Pair<Int, List<Int>>? {
    val needle = query.filterNot { it.isWhitespace() }.lowercase()
    if (needle.isEmpty()) return 0 to emptyList()
    val hay = text.lowercase()

    // The best of the matches that begin at each place the first letter is.
    var best: Pair<Int, List<Int>>? = null
    var start = hay.indexOf(needle[0])
    while (start >= 0) {
        val indices = ArrayList<Int>(needle.length)
        var at = start
        var score = 0
        var previous = -2
        var ok = true
        for (letter in needle) {
            val found = hay.indexOf(letter, at)
            if (found < 0) {
                ok = false
                break
            }
            indices += found
            // Together: a bonus that grows with the run. At the start of a word: a bonus. Far from the last one: a small penalty.
            score += if (found == previous + 1) 6 else -(found - previous - 1).coerceAtMost(3)
            if (found == 0 || !hay[found - 1].isLetterOrDigit()) score += 5
            previous = found
            at = found + 1
        }
        if (ok) {
            // A short text with the match in it is better than a long one: it is more what was meant.
            score -= (hay.length - needle.length) / 4
            if (best == null || score > best.first) best = score to indices
        }
        start = hay.indexOf(needle[0], start + 1)
    }
    return best
}

/**
 * The entries that match [query], in the order of the groups and in each the best match first; all of them, as they are, for a query with no
 * letters.
 */
public fun searchPalette(entries: List<PaletteEntry>, query: String): List<PaletteMatch> {
    val matches = entries.mapNotNull { entry ->
        val (score, indices) = fuzzyMatch(query, entry.label) ?: return@mapNotNull null
        PaletteMatch(entry, indices, score)
    }
    return PaletteGroup.entries.flatMap { group ->
        val inGroup = matches.filter { it.entry.group == group }
        if (query.isBlank()) inGroup else inGroup.sortedByDescending { it.score }
    }
}
