package app.katty.core

/**
 * The conversation that the agent of one terminal is in, kept for [keepMs] so that it is not looked for at every poll: finding it reads the agent's
 * files and databases (a log of 256 kB, every chat of a directory, a SQLite database), which twice a second for every terminal is a cost for nothing.
 * It is looked for again at once for another program (its pid or its start), another agent, or a session that the program reported (OSC 7501),
 * which is the program saying where it is.
 *
 * What it costs: a conversation that the agent changed to (`/new`) is seen up to [keepMs] late, so a quit in that time comes back on the one before.
 *
 * One for each terminal. Its calls do not overlap (the terminal's poll is sequential), and it is synchronized for the threads they run on.
 *
 * @param clock The time, in milliseconds, that only goes forward.
 */
public class SessionLookups(private val keepMs: Long = KEEP_MS, private val clock: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private data class Key(val agent: Agent, val pid: Int, val startedAt: Long, val reportedSession: String?)

    private var key: Key? = null
    private var found: String? = null
    private var foundAt = 0L

    /** The session that [find] gives for the agent [agent] that runs as [pid], which started at [startedAt]: what it gave last, while it is recent. */
    @Synchronized
    public fun sessionOf(agent: Agent, pid: Int, startedAt: Long, reportedSession: String?, find: () -> String?): String? {
        val now = clock()
        val wanted = Key(agent, pid, startedAt, reportedSession)
        if (wanted == key && now - foundAt < keepMs) return found
        found = find()
        key = wanted
        foundAt = now
        return found
    }

    public companion object {
        /** How long a conversation that was found is kept. */
        public const val KEEP_MS: Long = 5_000L
    }
}
