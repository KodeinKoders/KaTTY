package app.katty.core

/**
 * The card that an arrow key goes to on the board, from the card of [from] (`null` when none is selected, or it is gone: the first card of the
 * board is the answer to any arrow). `null` when the board has no card.
 *
 * Side by side ([stacked] is `false`), Up and Down go along a column, and Left and Right to the nearest column that has a card, on the same row
 * (or its last one, when it is shorter). Stacked, as the vertical layout draws the sections, Up and Down go through every card in the order they
 * are drawn and Left and Right do nothing. At the end of a column, or of the board, the card stays selected.
 */
public fun Map<BoardColumn, List<BoardCard>>.stepSelection(from: TerminalId?, direction: Direction, stacked: Boolean): TerminalId? {
    val all = values.flatten()
    if (all.isEmpty()) return null
    val current = all.indexOfFirst { it.terminal.id == from }
    if (current < 0) return all.first().terminal.id
    if (stacked) {
        val next = when (direction) {
            Direction.UP -> current - 1
            Direction.DOWN -> current + 1
            else -> current
        }
        return all[next.coerceIn(all.indices)].terminal.id
    }
    val columns = values.filter { it.isNotEmpty() }
    val column = columns.indexOfFirst { list -> list.any { it.terminal.id == from } }
    val row = columns[column].indexOfFirst { it.terminal.id == from }
    return when (direction) {
        Direction.UP -> columns[column][(row - 1).coerceAtLeast(0)]
        Direction.DOWN -> columns[column][(row + 1).coerceAtMost(columns[column].lastIndex)]
        Direction.LEFT -> columns.getOrNull(column - 1)?.let { it[row.coerceAtMost(it.lastIndex)] } ?: columns[column][row]
        Direction.RIGHT -> columns.getOrNull(column + 1)?.let { it[row.coerceAtMost(it.lastIndex)] } ?: columns[column][row]
    }.terminal.id
}
