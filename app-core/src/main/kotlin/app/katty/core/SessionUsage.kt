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
