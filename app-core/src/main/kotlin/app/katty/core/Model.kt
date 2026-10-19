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
    /** Whether its workspaces are listed in the sidebar. Any number of projects are open at once. */
    val open: Boolean = true,
)

/**
 * A named group of the terminals of a project. It always has a terminal: when the last one is closed, the workspace is gone.
 *
 * @param layout The tree of the splits. The vertical mode draws it with every split turned.
 * @param focused The terminal that had the focus last, which has it again when the workspace is selected; `null` until one had, and when it is
 * not there any more the first terminal does.
 */
@Serializable
public data class Workspace(
    val id: WorkspaceId,
    val name: String,
    val layout: PaneNode,
    val focused: TerminalId? = null,
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
 * The program that had a terminal, over its shell, when it was last known: what a restored terminal types in its new shell to get it back. The
 * program's own state is not kept (an editor's buffers, an unsent prompt): it is started again, not resumed, except for an agent that can pick
 * up its conversation.
 *
 * @param argv The command line, the program first.
 * @param directory Where the program was, which is not always where the shell was; `null` when it was not known.
 * @param agent The [Agent.id] of the program when it is a coding agent, `null` for any other.
 * @param agentSession The agent's id for its current conversation, when it could be told: what it resumes with.
 */
@Serializable
public data class RunningCommand(
    val argv: List<String>,
    val directory: String? = null,
    val agent: String? = null,
    val agentSession: String? = null,
)

/**
 * What is kept of a terminal. The shell is not: a terminal comes back with a new one, and types [running] in it.
 *
 * @param name What the user called it; `null` for the name of its agent, or of its shell.
 * @param directory Where its shell starts: where the shell was when this was last known, which a restored terminal starts in.
 * @param colorScheme The name of its color scheme; `null` for the default of the settings.
 * @param running The program that had the terminal over its shell, `null` when it was the shell at its prompt.
 */
@Serializable
public data class TerminalSpec(
    val id: TerminalId,
    val name: String? = null,
    val directory: String,
    val colorScheme: String? = null,
    val running: RunningCommand? = null,
)

/** The terminal that has the focus when the workspace is selected: the one that had it last, or the first one when it is gone or none had it. */
public fun Workspace.terminalToFocus(): TerminalId? {
    val terminals = layout.terminals()
    return (terminals.firstOrNull { it.id == focused } ?: terminals.firstOrNull())?.id
}

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
