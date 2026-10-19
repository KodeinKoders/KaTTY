package app.katty.core

/** A way to go from a pane to the one next to it, as the screen shows it. */
public enum class Direction { LEFT, RIGHT, UP, DOWN }

/** Where a terminal is in the area of its workspace, as fractions of it (0 to 1). */
public data class PaneBox(val left: Double, val top: Double, val right: Double, val bottom: Double) {
    internal val centerX: Double get() = (left + right) / 2
    internal val centerY: Double get() = (top + bottom) / 2
}

/**
 * Where each terminal of the tree is, from the ratios of its splits.
 *
 * @param flipped Whether every split is turned, which is how the vertical layout draws the tree.
 */
public fun PaneNode.boxes(flipped: Boolean = false): Map<TerminalId, PaneBox> {
    val result = LinkedHashMap<TerminalId, PaneBox>()
    fun place(node: PaneNode, box: PaneBox) {
        when (node) {
            is PaneNode.Leaf -> result[node.terminal.id] = box
            is PaneNode.Split -> {
                val direction = if (flipped) node.direction.turned() else node.direction
                val ratio = node.ratio.toDouble()
                if (direction == SplitDirection.ROW) {
                    val middle = box.left + (box.right - box.left) * ratio
                    place(node.first, box.copy(right = middle))
                    place(node.second, box.copy(left = middle))
                } else {
                    val middle = box.top + (box.bottom - box.top) * ratio
                    place(node.first, box.copy(bottom = middle))
                    place(node.second, box.copy(top = middle))
                }
            }
        }
    }
    place(this, PaneBox(0.0, 0.0, 1.0, 1.0))
    return result
}

/**
 * The terminal that is next to [from] in [direction], as the screen shows the tree: among the ones that are past its edge and share part of
 * its side, the nearest, then the one that shares the most of it. `null` when there is none: the edge of the area.
 */
public fun PaneNode.neighbour(from: TerminalId, direction: Direction, flipped: Boolean = false): TerminalId? {
    val boxes = boxes(flipped)
    val origin = boxes[from] ?: return null
    val epsilon = 1e-9

    // How far past the edge another box is (negative: it is not), and how much of the side they share.
    fun gap(other: PaneBox) = when (direction) {
        Direction.RIGHT -> other.left - origin.right
        Direction.LEFT -> origin.left - other.right
        Direction.DOWN -> other.top - origin.bottom
        Direction.UP -> origin.top - other.bottom
    }
    fun shared(other: PaneBox) = when (direction) {
        Direction.RIGHT, Direction.LEFT -> minOf(origin.bottom, other.bottom) - maxOf(origin.top, other.top)
        Direction.DOWN, Direction.UP -> minOf(origin.right, other.right) - maxOf(origin.left, other.left)
    }
    fun centerDistance(other: PaneBox) = when (direction) {
        Direction.RIGHT, Direction.LEFT -> kotlin.math.abs(other.centerY - origin.centerY)
        Direction.DOWN, Direction.UP -> kotlin.math.abs(other.centerX - origin.centerX)
    }

    return boxes.entries
        .filter { (id, box) -> id != from && gap(box) > -epsilon && shared(box) > epsilon }
        .sortedWith(compareBy({ gap(it.value) }, { -shared(it.value) }, { centerDistance(it.value) }))
        .firstOrNull()?.key
}

/**
 * The split that an arrow of the resize shortcut moves, and the share of its first side afterwards: the nearest split above [terminal] whose
 * divider is along the arrow as the screen shows it (a left or right arrow moves a divider between a pane and the one beside it, an up or down
 * arrow one between a pane and the one over or under it). The divider goes where the arrow points: right and down give the first side more,
 * left and up less. `null` when no split above the terminal has such a divider, which is a workspace that is not split that way.
 *
 * @param delta How much of the split a press moves, as a fraction of it.
 * @param flipped Whether every split is turned, which is how the vertical layout draws the tree.
 */
public fun PaneNode.splitToNudge(terminal: TerminalId, direction: Direction, delta: Float, flipped: Boolean = false): Pair<SplitId, Float>? {
    // The splits above the terminal, the nearest first.
    fun above(node: PaneNode): List<PaneNode.Split>? = when (node) {
        is PaneNode.Leaf -> if (node.terminal.id == terminal) emptyList() else null
        is PaneNode.Split -> (above(node.first) ?: above(node.second))?.let { it + node }
    }
    val wanted = if (direction == Direction.LEFT || direction == Direction.RIGHT) SplitDirection.ROW else SplitDirection.COLUMN
    val split = above(this)?.firstOrNull { (if (flipped) it.direction.turned() else it.direction) == wanted } ?: return null
    val sign = if (direction == Direction.RIGHT || direction == Direction.DOWN) 1f else -1f
    return split.id to (split.ratio + sign * delta).coerceIn(MIN_SPLIT_RATIO, 1f - MIN_SPLIT_RATIO)
}

/** The smallest share that a side of a split can have: what the store keeps. */
internal const val MIN_SPLIT_RATIO: Float = 0.05f
