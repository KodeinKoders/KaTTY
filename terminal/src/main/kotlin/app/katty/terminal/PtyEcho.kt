package app.katty.terminal

import org.slf4j.LoggerFactory
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle

private val logger = LoggerFactory.getLogger("app.katty.terminal.PtyEcho")

// The size of `struct termios` of macOS: four flag words of 8 bytes, 20 control characters, and two speeds of 8 bytes.
private const val TERMIOS_SIZE = 72L

// Where the local flags are in it, and the bits of them that tell a password prompt (the values of macOS: Linux has others).
private const val LFLAG_OFFSET = 24L
private const val ECHO = 0x8L
private const val ICANON = 0x100L

@Volatile
private var warned = false

private val tcgetattr: MethodHandle by lazy {
    val linker = Linker.nativeLinker()
    linker.downcallHandle(
        linker.defaultLookup().find("tcgetattr").orElseThrow(),
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS),
    )
}

/**
 * Whether the program that has the pty whose master is [fd] is reading a password: it turned the echo off and left the line
 * discipline on, which is what `sudo`, `ssh` and `passwd` do to read a line that is not shown. A program that reads a key at a time (vim,
 * a menu) turns both off. It is how Ghostty finds a password prompt, since the terminal itself cannot tell.
 *
 * It never throws: when the flags cannot be read it is not a password prompt.
 */
internal fun isPtyReadingPassword(fd: Int): Boolean =
    try {
        Arena.ofConfined().use { arena ->
            val termios = arena.allocate(TERMIOS_SIZE)
            if (tcgetattr.invokeExact(fd, termios) as Int != 0) {
                false
            } else {
                val local = termios.get(ValueLayout.JAVA_LONG, LFLAG_OFFSET)
                local and ECHO == 0L && local and ICANON != 0L
            }
        }
    } catch (e: Throwable) {
        if (!warned) {
            warned = true
            logger.warn("Could not read the flags of the pty", e)
        }
        false
    }
