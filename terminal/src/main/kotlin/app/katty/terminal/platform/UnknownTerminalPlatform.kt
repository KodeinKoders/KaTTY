package app.katty.terminal.platform

import app.katty.terminal.ForegroundCommand
import com.pty4j.PtyProcess

/**
 * A system that the terminal has no implementation for: what needs the system is not there (no program is known to have the terminal, no
 * password prompt is seen, the size in pixels is not given), and the rest works. Ending a program is pty4j's own `destroy`.
 */
internal object UnknownTerminalPlatform : TerminalPlatform {
    override val pty: PtyControl = object : PtyControl {
        override fun readingPassword(process: PtyProcess): Boolean = false

        override fun readingKeys(process: PtyProcess): Boolean? = null

        override fun setWindowSize(process: PtyProcess, cols: Int, rows: Int, widthPx: Int, heightPx: Int): Boolean = false

        override fun hangUp(process: PtyProcess) {
            process.destroy()
        }

        override fun closePty(process: PtyProcess) {}
    }

    override val processes: ProcessInspector = object : ProcessInspector {
        override fun foregroundProcessName(process: PtyProcess): String? = null

        override fun foregroundCommand(process: PtyProcess): ForegroundCommand? = null

        override fun sessionPids(session: Int, since: Long): List<Int> = emptyList()

        override fun kill(pid: Int) {}

        override fun openFilePaths(pid: Int): List<String> = emptyList()
    }

    override fun hostName(): String? = null
}
