package app.katty.terminal.platform.windows

import app.katty.terminal.platform.ProcessInspector
import app.katty.terminal.platform.PtyControl
import app.katty.terminal.platform.TerminalPlatform
import com.pty4j.PtyProcess

/**
 * Windows, through ConPTY: a pseudoconsole has no termios to tell a password prompt, its size is set in cells only (by pty4j), and there is no
 * hang-up, so a program is ended as pty4j ends it. What runs in a terminal is read from the processes that the shell started
 * ([WindowsProcessInspector]).
 */
internal object WindowsTerminalPlatform : TerminalPlatform {
    override val pty: PtyControl = object : PtyControl {
        override fun readingPassword(process: PtyProcess): Boolean = false

        // A console has modes of its own, which ConPTY does not show.
        override fun readingKeys(process: PtyProcess): Boolean? = null

        override fun setWindowSize(process: PtyProcess, cols: Int, rows: Int, widthPx: Int, heightPx: Int): Boolean = false

        override fun hangUp(process: PtyProcess) {
            process.destroy()
        }

        override fun closePty(process: PtyProcess) {}
    }

    override val processes: ProcessInspector = WindowsProcessInspector

    // What `hostname` says, and what the integration of PowerShell puts in the address of its directory.
    override fun hostName(): String? = System.getenv("COMPUTERNAME")?.takeIf { it.isNotEmpty() }

    // A program inherits whether this process ignores Control + C (see processControlC).
    override fun beforeStart() = processControlC()
}
