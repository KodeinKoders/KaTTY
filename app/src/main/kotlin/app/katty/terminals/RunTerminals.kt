package app.katty.terminals

import androidx.compose.ui.focus.FocusRequester
import app.katty.core.WorkspaceId
import app.katty.terminal.TerminalSession
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
) {
    /** Whether its command has ended by itself, or never started: it is kept to be read, and a new run does not ask before it replaces it. */
    val ended: Boolean get() = failure != null || session?.exited?.value != null

    internal fun with(session: TerminalSession?, failure: String?) = RunTerminal(workspace, command, directory, session, failure, shown, generation, focus)

    internal fun shown(shown: Boolean) = RunTerminal(workspace, command, directory, session, failure, shown, generation, focus)
}

/**
 * The run terminals, one at most for each workspace. They are not terminals of the store: no pane holds them, nothing of them is saved (their
 * program cannot outlive the application), and the board, the agents, the snapshots and the search of every terminal do not see them.
 *
 * A run that the user stops is gone at once, and its program is hung up as a terminal that is closed; one whose command ends by itself stays, with
 * its exit code, until it is closed or replaced by the next run.
 *
 * @param starter Where a command is started: not on the thread that draws.
 * @param start Starts [command] in [directory], in a session of its own.
 */
class RunTerminals(
    private val scope: CoroutineScope,
    private val starter: CoroutineDispatcher = Dispatchers.IO,
    private val start: (command: String, directory: String) -> TerminalSession,
) {
    private val mutableTerminals = MutableStateFlow<Map<WorkspaceId, RunTerminal>>(emptyMap())

    /** The run terminal of each workspace that has one. */
    val terminals: StateFlow<Map<WorkspaceId, RunTerminal>> get() = mutableTerminals

    private var generations = 0L
    private var visible: WorkspaceId? = null
    private var closed = false

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
        mutableTerminals.update { it + (workspace to RunTerminal(workspace, command, directory, shown = shown, generation = generation)) }
        scope.launch(starter) {
            val started = try {
                Result.success(start(command, directory))
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
            if (!wanted) session?.close()
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

    // Closing waits for the program to end: not on the thread that draws.
    private fun end(run: RunTerminal) {
        val session = run.session ?: return
        scope.launch(Dispatchers.IO) { session.close() }
    }
}
