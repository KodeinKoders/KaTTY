package app.katty.terminal.platform.macos

import app.katty.terminal.ForegroundCommand
import app.katty.terminal.platform.posix.foregroundGroup
import app.katty.terminal.platform.posix.libc
import org.slf4j.LoggerFactory
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle

private val logger = LoggerFactory.getLogger("app.katty.terminal.platform.macos.ForegroundCommands")

@Volatile
private var warned = false

// `int sysctl(int *name, u_int namelen, void *oldp, size_t *oldlenp, void *newp, size_t newlen)`
private val sysctl: MethodHandle by lazy {
    libc(
        "sysctl",
        FunctionDescriptor.of(
            ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
            ValueLayout.JAVA_LONG,
        ),
    )
}

// `int proc_pidpath(int pid, void *buffer, uint32_t buffersize)`
private val procPidPath: MethodHandle by lazy {
    libc("proc_pidpath", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT))
}

// The values and the layouts of macOS's `sys/sysctl.h` and `libproc.h`.
private const val CTL_KERN = 1
private const val KERN_PROCARGS2 = 49
private const val PROC_PIDTBSDINFO = 3
private const val PROC_PIDVNODEPATHINFO = 9
private const val PROC_PGRP_ONLY = 2

// `struct proc_bsdinfo` (136 bytes): where the parent's pid and the start time are.
private const val BSDINFO_SIZE = 136
private const val BSDINFO_PPID = 16L
private const val BSDINFO_START_SEC = 120L
private const val BSDINFO_START_USEC = 128L

// `struct proc_vnodepathinfo`: the current directory's `vnode_info_path` (a `vnode_info` of 152 bytes, then the path) and the root's.
private const val VNODEPATHINFO_SIZE = 2352
private const val VNODEPATHINFO_CDIR_PATH = 152L
private const val MAX_PATH = 1024

// `PROC_PIDPATHINFO_MAXSIZE`: four times the longest path.
private const val PIDPATH_MAX = 4096

private const val MAX_GROUP_PIDS = 512

/**
 * The program that has the pty whose master is [fd], or `null` when it is the shell [shellPid] itself (at its prompt), or when it cannot be known.
 * It never throws: it is asked about every terminal.
 */
internal fun foregroundCommand(fd: Int, shellPid: Int): ForegroundCommand? =
    try {
        val group = foregroundGroup(fd)
        if (group <= 0 || group == shellPid) {
            null
        } else {
            Arena.ofConfined().use { arena ->
                val argv = commandLine(arena, group)
                if (argv.isEmpty()) {
                    null
                } else {
                    ForegroundCommand(
                        pid = group,
                        argv = argv,
                        directory = currentDirectory(arena, group),
                        startedAtMillis = startTime(arena, group),
                        pipeline = isPipeline(arena, group, shellPid),
                        executable = executablePath(arena, group),
                        groupPids = groupPids(arena, group),
                        tty = ptyName(fd),
                    )
                }
            }
        }
    } catch (e: Throwable) {
        if (!warned) {
            warned = true
            logger.warn("Could not read the command of the foreground process of a pty", e)
        }
        null
    }

/**
 * The command line of [pid]: `KERN_PROCARGS2` holds the argument count, the path of the executable, some padding, then the arguments and the
 * environment, each ended by a null. Empty when the process is gone or is not ours to read.
 */
internal fun commandLine(arena: Arena, pid: Int): List<String> {
    val name = arena.allocate(3 * 4L)
    name.setAtIndex(ValueLayout.JAVA_INT, 0, CTL_KERN)
    name.setAtIndex(ValueLayout.JAVA_INT, 1, KERN_PROCARGS2)
    name.setAtIndex(ValueLayout.JAVA_INT, 2, pid)
    val size = arena.allocate(ValueLayout.JAVA_LONG)
    if (sysctl.invokeExact(name, 3, MemorySegment.NULL, size, MemorySegment.NULL, 0L) as Int != 0) return emptyList()
    // It may grow between the two calls: room for that.
    val capacity = size.get(ValueLayout.JAVA_LONG, 0) + 4096
    val buffer = arena.allocate(capacity)
    size.set(ValueLayout.JAVA_LONG, 0, capacity)
    if (sysctl.invokeExact(name, 3, buffer, size, MemorySegment.NULL, 0L) as Int != 0) return emptyList()
    val length = size.get(ValueLayout.JAVA_LONG, 0)
    return try {
        parseProcessArguments(buffer, length).arguments
    } finally {
        // The environment follows the arguments, with the secrets that programs are given: it is not left in memory that is freed.
        buffer.fill(0)
    }
}

/** What [parseProcessArguments] read: the arguments, and the offset just past the last one, which nothing after was read from. */
internal class ProcessArguments(val arguments: List<String>, val end: Long)

/**
 * The arguments of the first [length] bytes of a `KERN_PROCARGS2` buffer: what follows the executable's path and its padding, as many as the
 * count at the start says. Each one is copied on its own, and what follows the last (the environment, which holds secrets) is not read.
 */
internal fun parseProcessArguments(buffer: MemorySegment, length: Long): ProcessArguments {
    if (length < 4) return ProcessArguments(emptyList(), 0)
    // The count is the machine's byte order, little-endian on Apple Silicon.
    val count = buffer.get(ValueLayout.JAVA_INT_UNALIGNED.withOrder(java.nio.ByteOrder.LITTLE_ENDIAN), 0)
    fun at(index: Long) = buffer.get(ValueLayout.JAVA_BYTE, index).toInt()
    var index = 4L
    // The path of the executable.
    while (index < length && at(index) != 0) index++
    // The nulls that pad it.
    while (index < length && at(index) == 0) index++
    val arguments = ArrayList<String>(count.coerceIn(0, 4096))
    while (arguments.size < count && index < length) {
        val start = index
        while (index < length && at(index) != 0) index++
        arguments += String(buffer.asSlice(start, index - start).toArray(ValueLayout.JAVA_BYTE), Charsets.UTF_8)
        index++
    }
    return ProcessArguments(arguments, minOf(index, length))
}

/** The arguments of a `KERN_PROCARGS2` buffer, as [parseProcessArguments] reads them. */
internal fun parseProcessArguments(bytes: ByteArray): List<String> =
    parseProcessArguments(MemorySegment.ofArray(bytes), bytes.size.toLong()).arguments

private fun currentDirectory(arena: Arena, pid: Int): String? {
    val info = arena.allocate(VNODEPATHINFO_SIZE.toLong())
    if (procPidInfo.invokeExact(pid, PROC_PIDVNODEPATHINFO, 0L, info, VNODEPATHINFO_SIZE) as Int != VNODEPATHINFO_SIZE) return null
    return info.asSlice(VNODEPATHINFO_CDIR_PATH, MAX_PATH.toLong()).getString(0).takeIf { it.isNotEmpty() }
}

private fun executablePath(arena: Arena, pid: Int): String? {
    val buffer = arena.allocate(PIDPATH_MAX.toLong())
    return if (procPidPath.invokeExact(pid, buffer, PIDPATH_MAX) as Int > 0) buffer.getString(0).takeIf { it.isNotEmpty() } else null
}

private fun bsdInfo(arena: Arena, pid: Int): MemorySegment? {
    val info = arena.allocate(BSDINFO_SIZE.toLong())
    return if (procPidInfo.invokeExact(pid, PROC_PIDTBSDINFO, 0L, info, BSDINFO_SIZE) as Int == BSDINFO_SIZE) info else null
}

private fun startTime(arena: Arena, pid: Int): Long {
    val info = bsdInfo(arena, pid) ?: return 0L
    return info.get(ValueLayout.JAVA_LONG, BSDINFO_START_SEC) * 1000 + info.get(ValueLayout.JAVA_LONG, BSDINFO_START_USEC) / 1000
}

// The shell starts every program of a pipeline itself, in one group: more than one member of the group is its child. A program's own children
// (what an agent runs) have the program as their parent.
private fun isPipeline(arena: Arena, group: Int, shellPid: Int): Boolean {
    val buffer = arena.allocate(MAX_GROUP_PIDS * 4L)
    val bytes = procListPids.invokeExact(PROC_PGRP_ONLY, group, buffer, MAX_GROUP_PIDS * 4) as Int
    if (bytes <= 0) return false
    var shellChildren = 0
    for (index in 0 until bytes / 4) {
        val member = buffer.getAtIndex(ValueLayout.JAVA_INT, index.toLong())
        val info = bsdInfo(arena, member) ?: continue
        if (info.get(ValueLayout.JAVA_INT, BSDINFO_PPID) == shellPid) shellChildren++
    }
    return shellChildren > 1
}

// Every process whose group is [group].
private fun groupPids(arena: Arena, group: Int): List<Int> {
    val buffer = arena.allocate(MAX_GROUP_PIDS * 4L)
    val bytes = procListPids.invokeExact(PROC_PGRP_ONLY, group, buffer, MAX_GROUP_PIDS * 4) as Int
    if (bytes <= 0) return emptyList()
    return List(bytes / 4) { buffer.getAtIndex(ValueLayout.JAVA_INT, it.toLong()) }.filter { it > 0 }
}
