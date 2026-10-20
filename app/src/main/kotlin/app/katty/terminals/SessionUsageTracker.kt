package app.katty.terminals

import app.katty.core.Agent
import app.katty.core.SessionUsage
import app.katty.core.SessionUsageReader
import app.katty.core.TerminalId
import app.katty.terminal.ForegroundCommand
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What the session of the agent in each terminal has used (its context and its cost), for the agents that have a [SessionUsageReader].
 * It knows nothing of what the agent is doing: every [tickMs] it asks each such terminal when its session last changed, which is cheap, and
 * reads the session again only when that moved.
 *
 * A terminal's session is the one that the store keeps for it ([sessionOf]: what resuming uses, which the registry finds, or the one a restored
 * terminal came back on), taken **once** for a program and kept for as long as the same one runs, so that another session of the same
 * directory that is updated later does not take its place. The entry goes when the shell has the terminal again or the program is another.
 *
 * @param processNames The program that has each terminal (see [SessionRegistry.processNames]); the shell's own name at its prompt.
 * @param foreground The program that has a terminal, with its pid: it costs a few system calls, so it is only asked for terminals that run an agent.
 * @param sessionOf The id of the session that the store has for the terminal's program.
 */
class SessionUsageTracker(
    private val scope: CoroutineScope,
    private val readers: Map<Agent, SessionUsageReader>,
    private val processNames: () -> Map<TerminalId, String?>,
    private val foreground: (TerminalId) -> ForegroundCommand?,
    private val sessionOf: (TerminalId) -> String?,
    private val tickMs: Long = 3_000,
) {
    private val mutableUsages = MutableStateFlow<Map<TerminalId, SessionUsage>>(emptyMap())

    /** What each terminal's agent session has used, for the ones that could be read. */
    val usages: StateFlow<Map<TerminalId, SessionUsage>> get() = mutableUsages

    private class Entry(val pid: Int, val agent: Agent, var session: String? = null, var updatedAt: Long? = null)

    private val entries = HashMap<TerminalId, Entry>()
    private var job: Job? = null

    fun start() {
        if (job != null) return
        job = scope.launch {
            while (isActive) {
                tick()
                delay(tickMs)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /** One look at every terminal. Suspends on the system calls and the database, never on the thread that draws. */
    suspend fun tick() {
        val names = processNames()
        for (id in entries.keys.toList()) {
            val agent = Agent.fromProcess(names[id].orEmpty())
            if (agent == null || entries.getValue(id).agent != agent) drop(id)
        }
        for ((id, name) in names) {
            val agent = Agent.fromProcess(name.orEmpty()) ?: continue
            val reader = readers[agent] ?: continue
            val pid = withContext(Dispatchers.IO) { foreground(id)?.pid } ?: continue
            var entry = entries[id]
            if (entry == null || entry.pid != pid) {
                drop(id)
                entry = Entry(pid, agent).also { entries[id] = it }
            }
            val session = entry.session ?: sessionOf(id)?.also { entry.session = it } ?: continue
            val updated = withContext(Dispatchers.IO) { reader.updatedAt(session) } ?: continue
            if (updated == entry.updatedAt) continue
            val usage = withContext(Dispatchers.IO) { reader.read(session) } ?: continue
            entry.updatedAt = updated
            mutableUsages.value = mutableUsages.value + (id to usage)
        }
    }

    private fun drop(id: TerminalId) {
        entries.remove(id)
        if (id in mutableUsages.value) mutableUsages.value = mutableUsages.value - id
    }
}
