package app.katty.terminals

import androidx.compose.ui.focus.FocusRequester
import app.katty.core.WorkspaceId
import app.katty.terminal.TerminalSession
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("app.katty.terminals.RunTerminals")

/**
 * The terminal of a workspace that only runs [command], in [directory], over the panes of the workspace: it ends with its command.
 *
 * @param session The session of the command, `null` while it starts and when it could not ([failure] says why).
 * @param shown Whether it is over the panes, or minimized into the header of the workspace.
 * @param generation Which run of the workspace it is: a new run is another session, which what shows it is made again for.
 * @param focus What gives its terminal the keyboard.
 * @param waiting Whether it waits for the processes of the run before it to end, before it starts.
 */
class RunTerminal(
    val workspace: WorkspaceId,
    val command: String,
    val directory: String,
    val session: TerminalSession? = null,
    val failure: String? = null,
    val shown: Boolean = true,
    val generation: Long = 0,
    val focus: FocusRequester = FocusRequester(),
    val waiting: Boolean = false,
) {
    /** Whether its command has ended by itself, or never started: it is kept to be read, and a new run does not ask before it replaces it. */
    val ended: Boolean get() = failure != null || session?.exited?.value != null

    internal fun with(session: TerminalSession?, failure: String?) = RunTerminal(workspace, command, directory, session, failure, shown, generation, focus)

    internal fun shown(shown: Boolean) = RunTerminal(workspace, command, directory, session, failure, shown, generation, focus, waiting)

    internal fun starting() = RunTerminal(workspace, command, directory, session, failure, shown, generation, focus)
}

/**
 * The run terminals, one at most for each workspace. They are not terminals of the store: no pane holds them, nothing of them is saved (their
 * program cannot outlive the application), and the board, the agents, the snapshots and the search of every terminal do not see them.
 *
 * A run that the user stops is gone at once, and its program is hung up as a terminal that is closed; one whose command ends by itself stays, with
 * its exit code, until it is closed or replaced by the next run.
 * **A run starts once the one before it in its workspace has ended**: its session closed and every process of its terminal's session gone
 * (`TerminalSession.closeAndWait`: what still runs after [endTimeoutMs] is killed), so that a server that is run again does not find its port
 * taken by the one it replaces. The work of a workspace (a start, a stop) is chained for that.
 *
 * @param starter Where a command is started: not on the thread that draws.
 * @param start Starts [command] in [directory], in a session of its own, for a run of the workspace it is given (whose environment it has).
 * @param endTimeoutMs How long the processes of a run that is stopped have to end before they are killed.
 */
class RunTerminals(
    private val scope: CoroutineScope,
    private val starter: CoroutineDispatcher = Dispatchers.IO,
    private val endTimeoutMs: Long = TerminalSession.SESSION_END_TIMEOUT_MS,
    private val start: (workspace: WorkspaceId, command: String, directory: String) -> TerminalSession,
) {
    private val mutableTerminals = MutableStateFlow<Map<WorkspaceId, RunTerminal>>(emptyMap())

    /** The run terminal of each workspace that has one. */
    val terminals: StateFlow<Map<WorkspaceId, RunTerminal>> get() = mutableTerminals

    private var generations = 0L
    private var visible: WorkspaceId? = null
    private var closed = false

    // The last work launched for each workspace (starting its run, ending one): the next waits for it. Gone once it is done.
    private val lastWork = mutableMapOf<WorkspaceId, Job>()

    /** Whether the command of the run of [workspace] is still running (or starting): what a new run stops, and asks about first. */
    fun active(workspace: WorkspaceId): Boolean = mutableTerminals.value[workspace]?.ended == false

    /**
     * Runs [command] in [directory] for [workspace], over its panes, or minimized into the header of the workspace unless [shown]: the run it had, ended
     * or not, is stopped and replaced.
     */
    @Synchronized
    fun run(workspace: WorkspaceId, command: String, directory: String, shown: Boolean = true) {
        if (closed) return
        mutableTerminals.value[workspace]?.let(::end)
        val generation = ++generations
        // It waits while what ran before it in the workspace has not ended.
        val waiting = lastWork[workspace]?.isActive == true
        mutableTerminals.update { it + (workspace to RunTerminal(workspace, command, directory, shown = shown, generation = generation, waiting = waiting)) }
        chain(workspace, starter) {
            synchronized(this@RunTerminals) {
                val current = mutableTerminals.value[workspace]
                // Stopped or replaced while it waited: nothing is started.
                if (closed || current?.generation != generation) return@chain
                if (current.waiting) mutableTerminals.update { it + (workspace to current.starting()) }
            }
            val started = try {
                Result.success(start(workspace, command, directory))
            } catch (e: Exception) {
                logger.error("Could not run {} in {}", command, directory, e)
                Result.failure(e)
            }
            val session = started.getOrNull()
            val wanted = synchronized(this@RunTerminals) {
                val current = mutableTerminals.value[workspace]
                // Stopped or replaced while it started, or the application quits: this session is nobody's.
                if (closed || current?.generation != generation) {
                    false
                } else {
                    mutableTerminals.update { it + (workspace to current.with(session, started.exceptionOrNull()?.let { e -> e.message ?: e.toString() })) }
                    applyVisibility()
                    true
                }
            }
            if (!wanted) session?.closeAndWait(endTimeoutMs)
        }
    }

    /** Stops the run of [workspace], and its program: it is gone. */
    @Synchronized
    fun stop(workspace: WorkspaceId) {
        val run = mutableTerminals.value[workspace] ?: return
        mutableTerminals.update { it - workspace }
        end(run)
    }

    /** Shows the run of [workspace] over its panes, or minimizes it into the header of the workspace. */
    @Synchronized
    fun setShown(workspace: WorkspaceId, shown: Boolean) {
        val run = mutableTerminals.value[workspace]?.takeIf { it.shown != shown } ?: return
        mutableTerminals.update { it + (workspace to run.shown(shown)) }
        applyVisibility()
    }

    /** Shows the run of [workspace] when it is minimized, minimizes it when it is shown. */
    fun toggle(workspace: WorkspaceId) {
        val run = mutableTerminals.value[workspace] ?: return
        setShown(workspace, !run.shown)
    }

    /** The runs of the workspaces that are gone are stopped: a run belongs to its workspace. */
    @Synchronized
    fun keepOnly(workspaces: Set<WorkspaceId>) {
        for (workspace in mutableTerminals.value.keys - workspaces) stop(workspace)
    }

    /** [workspace] is the one on the screen, `null` for none: its run draws frames while it is shown, the others do not. */
    @Synchronized
    fun setVisible(workspace: WorkspaceId?) {
        visible = workspace
        applyVisibility()
    }

    // Under the monitor.
    private fun applyVisibility() {
        for (run in mutableTerminals.value.values) run.session?.setVisible(run.workspace == visible && run.shown)
    }

    /**
     * The application quits: nothing more is started, and the sessions that run are given to the caller, which closes them with the others.
     */
    @Synchronized
    fun close(): List<TerminalSession> {
        closed = true
        return mutableTerminals.value.values.mapNotNull { it.session }.also { mutableTerminals.value = emptyMap() }
    }

    // Closing waits for the program, and what it started, to end: not on the thread that draws. A run that is still starting closes its own
    // session, which nobody wants any more, in its work, which the next one waits for too.
    private fun end(run: RunTerminal) {
        val session = run.session ?: return
        chain(run.workspace, Dispatchers.IO) { session.closeAndWait(endTimeoutMs) }
    }

    // Under the monitor: launches [work] once the last work of [workspace] is done.
    private fun chain(workspace: WorkspaceId, dispatcher: CoroutineDispatcher, work: suspend () -> Unit) {
        val before = lastWork[workspace]
        val job = scope.launch(dispatcher) {
            before?.join()
            work()
        }
        lastWork[workspace] = job
        job.invokeOnCompletion { synchronized(this) { if (lastWork[workspace] === job) lastWork.remove(workspace) } }
    }
}
