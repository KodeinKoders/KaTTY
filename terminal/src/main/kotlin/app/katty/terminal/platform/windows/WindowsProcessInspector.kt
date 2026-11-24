package app.katty.terminal.platform.windows

import app.katty.terminal.ForegroundCommand
import app.katty.terminal.platform.ProcessInspector
import com.pty4j.PtyProcess
import com.pty4j.windows.WinHelper
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("app.katty.terminal.platform.windows.WindowsProcessInspector")

@Volatile
private var warned = false

// The hosts of a console, which are children of what they host and are no program of the user's.
private val CONSOLE_HOSTS = setOf("conhost.exe", "openconsole.exe")

// How many copies of the shell are gone through to find its program: a subshell of a subshell is still found.
private const val MAX_COPIES = 4

/**
 * What runs in a terminal on Windows. A console has no process group that has it, as a Unix terminal does: the program that has the terminal is a
 * direct child of the shell (a command that PowerShell runs and waits for), and the most recent one when there are several (a pipeline). The hosts
 * of a console and the graphical programs are not: PowerShell does not wait for `notepad`, which stays its child while it is back at its prompt. A
 * cmdlet or a script of PowerShell that runs for long starts no process, and the shell seems at its prompt, as a builtin of a Unix shell does.
 */
internal object WindowsProcessInspector : ProcessInspector {
    override fun foregroundProcessName(process: PtyProcess): String? = guarded(null) {
        val shell = process.pid().toInt()
        val tree = processSnapshot()
        val program = foreground(tree, shell).lastOrNull() ?: tree.firstOrNull { it.pid == shell } ?: return@guarded null
        program.exeName.withoutExe()
    }

    override fun foregroundCommand(process: PtyProcess): ForegroundCommand? = guarded(null) {
        val shell = process.pid().toInt()
        val tree = processSnapshot()
        val programs = foreground(tree, shell)
        val program = programs.lastOrNull() ?: return@guarded null
        val argv = commandLine(program.pid) ?: return@guarded null
        val handle = ProcessHandle.of(program.pid.toLong()).orElse(null) ?: return@guarded null
        ForegroundCommand(
            pid = program.pid,
            argv = argv,
            directory = currentDirectory(program.pid),
            startedAtMillis = handle.info().startInstant().map { it.toEpochMilli() }.orElse(0L),
            pipeline = programs.size > 1,
            executable = handle.info().command().orElse(null),
            groupPids = listOf(program.pid) + descendants(tree, program.pid, startOf(program.pid) ?: 0L),
        )
    }

    override fun sessionPids(session: Int, since: Long): List<Int> {
        val tree = processSnapshot()
        val leader = tree.firstOrNull { it.pid == session }?.takeIf { isSameProcess(it.pid, since) }
        return listOfNotNull(leader?.pid) + descendants(tree, session, since)
    }

    override fun kill(pid: Int) {
        ProcessHandle.of(pid.toLong()).ifPresent { it.destroyForcibly() }
    }

    // The files that a process has open are the system's handles, which Windows only lists for the whole system (NtQuerySystemInformation) and
    // which can block on a pipe when asked their name: not read. Antigravity and Kilo, which are found by the files they keep open, are not
    // resumed on Windows.
    override fun openFilePaths(pid: Int): List<String> = emptyList()

    // The children of the shell that are the user's programs, the oldest first: not a host of the console, nor a graphical program, nor a process
    // whose pid the shell's dead parent left and that was given to it again (it started before the shell).
    //
    // The shells of MSYS2, Git for Windows and Cygwin start a program of Windows from a copy of themselves (their `fork`), which stays its parent
    // (measured with ping: bash, then a bash, then PING): a child with the shell's own executable that has programs of its own is gone through,
    // and its programs are the ones that have the terminal. One that has none is a shell that the user started, at its prompt. A program of the
    // environment itself (vim, sleep) is a process whose parent, the copy, has ended: it is not seen, and the shell seems at its prompt.
    private fun foreground(tree: List<WindowsProcess>, shell: Int, depth: Int = 0): List<WindowsProcess> {
        val shellStart = startOf(shell) ?: return emptyList()
        val programs = tree.asSequence()
            .filter { it.parent == shell && it.exeName.lowercase() !in CONSOLE_HOSTS }
            .mapNotNull { child -> startOf(child.pid)?.takeIf { it >= shellStart }?.let { child to it } }
            .filter { (child, _) -> ProcessHandle.of(child.pid.toLong()).flatMap { it.info().command() }.map { !isGraphicalProgram(it) }.orElse(true) }
            .sortedBy { it.second }
            .map { it.first }
            .toList()
        val shellExe = tree.firstOrNull { it.pid == shell }?.exeName ?: return programs
        if (depth >= MAX_COPIES) return programs
        return programs.flatMap { program ->
            if (program.exeName.equals(shellExe, ignoreCase = true)) foreground(tree, program.pid, depth + 1).ifEmpty { listOf(program) } else listOf(program)
        }
    }

    // The processes that [root] started, and the ones they started, by the parent that each one had, even when that parent has ended: the ones
    // that started after [since], so that a pid that the system gave again to another process is not taken for one of them.
    private fun descendants(tree: List<WindowsProcess>, root: Int, since: Long): List<Int> {
        val children = tree.groupBy { it.parent }
        val found = ArrayList<Int>()
        val queue = ArrayDeque(listOf(root))
        while (queue.isNotEmpty()) {
            for (child in children[queue.removeFirst()].orEmpty()) {
                if (child.pid == root || child.pid in found) continue
                val start = startOf(child.pid) ?: continue
                if (start < since) continue
                found += child.pid
                queue += child.pid
            }
        }
        return found
    }

    // Whether [pid] is still the process that started at [since], and not another that was given its pid since (within a second: the clock of a
    // start time and the one of the caller are not read at the same moment).
    private fun isSameProcess(pid: Int, since: Long): Boolean = startOf(pid)?.let { it >= since - 1_000 } ?: false

    private fun startOf(pid: Int): Long? = ProcessHandle.of(pid.toLong()).flatMap { it.info().startInstant() }.map { it.toEpochMilli() }.orElse(null)

    // The directory of [pid], as pty4j's helper of Windows reads it (`C:\src\`), without the separator at its end but for a drive's root.
    private fun currentDirectory(pid: Int): String? = try {
        WinHelper.getCurrentDirectory(pid.toLong())?.let { if (it.length > 3) it.trimEnd('\\') else it }
    } catch (e: Exception) {
        logger.debug("Could not read the directory of {}", pid, e)
        null
    }

    // What the system calls fail with is logged once: they are asked about every terminal, twice a second.
    private inline fun <T> guarded(default: T, read: () -> T): T = try {
        read()
    } catch (e: Throwable) {
        if (!warned) {
            warned = true
            logger.warn("Could not read what runs in a terminal", e)
        }
        default
    }
}

// The name of a program without the `.exe` of Windows (`pwsh`), as the shell's name is written.
private fun String.withoutExe(): String = if (endsWith(".exe", ignoreCase = true)) dropLast(4) else this
