package app.katty.terminal

import org.slf4j.LoggerFactory
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle

private val logger = LoggerFactory.getLogger("app.katty.terminal.ForegroundProcess")

@Volatile
private var warned = false

// `pid_t tcgetpgrp(int fd)`: the process group that has the pty, which is the one of the program that the shell is waiting for. A shell with
// job control gives each command a group of its own, so the group's id is the pid of the command.
private val tcgetpgrp: MethodHandle by lazy {
    libc("tcgetpgrp", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT))
}

// `int proc_name(int pid, void *buffer, uint32_t size)`, of libproc (macOS): the name that the kernel keeps of a process, which is the name
// of its program (`emacs`, `zsh`, `sleep`), with no path and no arguments.
private val procName: MethodHandle by lazy {
    libc("proc_name", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT))
}

private const val NAME_BUFFER_BYTES = 256

/** The id of the process group that has the pty whose master is [fd], `0` or less when none does. */
internal fun foregroundGroup(fd: Int): Int = tcgetpgrp.invokeExact(fd) as Int

/**
 * The name of the program that has the pty whose master is [fd]: what the shell is waiting for, or the shell itself at its prompt.
 * `null` when it cannot be known (nothing has the pty any more, or the system does not say). It never throws: it is asked about every
 * terminal, often.
 */
internal fun foregroundProcessName(fd: Int): String? =
    try {
        val group = tcgetpgrp.invokeExact(fd) as Int
        if (group <= 0) {
            null
        } else {
            Arena.ofConfined().use { arena ->
                val buffer = arena.allocate(NAME_BUFFER_BYTES.toLong())
                val length = procName.invokeExact(group, buffer, NAME_BUFFER_BYTES) as Int
                if (length <= 0) null else buffer.getString(0).removePrefix("-").takeIf { it.isNotEmpty() }
            }
        }
    } catch (e: Throwable) {
        if (!warned) {
            warned = true
            logger.warn("Could not read the foreground process of a pty", e)
        }
        null
    }
