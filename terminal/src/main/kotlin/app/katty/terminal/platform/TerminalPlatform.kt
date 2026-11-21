package app.katty.terminal.platform

import app.katty.terminal.ForegroundCommand
import app.katty.terminal.platform.macos.MacosTerminalPlatform
import app.katty.terminal.platform.windows.WindowsTerminalPlatform
import com.pty4j.PtyProcess
import java.io.IOException

// What the terminal asks of the system it runs on, which each system does its own way: one implementation for each, in a package of its own
// (`macos`, with what macOS shares with other Unix systems in `posix`, and `windows`), chosen once by [terminalPlatform]. Nothing outside this package uses an
// implementation directly (PlatformBoundaryTest checks it). A system that has none gets [UnknownTerminalPlatform], on which what needs the
// system is simply not there.

/**
 * What a session does with its pty beyond reading and writing it, given pty4j's process. These are system calls that the session makes outside
 * the terminal's lock, under the lock that keeps the pty from being closed meanwhile.
 */
internal interface PtyControl {
    /**
     * Whether the program that has the pty is reading a password: it turned the echo off and left the line discipline on, which is what `sudo`,
     * `ssh` and `passwd` do. Never throws: `false` when it cannot be told.
     */
    fun readingPassword(process: PtyProcess): Boolean

    /**
     * Sets the window size of the pty, in cells and in pixels (pty4j only sets the cells), and returns whether it worked. Never throws: the
     * caller falls back to pty4j's size in cells.
     */
    fun setWindowSize(process: PtyProcess, cols: Int, rows: Int, widthPx: Int, heightPx: Int): Boolean

    /** Tells the program that its terminal is gone, as a terminal whose window closes does: a hang-up where there is one. */
    fun hangUp(process: PtyProcess)

    /** Closes the session's side of the pty, which would otherwise stay open until the garbage collector finds it. */
    @Throws(IOException::class)
    fun closePty(process: PtyProcess)
}

/** What can be known of the programs that run in a terminal. Only [sessionPids] may throw: what cannot be known is `null` or empty. */
internal interface ProcessInspector {
    /** The name of the program that has the pty (`emacs`, or the shell's own at its prompt), `null` when it cannot be known. */
    fun foregroundProcessName(process: PtyProcess): String?

    /** The program that has the pty, `null` when it is the shell itself (the [process]) or when it cannot be known. */
    fun foregroundCommand(process: PtyProcess): ForegroundCommand?

    /**
     * The pids of the processes of the session whose leader was [session], which started at [since] (milliseconds since the epoch, as
     * `ProcessHandle.Info.startInstant` says it): empty once they have all ended. A system without sessions (Windows) takes the processes that
     * the leader started, and the ones they started, which [since] tells from those that were given a pid of theirs again. Throws when the
     * system refuses.
     */
    fun sessionPids(session: Int, since: Long): List<Int>

    /** Kills [pid], which ignored the hang-up and was waited for long enough. */
    fun kill(pid: Int)

    /** The paths of the files that the process [pid] has open, empty when it cannot be known. */
    fun openFilePaths(pid: Int): List<String>
}

/** The system the terminal runs on. */
internal interface TerminalPlatform {
    val pty: PtyControl
    val processes: ProcessInspector

    /** The name of this machine as the system has it now, `null` when it cannot be known. Quick: it does not ask the network. */
    fun hostName(): String?
}

/** The implementation of the system the terminal runs on. */
internal val terminalPlatform: TerminalPlatform = when {
    System.getProperty("os.name").orEmpty().startsWith("Mac") -> MacosTerminalPlatform
    System.getProperty("os.name").orEmpty().startsWith("Windows") -> WindowsTerminalPlatform
    else -> UnknownTerminalPlatform
}
