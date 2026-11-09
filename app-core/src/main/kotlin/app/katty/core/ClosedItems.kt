package app.katty.core

/** How many of the last things that were closed can be opened again. */
public const val REOPEN_MAX_ITEMS: Int = 10

/** For how long something that was closed can be opened again. */
public const val REOPEN_KEPT_MS: Long = 10 * 60 * 1000L

/**
 * Something that was closed and can be opened again: a terminal, which comes back at its place in its workspace, or a whole workspace, which comes back
 * at its place in its project, with its splits. It is kept in memory only, for [REOPEN_KEPT_MS], and nothing of it is written: the terminals come back
 * as a terminal of a state that was saved does, with a new shell that types what ran over the old one.
 *
 * @param project The project it was in: it cannot come back once that is removed.
 * @param index The place of the workspace among the ones of its project.
 * @param workspace The workspace as it was closed; for a terminal, its workspace with that terminal alone, which is what comes back when the
 * workspace is gone meanwhile.
 * @param place Where the terminal was in the splits of its workspace, which stayed; `null` when the whole workspace was closed, which closing its last
 * terminal does.
 * @param closedAt When, in milliseconds since the epoch.
 */
public data class ClosedItem(
    val project: ProjectId,
    val index: Int,
    val workspace: Workspace,
    val place: PanePlace?,
    val closedAt: Long,
) {
    /** Whether it is a whole workspace, and not a terminal of a workspace that stayed. */
    val isWorkspace: Boolean get() = place == null

    /** The terminals that come back with it. */
    val terminals: List<TerminalSpec> get() = workspace.layout.terminals()
}

/**
 * Where a terminal was in the splits of its workspace: the split that held it ([split], with its [direction] and [ratio]), whether it was its [first]
 * side, and what was on the other side, which took the place of the split when the terminal was closed: a terminal ([besideTerminal]) or a split
 * ([besideSplit]).
 */
public data class PanePlace(
    val split: SplitId,
    val direction: SplitDirection,
    val ratio: Float,
    val first: Boolean,
    val besideTerminal: TerminalId? = null,
    val besideSplit: SplitId? = null,
)

/** Where the leaf of [id] is in the tree; `null` when it is the whole tree, or is not in it. */
internal fun PaneNode.placeOf(id: TerminalId): PanePlace? {
    if (this !is PaneNode.Split) return null
    val isFirst = (first as? PaneNode.Leaf)?.terminal?.id == id
    val isSecond = (second as? PaneNode.Leaf)?.terminal?.id == id
    if (!isFirst && !isSecond) return first.placeOf(id) ?: second.placeOf(id)
    val beside = if (isFirst) second else first
    return PanePlace(this.id, direction, ratio, isFirst, (beside as? PaneNode.Leaf)?.terminal?.id, (beside as? PaneNode.Split)?.id)
}

/**
 * The tree with [terminal] back at [place]: the split that held it, with its direction and ratio, around what was beside it. When that is not in the
 * tree any more (closed, or moved to another workspace), it is beside the whole tree, on the side it was.
 */
internal fun PaneNode.withTerminalAt(terminal: TerminalSpec, place: PanePlace): PaneNode {
    val leaf = PaneNode.Leaf(terminal)
    val around = { node: PaneNode -> PaneNode.Split(place.split, place.direction, place.ratio, if (place.first) leaf else node, if (place.first) node else leaf) }
    return replacingBeside(place, around) ?: around(this)
}

// The tree with what was beside the terminal of [place] replaced by [replacement] of it; `null` when it is not in the tree.
private fun PaneNode.replacingBeside(place: PanePlace, replacement: (PaneNode) -> PaneNode): PaneNode? {
    val found = when (this) {
        is PaneNode.Leaf -> terminal.id == place.besideTerminal
        is PaneNode.Split -> id == place.besideSplit
    }
    if (found) return replacement(this)
    if (this !is PaneNode.Split) return null
    first.replacingBeside(place, replacement)?.let { return copy(first = it) }
    second.replacingBeside(place, replacement)?.let { return copy(second = it) }
    return null
}
