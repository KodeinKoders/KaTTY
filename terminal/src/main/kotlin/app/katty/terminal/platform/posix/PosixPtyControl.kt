package app.katty.terminal.platform.posix

import app.katty.terminal.platform.PtyControl
import com.pty4j.PtyProcess
import com.pty4j.unix.UnixPtyProcess

/**
 * The master side of the pty of [process], `null` when it is not a Unix pty or it is closed. The caller holds the session's lock on the pty, so
 * that the descriptor is not closed (and its number given to another file) while it is used.
 */
internal fun masterOf(process: PtyProcess): Int? = (process as? UnixPtyProcess)?.pty?.masterFD?.takeIf { it >= 0 }

/**
 * The pty of a Unix system, by its master descriptor: what differs from one system to the next is given.
 *
 * @param termios Where the system keeps the flags that tell a password prompt.
 * @param setWindowSize The system's `TIOCSWINSZ`.
 */
internal class PosixPtyControl(private val termios: TermiosLayout, private val setWindowSize: Long) : PtyControl {
    override fun readingPassword(process: PtyProcess): Boolean = masterOf(process)?.let { isPtyReadingPassword(it, termios) } ?: false

    override fun setWindowSize(process: PtyProcess, cols: Int, rows: Int, widthPx: Int, heightPx: Int): Boolean =
        masterOf(process)?.let { setPtyWindowSize(it, setWindowSize, cols, rows, widthPx, heightPx) } ?: false

    // SIGHUP to the program's group, as a terminal whose window closes sends: an interactive shell ignores SIGTERM, which `destroy` sends, and on
    // a hang-up it saves its history and hangs up its jobs.
    override fun hangUp(process: PtyProcess) {
        (process as? UnixPtyProcess)?.hangup() ?: process.destroy()
    }

    override fun closePty(process: PtyProcess) {
        (process as? UnixPtyProcess)?.pty?.close()
    }
}
