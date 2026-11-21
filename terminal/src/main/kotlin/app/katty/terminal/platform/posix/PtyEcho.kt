package app.katty.terminal.platform.posix

import org.slf4j.LoggerFactory
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle

private val logger = LoggerFactory.getLogger("app.katty.terminal.platform.posix.PtyEcho")

/**
 * Where a system keeps what tells a password prompt in its `struct termios`: each one has its own layout and its own values of the flags.
 *
 * @property size The size of `struct termios`.
 * @property localFlagsOffset Where the local flags (`c_lflag`) are in it, a word of [localFlagsBytes] bytes.
 * @property echo The bit of `ECHO` in them.
 * @property canonical The bit of `ICANON` in them.
 */
internal class TermiosLayout(val size: Long, val localFlagsOffset: Long, val localFlagsBytes: Int, val echo: Long, val canonical: Long)

@Volatile
private var warned = false

private val tcgetattr: MethodHandle by lazy {
    libc("tcgetattr", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS))
}

/**
 * Whether the program that has the pty whose master is [fd] is reading a password: it turned the echo off and left the line
 * discipline on, which is what `sudo`, `ssh` and `passwd` do to read a line that is not shown. A program that reads a key at a time (vim,
 * a menu) turns both off. It is how Ghostty finds a password prompt, since the terminal itself cannot tell.
 *
 * It never throws: when the flags cannot be read it is not a password prompt.
 */
internal fun isPtyReadingPassword(fd: Int, termios: TermiosLayout): Boolean =
    try {
        Arena.ofConfined().use { arena ->
            val attributes = arena.allocate(termios.size)
            if (tcgetattr.invokeExact(fd, attributes) as Int != 0) {
                false
            } else {
                val local = when (termios.localFlagsBytes) {
                    8 -> attributes.get(ValueLayout.JAVA_LONG, termios.localFlagsOffset)
                    else -> attributes.get(ValueLayout.JAVA_INT, termios.localFlagsOffset).toLong() and 0xFFFF_FFFFL
                }
                local and termios.echo == 0L && local and termios.canonical != 0L
            }
        }
    } catch (e: Throwable) {
        if (!warned) {
            warned = true
            logger.warn("Could not read the flags of the pty", e)
        }
        false
    }
