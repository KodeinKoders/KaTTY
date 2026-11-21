package app.katty.terminal.platform.macos

import app.katty.terminal.ForegroundCommand
import app.katty.terminal.platform.ProcessInspector
import app.katty.terminal.platform.PtyControl
import app.katty.terminal.platform.TerminalPlatform
import app.katty.terminal.platform.posix.PosixPtyControl
import app.katty.terminal.platform.posix.TermiosLayout
import app.katty.terminal.platform.posix.killProcess
import app.katty.terminal.platform.posix.masterOf
import app.katty.terminal.platform.posix.readHostName
import com.pty4j.PtyProcess

/** macOS: the pty of a Unix system with macOS's values, and the programs as libproc and `sysctl` tell them. */
internal object MacosTerminalPlatform : TerminalPlatform {
    override val pty: PtyControl = PosixPtyControl(
        // `struct termios` of macOS: four flag words of 8 bytes (the local ones are the fourth), 20 control characters, and two speeds of 8 bytes.
        termios = TermiosLayout(size = 72, localFlagsOffset = 24, localFlagsBytes = 8, echo = 0x8, canonical = 0x100),
        // `_IOW('t', 103, struct winsize)`.
        setWindowSize = 0x80087467L,
    )

    override val processes: ProcessInspector = object : ProcessInspector {
        override fun foregroundProcessName(process: PtyProcess): String? = masterOf(process)?.let(::foregroundProcessName)

        override fun foregroundCommand(process: PtyProcess): ForegroundCommand? =
            masterOf(process)?.let { foregroundCommand(it, process.pid().toInt()) }

        override fun sessionPids(session: Int): List<Int> = app.katty.terminal.platform.macos.sessionPids(session)

        override fun kill(pid: Int) = killProcess(pid)

        override fun openFilePaths(pid: Int): List<String> = app.katty.terminal.platform.macos.openFilePaths(pid)
    }

    override fun hostName(): String? = readHostName()
}
