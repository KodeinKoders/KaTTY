package app.katty.terminal.platform.posix

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemoryLayout
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle

// The functions of the C library that the terminal calls through FFM on a Unix system. One handle for each, which the files that call it share:
// a function that is declared twice can be declared two ways.

private val linker = Linker.nativeLinker()

/** A handle on the function [name] of the C library, which the linker finds in the process. */
internal fun libc(name: String, descriptor: FunctionDescriptor, vararg options: Linker.Option): MethodHandle =
    linker.downcallHandle(linker.defaultLookup().find(name).orElseThrow(), descriptor, *options)

/** Where `errno` is in the call state of [ioctl]: the state is a segment of `Linker.Option.captureStateLayout()`. */
internal val errnoOffset: Long = Linker.Option.captureStateLayout().byteOffset(MemoryLayout.PathElement.groupElement("errno"))

/**
 * `int ioctl(int fd, unsigned long request, ...)` with a pointer as its only variadic argument (on Apple Silicon variadic arguments are not passed
 * as the fixed ones are), and `errno` captured: it is called as `ioctl(state, fd, request, pointer)`, `state` a segment of the call state layout.
 */
internal val ioctl: MethodHandle by lazy {
    libc(
        "ioctl",
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS),
        Linker.Option.firstVariadicArg(2),
        Linker.Option.captureCallState("errno"),
    )
}

// `pid_t tcgetpgrp(int fd)`: the process group that has the pty, which is the one of the program that the shell is waiting for. A shell with
// job control gives each command a group of its own, so the group's id is the pid of the command.
private val tcgetpgrp: MethodHandle by lazy {
    libc("tcgetpgrp", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT))
}

/** The id of the process group that has the pty whose master is [fd], `0` or less when none does. */
internal fun foregroundGroup(fd: Int): Int = tcgetpgrp.invokeExact(fd) as Int

// `pid_t getsid(pid_t pid)`: -1 for a process that is gone, or that the system does not tell about.
private val getsid: MethodHandle by lazy { libc("getsid", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT)) }

/** The id of the session of [pid], `-1` when it is gone or not ours to know. */
internal fun sessionOf(pid: Int): Int = getsid.invokeExact(pid) as Int

// `int kill(pid_t pid, int sig)`
private val kill: MethodHandle by lazy { libc("kill", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT)) }

private const val SIGKILL = 9

/** Kills [pid] (`SIGKILL`). */
internal fun killProcess(pid: Int) {
    kill.invokeExact(pid, SIGKILL) as Int
}

private const val HOST_NAME_BYTES = 256L

// `int gethostname(char *name, size_t namelen)`.
private val gethostname: MethodHandle by lazy {
    libc("gethostname", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG))
}

/** The name of this machine as the system has it now (`gethostname`), `null` when it cannot be read. */
internal fun readHostName(): String? {
    return try {
        Arena.ofConfined().use { arena ->
            val buffer = arena.allocate(HOST_NAME_BYTES)
            if (gethostname.invokeExact(buffer, HOST_NAME_BYTES) as Int == 0) buffer.getString(0).takeIf { it.isNotEmpty() } else null
        }
    } catch (e: Throwable) {
        null
    }
}
