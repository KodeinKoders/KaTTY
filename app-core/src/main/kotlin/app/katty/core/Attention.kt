package app.katty.core

import app.katty.terminal.TerminalCommandOutcome

/**
 * A command that the shell ran, and that ended while the user was not looking at its terminal: what the bell says of it until they have.
 *
 * @param line The line that it was typed on, `null` when it is not known.
 * @param durationMs How long it ran.
 * @param at When it ended, in the time of the tracker that saw it.
 */
public data class FinishedCommand(
    val line: String?,
    val outcome: TerminalCommandOutcome,
    val exitCode: Int?,
    val durationMs: Long,
    val at: Long,
) {
    /** Whether it ended with an exit code other than 0 (and was not stopped). */
    val failed: Boolean get() = outcome == TerminalCommandOutcome.FAILED
}

/**
 * A terminal that is asking for the user's attention, with where it is: for what its agent says ([info]), or for a command that ended in it
 * ([command]) when its agent says nothing that needs them.
 */
public data class AttentionItem(
    val terminal: TerminalSpec,
    val project: Project,
    val workspace: Workspace,
    val info: AgentInfo?,
    val command: FinishedCommand? = null,
) {
    /** The state that it is shown in: a command that ended is done, or failed. */
    val state: AgentState get() = info?.state ?: if (command?.failed == true) AgentState.ERROR else AgentState.DONE

    /** Since when it needs the user. */
    val since: Long get() = info?.since ?: command?.at ?: 0
}

/**
 * Who needs the user, and who finished: what the bell lists. A terminal that finished needs the user as much as one that waits: what it did is
 * there to be looked at, and it stays until the user has (see `AgentTracker.markSeen`).
 *
 * @param needsYou The terminals whose agent waits for the user, or failed, the one that has waited for the shortest time first.
 * @param finished The terminals whose agent finished, or where a command ended (failed or not), that nobody has seen, the one that finished last
 * first.
 */
public data class Attention(val needsYou: List<AttentionItem>, val finished: List<AttentionItem>) {
    /** The number that the bell shows: the terminals that need the user, the ones that finished too. */
    val count: Int get() = needsYou.size + finished.size

    /**
     * The terminal to go to for "jump to the next terminal that needs you": the one that follows [from] among the ones that wait and the ones that
     * finished, in the order that the sidebar lists them, and the first of them when [from] is the last, or is not one of them. `null` when none
     * needs the user.
     */
    public fun next(state: KattyState, from: TerminalId?): TerminalId? {
        val needing = (needsYou + finished).map { it.terminal.id }.toSet()
        if (needing.isEmpty()) return null
        val order = state.allTerminals().map { it.terminal.id }.filter { it in needing }
        val index = order.indexOf(from)
        return if (index < 0) order.first() else order[(index + 1) % order.size]
    }
}

/**
 * What needs attention among the terminals of this state, given what each says ([infos]) and the commands that ended where the user was not looking
 * ([commands]). A terminal is listed once: for what its agent says when it needs the user, else for its command.
 */
public fun KattyState.attention(infos: Map<TerminalId, AgentInfo>, commands: Map<TerminalId, FinishedCommand> = emptyMap()): Attention {
    val items = allTerminals().mapNotNull { location ->
        val id = location.terminal.id
        val info = infos[id]?.takeIf { it.state in ATTENTION_STATES }
        val command = commands[id]
        when {
            info != null -> AttentionItem(location.terminal, location.project, location.workspace, info)
            command != null -> AttentionItem(location.terminal, location.project, location.workspace, null, command)
            else -> null
        }
    }
    return Attention(
        needsYou = items.filter { it.info != null && it.state != AgentState.DONE }.sortedByDescending { it.since },
        finished = items.filter { it.info == null || it.state == AgentState.DONE }.sortedByDescending { it.since },
    )
}

private val ATTENTION_STATES = setOf(AgentState.NEEDS_INPUT, AgentState.ERROR, AgentState.DONE)

/**
 * How long ago, as the board and the list of attention say it: `now` under a minute, then `2 min`, `3 h`, `4 d`.
 */
public fun relativeTime(now: Long, since: Long): String {
    val minutes = ((now - since) / 60_000).coerceAtLeast(0)
    return when {
        minutes < 1 -> "now"
        minutes < 60 -> "$minutes min"
        minutes < 24 * 60 -> "${minutes / 60} h"
        else -> "${minutes / (24 * 60)} d"
    }
}
