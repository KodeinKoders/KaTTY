package app.katty.core

/**
 * What a session of an agent has used.
 *
 * @param contextPercent How much of the model's context window the last answer filled, 0 to 100, or `null` when it is not known (no answer yet,
 * or a model whose window is not known).
 * @param costUsd What the session cost in dollars, with the sessions it started, or `null` when it is not known.
 */
public data class SessionUsage(val contextPercent: Int?, val costUsd: Double?)

/**
 * Where an agent keeps what its sessions used, read without asking the agent anything and without a credential. One for each agent that has
 * somewhere to read it from, so that others can be added; only OpenCode has one.
 */
public interface SessionUsageReader {
    /** The session of the program that runs in [cwd] and started at [startedAtMs], or `null` when there is none or it cannot be told. */
    public fun sessionFor(cwd: String, startedAtMs: Long): String?

    /** When session [id] last changed, in milliseconds since the epoch: cheap, so that the session is only read again when it moved. */
    public fun updatedAt(id: String): Long?

    /** What session [id] has used, `null` when it cannot be read. */
    public fun read(id: String): SessionUsage?
}

/** OpenCode's, from its database ([OpenCodeStore]). */
public class OpenCodeUsageReader internal constructor(private val store: OpenCodeStore) : SessionUsageReader {
    public constructor() : this(OpenCodeStore())

    override fun sessionFor(cwd: String, startedAtMs: Long): String? = store.sessionFor(cwd, startedAtMs)

    override fun updatedAt(id: String): Long? = store.updatedAt(id)

    override fun read(id: String): SessionUsage? = store.usage(id)
}

/** The readers of the agents that have one. */
public fun sessionUsageReaders(): Map<Agent, SessionUsageReader> = mapOf(Agent.OPENCODE to OpenCodeUsageReader())

/**
 * What the open sessions of every agent cost together.
 *
 * @param sessions How many terminals have a session that was read.
 * @param usd The sum of the costs that are known, `null` when no session says its cost.
 */
public data class OpenSessions(val sessions: Int, val usd: Double?)

/**
 * The total of the sessions of the terminals that are in this state, whatever the agent: a terminal that was closed has left it, and so has one
 * whose agent ended (the tracker drops it). `null` when there is no session to count.
 */
public fun KattyState.openSessionsCost(usages: Map<TerminalId, SessionUsage>): OpenSessions? {
    val open = allTerminals().mapNotNull { usages[it.terminal.id] }
    if (open.isEmpty()) return null
    val costs = open.mapNotNull { it.costUsd }
    return OpenSessions(open.size, if (costs.isEmpty()) null else costs.sum())
}

/** What [Settings.usageHidden] keeps when the user hides the total of the open sessions: it belongs to no agent. */
public const val OPEN_SESSIONS_KEY: String = "open-sessions"
