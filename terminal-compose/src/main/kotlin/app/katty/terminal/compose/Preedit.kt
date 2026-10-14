package app.katty.terminal.compose

// How the text that is being composed is laid out on the grid. Pure logic, to be tested without drawing.

/** A character of the preedit, with the combining marks that follow it, and the number of cells it takes. */
internal data class PreeditCell(val text: String, val cols: Int)

/**
 * The cells of [preedit]: a wide character takes two, and a combining mark takes none, it is drawn in the
 * cell of the character it follows. A mark that follows nothing is a cell of its own.
 *
 * The width of a character is approximated, not the terminal's own: it is the East Asian wide ranges and
 * the emoji, which is what an input method composes.
 */
internal fun preeditCells(preedit: String): List<PreeditCell> {
    val cells = ArrayList<PreeditCell>()
    var i = 0
    while (i < preedit.length) {
        val codePoint = preedit.codePointAt(i)
        val text = String(Character.toChars(codePoint))
        i += Character.charCount(codePoint)
        if (isCombining(codePoint) && cells.isNotEmpty()) {
            val last = cells.removeAt(cells.lastIndex)
            cells += last.copy(text = last.text + text)
        } else {
            cells += PreeditCell(text, if (isWide(codePoint)) 2 else 1)
        }
    }
    return cells
}

/**
 * The column where the preedit starts: the cursor's, unless it would not fit on the line, and then as far
 * to the left as it takes, as Ghostty does. The preedit wider than the line starts on the first column.
 */
internal fun preeditStart(cursorCol: Int, preeditCols: Int, cols: Int): Int =
    maxOf(0, minOf(cursorCol, cols - preeditCols))

private fun isCombining(codePoint: Int): Boolean = when (Character.getType(codePoint).toByte()) {
    Character.NON_SPACING_MARK, Character.ENCLOSING_MARK, Character.FORMAT -> true
    else -> false
}

private fun isWide(codePoint: Int): Boolean =
    codePoint in 0x1100..0x115F || // Hangul Jamo
        codePoint in 0x2E80..0x303E || // CJK radicals, symbols and punctuation
        codePoint in 0x3041..0xA4CF || // Kana, CJK, Yi
        codePoint in 0xAC00..0xD7A3 || // Hangul syllables
        codePoint in 0xF900..0xFAFF || // CJK compatibility ideographs
        codePoint in 0xFE30..0xFE6F || // CJK compatibility forms
        codePoint in 0xFF00..0xFF60 || // Fullwidth forms
        codePoint in 0xFFE0..0xFFE6 ||
        codePoint in 0x1F300..0x1F64F || // Emoji
        codePoint in 0x1F900..0x1F9FF ||
        codePoint in 0x20000..0x3FFFD // CJK extensions
