package app.katty.terminal

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle

// ioctl(TIOCPTYGNAME): the request that gives the name of the slave of a pty from its master, as `/dev/ttys018`. It is `_IOC(IOC_OUT, 't', 83, 128)`, and it is
// the value of macOS (the buffer is 128 bytes).
private const val TIOCPTYGNAME = 0x40807453L
private const val NAME_BYTES = 128L

// `int ioctl(int fd, unsigned long request, ...)`: the pointer to the buffer is the first variadic argument.
private val ioctl: MethodHandle by lazy {
    val linker = Linker.nativeLinker()
    linker.downcallHandle(
        linker.defaultLookup().find("ioctl").orElseThrow(),
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS),
        Linker.Option.firstVariadicArg(2),
    )
}

/**
 * The name of the terminal that the pty whose master is [fd] is, without `/dev/` (`ttys018`): what a program that is in it calls its terminal (`ttyname`), and
 * what some of them, such as omp, keep a file by. `null` when it cannot be known. It never throws: it is asked of every terminal.
 */
internal fun ptyName(fd: Int): String? =
    try {
        Arena.ofConfined().use { arena ->
            val buffer = arena.allocate(NAME_BYTES)
            if (ioctl.invokeExact(fd, TIOCPTYGNAME, buffer) as Int == 0) buffer.getString(0).removePrefix("/dev/").takeIf { it.isNotEmpty() } else null
        }
    } catch (e: Throwable) {
        null
    }
