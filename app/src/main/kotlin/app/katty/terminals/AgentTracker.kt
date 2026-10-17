package app.katty.terminals

import app.katty.core.AgentInfo
import app.katty.core.AgentState
import app.katty.core.TerminalId
import app.katty.core.column
import app.katty.core.summarize
import app.katty.terminal.TerminalProgramState
import app.katty.terminal.TerminalSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * A terminal changed state.
 *
 * @param from The state it was in; at rest for a terminal that had said nothing.
 * @param info What it is now, with since when.
 */
data class AgentTransition(val terminal: TerminalId, val from: AgentState, val info: AgentInfo)

/**
 * What each terminal says it is doing: it follows the program status records of every session (OSC 7501), and keeps, for each terminal, its
 * state, the program, the note and since when it has been in that state. It only reads: what the programs say is theirs, and nothing here
 * answers it.
 *
 * `done` and `error` records stay on a session until someone has seen them: [markSeen] removes them, and the terminal is then at rest.
 */
class AgentTracker(
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mutableInfos = MutableStateFlow<Map<TerminalId, AgentInfo>>(emptyMap())

    /** What every terminal that is running says, by its id. */
    val infos: StateFlow<Map<TerminalId, AgentInfo>> get() = mutableInfos

    private val mutableTransitions = MutableSharedFlow<AgentTransition>(extraBufferCapacity = 64)

    /** A terminal changed state. A collector that comes late misses what happened before. */
    val transitions: SharedFlow<AgentTransition> get() = mutableTransitions

    private class Follow(val session: TerminalSession, val job: Job, val processJob: Job)

    private val follows = HashMap<TerminalId, Follow>()

    // The agent that a terminal has said it runs, by the `app` of its last report that named one: what it says is gone when what it finished
    // is seen and taken off, and it still runs. It lasts until the shell has the terminal again, which is when the agent has ended.
    private val rememberedApp = HashMap<TerminalId, String>()

    // What the records of each terminal come to, without what is remembered.
    private val latest = HashMap<TerminalId, app.katty.core.AgentReport>()

    /** Follows the sessions of [handles], and lets go of the ones that are gone or were replaced by another shell. */
    @Synchronized
    fun sync(handles: Map<TerminalId, TerminalHandle>) {
        for (id in follows.keys.toList()) {
            val session = handles[id]?.session
            if (session == null || follows.getValue(id).session !== session) {
                follows.remove(id)?.let { it.job.cancel(); it.processJob.cancel() }
                rememberedApp.remove(id)
                latest.remove(id)
                mutableInfos.value = mutableInfos.value - id
            }
        }
        for ((id, handle) in handles) {
            val session = handle.session ?: continue
            if (id in follows) continue
            follows[id] = Follow(
                session,
                scope.launch { session.programStatus.collect { records -> update(id, summarize(records)) } },
                // The shell has the terminal again: the agent that ran in it is over.
                scope.launch {
                    handle.processName.collect { name ->
                        if (name == null || name == handle.shellName) forgetAgent(id)
                    }
                },
            )
        }
    }

    private fun forgetAgent(id: TerminalId) {
        val report = synchronized(this) {
            if (rememberedApp.remove(id) == null) return
            latest[id]
        } ?: return
        update(id, report)
    }

    private fun update(id: TerminalId, summary: app.katty.core.AgentReport) {
        var transition: AgentTransition? = null
        mutableInfos.value = synchronized(this) {
            latest[id] = summary
            // A report that names a program says which agent the terminal runs; one at rest that names none (what is left when a `done` was
            // seen) is that agent, at rest, until the shell has the terminal again.
            summary.app?.let { rememberedApp[id] = it }
            val report = if (summary.app == null && summary.state == AgentState.IDLE) rememberedApp[id]?.let { summary.copy(app = it) } ?: summary else summary
            val before = mutableInfos.value[id]
            // Since when it is in this column, not in this state: a wait that becomes a failure stays where it is on the board.
            val since = if (before != null && before.state.column() == report.state.column()) before.since else clock()
            val info = AgentInfo(report, since)
            if (before == info) return
            // A terminal that has said nothing is at rest: becoming so is not a change.
            val from = before?.state ?: AgentState.IDLE
            if (from != report.state) transition = AgentTransition(id, from, info)
            mutableInfos.value + (id to info)
        }
        transition?.let { mutableTransitions.tryEmit(it) }
    }

    /** The user has seen the terminal: what it finished or failed at is taken off, and it is at rest. */
    fun markSeen(id: TerminalId) {
        val session = synchronized(this) { follows[id]?.session } ?: return
        for (record in session.programStatus.value) {
            if (record.state == TerminalProgramState.DONE || record.state == TerminalProgramState.ERROR) session.clearProgramStatus(record.id)
        }
    }

    /** Marks every terminal seen: "Mark all seen". */
    fun markAllSeen() {
        val ids = synchronized(this) { follows.keys.toList() }
        ids.forEach(::markSeen)
    }

    /** Lets go of every session. */
    @Synchronized
    fun close() {
        follows.values.forEach { it.job.cancel() }
        follows.clear()
        mutableInfos.value = emptyMap()
    }
}
