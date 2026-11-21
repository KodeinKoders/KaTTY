package app.katty.terminal

/**
 * The program that has a terminal, as the system knows it: enough to start it again.
 *
 * @property pid The pid of the program, which is the id of its process group.
 * @property argv Its command line, the program first, as it was started (`emacs`, `notes.txt`). The environment is not read: it can hold secrets.
 * @property directory The directory it is in, which is not always the shell's: a program can change its own.
 * @property startedAtMillis When it started, in milliseconds since the epoch: what tells the files it wrote from the ones of an older program.
 * @property executable The path of the program's executable file as the system has it (`proc_pidpath` on macOS), which is not what `argv` names when it was
 * found on the `PATH` (`opencode`) or is a link: `null` when it could not be read.
 * @property groupPids The pids of every process of its group: the program, and what it started that stayed in the group (a launcher script that
 * runs the real program as a child: `node /opt/homebrew/bin/cline` and the native `.cline` that registers with its daemon). Empty when it could not be read.
 * @property tty The terminal that the program has (`ttys018`, without `/dev/`): the one that a program that keeps a file for each terminal (omp) calls its own.
 * `null` when it could not be read.
 * @property pipeline Whether it is one of several programs of a pipeline (`git log | less`): the others are not known, so it cannot be started again as it was.
 */
public data class ForegroundCommand(
    val pid: Int,
    val argv: List<String>,
    val directory: String?,
    val startedAtMillis: Long,
    val pipeline: Boolean,
    val executable: String? = null,
    val groupPids: List<Int> = emptyList(),
    val tty: String? = null,
)
