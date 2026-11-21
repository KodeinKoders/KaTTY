package app.katty.terminal.platform.macos

import app.katty.terminal.platform.posix.sessionOf
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout

// The processes of a terminal's session: the program that pty4j starts makes a session of its own (`setsid`, which is how the pty becomes its
// controlling terminal), so the session's id is the program's pid, and every process that it starts is in it, unless it leaves it (a daemon calls
// `setsid` itself: Gradle's does). The id stays the session's once its leader has ended.

private const val PROC_ALL_PIDS = 1

/** The pids of the processes whose session is [session]: empty once they have all ended. */
internal fun sessionPids(session: Int): List<Int> {
    if (session <= 0) return emptyList()
    Arena.ofConfined().use { arena ->
        // The system says how many bytes the list takes; it may have grown by the time it is read, so there is room for more.
        val needed = procListPids.invokeExact(PROC_ALL_PIDS, 0, MemorySegment.NULL, 0) as Int
        if (needed <= 0) return emptyList()
        val size = needed + 256 * 4
        val buffer = arena.allocate(size.toLong())
        val bytes = procListPids.invokeExact(PROC_ALL_PIDS, 0, buffer, size) as Int
        if (bytes <= 0) return emptyList()
        return (0 until bytes / 4).map { buffer.getAtIndex(ValueLayout.JAVA_INT, it.toLong()) }
            .filter { it > 0 && sessionOf(it) == session }
    }
}
