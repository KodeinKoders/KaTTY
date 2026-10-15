package app.katty.core

/** A terminal that is asking for the user's attention, with where it is. */
public data class AttentionItem(val terminal: TerminalSpec, val project: Project, val workspace: Workspace, val info: AgentInfo)

/**
 * Who needs the user, and who finished: what the bell lists.
 *
 * @param needsYou The terminals whose agent waits for the user, or failed, the one that has waited for the shortest time first.
 * @param finished The terminals whose agent finished and that nobody has seen, the one that finished last first.
 */
public data class Attention(val needsYou: List<AttentionItem>, val finished: List<AttentionItem>) {
    /** The number that the bell shows: the terminals that need the user. */
    val count: Int get() = needsYou.size

    /**
     * The terminal to go to for "jump to the next agent that waits": the one that follows [from] among the ones that wait, in the order that
     * the sidebar lists them, and the first of them when [from] is the last, or is not one of them. `null` when none waits.
     */
    public fun nextWaiting(state: KattyState, from: TerminalId?): TerminalId? {
        val waiting = needsYou.map { it.terminal.id }.toSet()
        if (waiting.isEmpty()) return null
        val order = state.allTerminals().map { it.terminal.id }.filter { it in waiting }
        val index = order.indexOf(from)
        return if (index < 0) order.first() else order[(index + 1) % order.size]
    }
}

/** What needs attention among the terminals of this state, given what each says. */
public fun KattyState.attention(infos: Map<TerminalId, AgentInfo>): Attention {
    val items = allTerminals().mapNotNull { location ->
        val info = infos[location.terminal.id] ?: return@mapNotNull null
        AttentionItem(location.terminal, location.project, location.workspace, info)
    }
    return Attention(
        needsYou = items.filter { it.info.state == AgentState.NEEDS_INPUT || it.info.state == AgentState.ERROR }.sortedByDescending { it.info.since },
        finished = items.filter { it.info.state == AgentState.DONE }.sortedByDescending { it.info.since },
    )
}

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
