package app.katty.terminal

import org.slf4j.LoggerFactory
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle

private val logger = LoggerFactory.getLogger("app.katty.terminal.OpenFiles")

@Volatile
private var warned = false

private val linker = Linker.nativeLinker()

// `int proc_pidinfo(int pid, int flavor, uint64_t arg, void *buffer, int buffersize)`
private val procPidInfo: MethodHandle by lazy {
    linker.downcallHandle(
        linker.defaultLookup().find("proc_pidinfo").orElseThrow(),
        FunctionDescriptor.of(
            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
        ),
    )
}

// `int proc_pidfdinfo(int pid, int fd, int flavor, void *buffer, int buffersize)`
private val procPidFdInfo: MethodHandle by lazy {
    linker.downcallHandle(
        linker.defaultLookup().find("proc_pidfdinfo").orElseThrow(),
        FunctionDescriptor.of(
            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
        ),
    )
}

// The values and the layouts of macOS's `libproc.h`.
private const val PROC_PIDLISTFDS = 1
private const val PROC_PIDFDVNODEPATHINFO = 2
private const val PROX_FDTYPE_VNODE = 1

// `struct proc_fdinfo` (8 bytes): the descriptor, and its type.
private const val FDINFO_SIZE = 8
private const val FDINFO_FD = 0L
private const val FDINFO_TYPE = 4L

// `struct vnode_fdinfowithpath` (1200 bytes): a `proc_fileinfo` (24), a `vnode_info` (152), then the path.
private const val VNODE_FDINFO_SIZE = 1200
private const val VNODE_FDINFO_PATH = 176L
private const val MAX_PATH = 1024

// Room for the descriptors of a process, which an agent that watches files can have by the thousand.
private const val MAX_FDS = 16_384

/**
 * The paths of the files that the process [pid] has open (the ones that have a path: not sockets or pipes), or an empty list when it is
 * gone, or is not ours to read. It never throws: it is asked about every agent that runs.
 *
 * It is how a program that says nothing of its own conversation can be told from what it holds: a file it opened for it.
 */
public fun openFilePaths(pid: Int): List<String> =
    try {
        Arena.ofConfined().use { arena ->
            val fds = listFds(arena, pid)
            val info = arena.allocate(VNODE_FDINFO_SIZE.toLong())
            buildList {
                for (index in 0 until fds.byteSize() / FDINFO_SIZE) {
                    val base = index * FDINFO_SIZE.toLong()
                    if (fds.get(ValueLayout.JAVA_INT, base + FDINFO_TYPE) != PROX_FDTYPE_VNODE) continue
                    val fd = fds.get(ValueLayout.JAVA_INT, base + FDINFO_FD)
                    // A descriptor that was closed since the list was made is not an error.
                    if (procPidFdInfo.invokeExact(pid, fd, PROC_PIDFDVNODEPATHINFO, info, VNODE_FDINFO_SIZE) as Int != VNODE_FDINFO_SIZE) continue
                    info.asSlice(VNODE_FDINFO_PATH, MAX_PATH.toLong()).getString(0).takeIf { it.isNotEmpty() }?.let(::add)
                }
            }
        }
    } catch (e: Throwable) {
        if (!warned) {
            warned = true
            logger.warn("Could not read the open files of a process", e)
        }
        emptyList()
    }

// The descriptors of [pid], as the bytes of an array of `proc_fdinfo`.
private fun listFds(arena: Arena, pid: Int): MemorySegment {
    val needed = procPidInfo.invokeExact(pid, PROC_PIDLISTFDS, 0L, MemorySegment.NULL, 0) as Int
    if (needed <= 0) return MemorySegment.NULL.reinterpret(0)
    // It may grow between the two calls: room for that.
    val capacity = minOf(needed + 64 * FDINFO_SIZE, MAX_FDS * FDINFO_SIZE)
    val buffer = arena.allocate(capacity.toLong())
    val filled = procPidInfo.invokeExact(pid, PROC_PIDLISTFDS, 0L, buffer, capacity) as Int
    return buffer.asSlice(0, filled.coerceAtLeast(0).toLong())
}
