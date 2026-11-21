package app.katty.terminal.platform.windows

import app.katty.terminal.ForegroundCommand
import app.katty.terminal.platform.ProcessInspector
import app.katty.terminal.platform.PtyControl
import app.katty.terminal.platform.TerminalPlatform
import com.pty4j.PtyProcess

/**
 * Windows, through ConPTY: a pseudoconsole has no termios to tell a password prompt, its size is set in cells only (by pty4j), and there is no
 * hang-up, so a program is ended as pty4j ends it. What runs in a terminal is not known yet: Windows has no process group that has the console.
 */
internal object WindowsTerminalPlatform : TerminalPlatform {
    override val pty: PtyControl = object : PtyControl {
        override fun readingPassword(process: PtyProcess): Boolean = false

        override fun setWindowSize(process: PtyProcess, cols: Int, rows: Int, widthPx: Int, heightPx: Int): Boolean = false

        override fun hangUp(process: PtyProcess) {
            process.destroy()
        }

        override fun closePty(process: PtyProcess) {}
    }

    override val processes: ProcessInspector = object : ProcessInspector {
        override fun foregroundProcessName(process: PtyProcess): String? = null

        override fun foregroundCommand(process: PtyProcess): ForegroundCommand? = null

        override fun sessionPids(session: Int): List<Int> = emptyList()

        override fun kill(pid: Int) {
            ProcessHandle.of(pid.toLong()).ifPresent { it.destroyForcibly() }
        }

        override fun openFilePaths(pid: Int): List<String> = emptyList()
    }

    // What `hostname` says, and what the integration of PowerShell puts in the address of its directory.
    override fun hostName(): String? = System.getenv("COMPUTERNAME")?.takeIf { it.isNotEmpty() }
}
