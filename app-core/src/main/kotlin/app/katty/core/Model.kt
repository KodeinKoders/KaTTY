package app.katty.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** What identifies a project. Ids are made up when what they identify is created, and kept as long as it is. */
@Serializable
@JvmInline
public value class ProjectId(public val value: String)

@Serializable
@JvmInline
public value class WorkspaceId(public val value: String)

@Serializable
@JvmInline
public value class TerminalId(public val value: String)

@Serializable
@JvmInline
public value class SplitId(public val value: String)

/**
 * A folder on the disk, with the workspaces that were opened in it. Every terminal of it starts in that folder, or where its shell went
 * afterwards. A project is removed without deleting its folder.
 */
@Serializable
public data class Project(
    val id: ProjectId,
    val name: String,
    val directory: String,
    val workspaces: List<Workspace> = emptyList(),
)

/**
 * A named group of the terminals of a project. It always has a terminal: when the last one is closed, the workspace is gone.
 *
 * @param layout The tree of the splits. The vertical mode draws it with every split turned.
 */
@Serializable
public data class Workspace(
    val id: WorkspaceId,
    val name: String,
    val layout: PaneNode,
)

/** How a split puts its two sides, in the horizontal layout. */
@Serializable
public enum class SplitDirection {
    /** The first side is on the left, the second on the right. */
    ROW,

    /** The first side is above, the second below. */
    COLUMN,
    ;

    /** The other one: what the vertical layout draws. */
    public fun turned(): SplitDirection = if (this == ROW) COLUMN else ROW
}

/** The tree of the splits of a workspace: a leaf is a terminal, and a split has two sides. */
@Serializable
public sealed interface PaneNode {
    /**
     * @param ratio The share of the first side, between 0 and 1.
     */
    @Serializable
    @SerialName("split")
    public data class Split(
        val id: SplitId,
        val direction: SplitDirection,
        val ratio: Float = 0.5f,
        val first: PaneNode,
        val second: PaneNode,
    ) : PaneNode

    @Serializable
    @SerialName("leaf")
    public data class Leaf(val terminal: TerminalSpec) : PaneNode
}

/**
 * What is kept of a terminal. The shell is not: a terminal comes back with a new one.
 *
 * @param name What the user called it; `null` for the name of its agent, or of its shell.
 * @param directory Where its shell starts: where the shell was when this was last known, which a restored terminal starts in.
 * @param colorScheme The name of its color scheme; `null` for the default of the settings.
 */
@Serializable
public data class TerminalSpec(
    val id: TerminalId,
    val name: String? = null,
    val directory: String,
    val colorScheme: String? = null,
)

/** The terminals of the tree, in the order they are drawn: left to right, top to bottom. */
public fun PaneNode.terminals(): List<TerminalSpec> = when (this) {
    is PaneNode.Leaf -> listOf(terminal)
    is PaneNode.Split -> first.terminals() + second.terminals()
}

/** The tree with [transform] applied to the terminal [id]; the same tree if there is none. */
internal fun PaneNode.mapTerminal(id: TerminalId, transform: (TerminalSpec) -> TerminalSpec): PaneNode = when (this) {
    is PaneNode.Leaf -> if (terminal.id == id) PaneNode.Leaf(transform(terminal)) else this
    is PaneNode.Split -> {
        val newFirst = first.mapTerminal(id, transform)
        val newSecond = second.mapTerminal(id, transform)
        if (newFirst === first && newSecond === second) this else copy(first = newFirst, second = newSecond)
    }
}

/** The tree with the leaf of [id] replaced by [replacement]; the same tree if there is none. */
internal fun PaneNode.replaceLeaf(id: TerminalId, replacement: PaneNode): PaneNode = when (this) {
    is PaneNode.Leaf -> if (terminal.id == id) replacement else this
    is PaneNode.Split -> {
        val newFirst = first.replaceLeaf(id, replacement)
        val newSecond = second.replaceLeaf(id, replacement)
        if (newFirst === first && newSecond === second) this else copy(first = newFirst, second = newSecond)
    }
}

/**
 * The tree without the leaf of [id], where the sibling of that leaf takes the space of the split that held them.
 *
 * @return `null` if [id] was the only leaf, since a tree has at least one.
 */
internal fun PaneNode.withoutTerminal(id: TerminalId): PaneNode? = when (this) {
    is PaneNode.Leaf -> if (terminal.id == id) null else this
    is PaneNode.Split -> {
        val newFirst = first.withoutTerminal(id)
        val newSecond = second.withoutTerminal(id)
        when {
            newFirst == null -> newSecond
            newSecond == null -> newFirst
            newFirst === first && newSecond === second -> this
            else -> copy(first = newFirst, second = newSecond)
        }
    }
}

/** The tree with the ratio of the split [id] changed. */
internal fun PaneNode.withRatio(id: SplitId, ratio: Float): PaneNode = when (this) {
    is PaneNode.Leaf -> this
    is PaneNode.Split -> if (this.id == id) copy(ratio = ratio) else copy(first = first.withRatio(id, ratio), second = second.withRatio(id, ratio))
}
