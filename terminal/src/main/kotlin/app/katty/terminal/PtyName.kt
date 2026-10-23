package app.katty.terminal

import java.lang.foreign.Arena
import java.lang.foreign.Linker

// ioctl(TIOCPTYGNAME): the request that gives the name of the slave of a pty from its master, as `/dev/ttys018`. It is `_IOC(IOC_OUT, 't', 83, 128)`, and it is
// the value of macOS (the buffer is 128 bytes).
private const val TIOCPTYGNAME = 0x40807453L
private const val NAME_BYTES = 128L

/**
 * The name of the terminal that the pty whose master is [fd] is, without `/dev/` (`ttys018`): what a program that is in it calls its terminal (`ttyname`), and
 * what some of them, such as omp, keep a file by. `null` when it cannot be known. It never throws: it is asked of every terminal.
 */
internal fun ptyName(fd: Int): String? =
    try {
        Arena.ofConfined().use { arena ->
            val buffer = arena.allocate(NAME_BYTES)
            val state = arena.allocate(Linker.Option.captureStateLayout())
            if (ioctl.invokeExact(state, fd, TIOCPTYGNAME, buffer) as Int == 0) buffer.getString(0).removePrefix("/dev/").takeIf { it.isNotEmpty() } else null
        }
    } catch (e: Throwable) {
        null
    }
