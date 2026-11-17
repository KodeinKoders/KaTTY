package app.katty.terminal

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle

// The processes of a terminal's session: the program that pty4j starts makes a session of its own (`setsid`, which is how the pty becomes its
// controlling terminal), so the session's id is the program's pid, and every process that it starts is in it, unless it leaves it (a daemon calls
// `setsid` itself: Gradle's does). The id stays the session's once its leader has ended.

// `int proc_listpids(uint32_t type, uint32_t typeinfo, void *buffer, int buffersize)`
private val procListAllPids: MethodHandle by lazy {
    libc(
        "proc_listpids",
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT),
    )
}

// `pid_t getsid(pid_t pid)`: -1 for a process that is gone, or that macOS does not tell about.
private val getsid: MethodHandle by lazy { libc("getsid", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT)) }

// `int kill(pid_t pid, int sig)`
private val kill: MethodHandle by lazy { libc("kill", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT)) }

private const val PROC_ALL_PIDS = 1
private const val SIGKILL = 9

/** The pids of the processes whose session is [session]: empty once they have all ended. */
internal fun sessionPids(session: Int): List<Int> {
    if (session <= 0) return emptyList()
    Arena.ofConfined().use { arena ->
        // The system says how many bytes the list takes; it may have grown by the time it is read, so there is room for more.
        val needed = procListAllPids.invokeExact(PROC_ALL_PIDS, 0, java.lang.foreign.MemorySegment.NULL, 0) as Int
        if (needed <= 0) return emptyList()
        val size = needed + 256 * 4
        val buffer = arena.allocate(size.toLong())
        val bytes = procListAllPids.invokeExact(PROC_ALL_PIDS, 0, buffer, size) as Int
        if (bytes <= 0) return emptyList()
        return (0 until bytes / 4).map { buffer.getAtIndex(ValueLayout.JAVA_INT, it.toLong()) }
            .filter { it > 0 && getsid.invokeExact(it) as Int == session }
    }
}

/** Kills [pid] (`SIGKILL`): what ignored the hang-up and was waited for long enough. */
internal fun killProcess(pid: Int) {
    kill.invokeExact(pid, SIGKILL) as Int
}
