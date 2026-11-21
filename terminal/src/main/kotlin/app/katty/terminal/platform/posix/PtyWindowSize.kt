package app.katty.terminal.platform.posix

import org.slf4j.LoggerFactory
import java.lang.foreign.Arena
import java.lang.foreign.Linker
import java.lang.foreign.ValueLayout

private val logger = LoggerFactory.getLogger("app.katty.terminal.platform.posix.PtyWindowSize")

private const val MAX_UNSIGNED_SHORT = 0xFFFF

// A drag of the window resizes it many times: once is enough to say that it does not work.
@Volatile
private var warned = false

/**
 * Sets the window size of the pty whose master is [fd], in cells and in pixels, and returns whether it worked. [request] is the system's
 * `TIOCSWINSZ`: the request that sets the window size of a pty, which is the one that programs read with `TIOCGWINSZ`.
 *
 * pty4j only sets the size in cells: the fields in pixels stay at 0, and the programs that read them from
 * the pty (`kitten icat`, `timg`…) could not size what they draw. The kernel sends the program a SIGWINCH
 * when the size changes.
 *
 * It never throws: the caller has the size in cells to fall back to.
 */
internal fun setPtyWindowSize(fd: Int, request: Long, cols: Int, rows: Int, widthPx: Int, heightPx: Int): Boolean =
    try {
        Arena.ofConfined().use { arena ->
            // struct winsize { unsigned short ws_row, ws_col, ws_xpixel, ws_ypixel; }
            val size = arena.allocate(4 * ValueLayout.JAVA_SHORT.byteSize(), ValueLayout.JAVA_SHORT.byteAlignment())
            size.setAtIndex(ValueLayout.JAVA_SHORT, 0, rows.coerceIn(0, MAX_UNSIGNED_SHORT).toShort())
            size.setAtIndex(ValueLayout.JAVA_SHORT, 1, cols.coerceIn(0, MAX_UNSIGNED_SHORT).toShort())
            size.setAtIndex(ValueLayout.JAVA_SHORT, 2, widthPx.coerceIn(0, MAX_UNSIGNED_SHORT).toShort())
            size.setAtIndex(ValueLayout.JAVA_SHORT, 3, heightPx.coerceIn(0, MAX_UNSIGNED_SHORT).toShort())

            val state = arena.allocate(Linker.Option.captureStateLayout())
            val result = ioctl.invokeExact(state, fd, request, size) as Int
            if (result != 0 && !warned) {
                warned = true
                logger.warn("ioctl(TIOCSWINSZ) failed with errno {}", state.get(ValueLayout.JAVA_INT, errnoOffset))
            }
            result == 0
        }
    } catch (e: Throwable) {
        if (!warned) {
            warned = true
            logger.warn("Could not set the window size of the pty", e)
        }
        false
    }
