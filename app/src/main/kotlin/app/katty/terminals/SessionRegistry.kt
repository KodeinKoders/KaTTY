package app.katty.terminals

import androidx.compose.ui.focus.FocusRequester
import app.katty.core.Agent
import app.katty.core.GitInfo
import app.katty.core.GitReader
import app.katty.core.KattyState
import app.katty.core.KattyStore
import app.katty.core.RestorePolicy
import app.katty.core.TerminalId
import app.katty.core.TerminalSpec
import app.katty.core.sessionOf
import app.katty.core.toRunning
import app.katty.core.toTyped
import app.katty.terminal.TerminalSession
import app.katty.terminal.TerminalTheme
import app.katty.terminal.themes.ITermColorSchemes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.withContext
import java.nio.file.Path
import app.katty.terminal.TerminalEvent
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.io.File

private val logger = LoggerFactory.getLogger("app.katty.terminals.SessionRegistry")

/**
 * A terminal of the store as it runs: its session, or why there is none, and what gives it the keyboard focus.
 *
 * @param session `null` when the shell could not be started.
 * @param failure What went wrong then.
 * @param generation Which shell of the terminal this is: it changes when the shell is started again, so that what shows it is made again.
 */
class TerminalHandle(
    val id: TerminalId,
    val session: TerminalSession?,
    val failure: String? = null,
    val generation: Int = 0,
    /** The name of the shell that runs, as its executable is called: `zsh`. */
    val shellName: String = "zsh",
) {
    val focus: FocusRequester = FocusRequester()

    internal val gitState = MutableStateFlow<GitInfo?>(null)

    internal val processState = MutableStateFlow<String?>(null)

    /** The program that has the terminal now (`emacs`, or the shell's own name at its prompt), `null` when it is not known. */
    val processName: StateFlow<String?> get() = processState

    /** The branch, worktree or commit that the shell is in, `null` when it is not in a repository. */
    val git: StateFlow<GitInfo?> get() = gitState
}

/**
 * The shells of the terminals: there is one session for each terminal of the store, in whatever workspace and project, as long as the
 * terminal is there. A shell goes on running when its workspace is not the one shown, which is the point of keeping them.
 *
 * The registry follows the store: [sync] starts the sessions of the terminals that are new, closes the ones of the terminals that are
 * gone, and gives each session its color scheme. It also tells the store where each shell goes, so that a terminal that is started again
 * starts there.
 *
 * @param start Starts the shell of a terminal. Tests give their own program.
 * @param loadTheme The colors of a color scheme, `null` when it cannot be read.
 */
class SessionRegistry(
    private val store: KattyStore,
    private val scope: CoroutineScope,
    private val loadTheme: (String) -> TerminalTheme? = ::loadColorScheme,
    private val start: (TerminalSpec, TerminalTheme?) -> TerminalSession = ::startShell,
) {
    private val mutableHandles = MutableStateFlow<Map<TerminalId, TerminalHandle>>(emptyMap())

    /** Every terminal that is running, by its id. */
    val handles: StateFlow<Map<TerminalId, TerminalHandle>> get() = mutableHandles

    private val mutableProcessNames = MutableStateFlow<Map<TerminalId, String?>>(emptyMap())

    /**
     * The program that has each terminal (`claude`, or the shell's own name at its prompt), which is what each handle's [TerminalHandle.processName]
     * says, all in one place for the ones that want them all: the board tells the terminals that run an agent by it.
     */
    val processNames: StateFlow<Map<TerminalId, String?>> get() = mutableProcessNames

    private val mutableGitInfos = MutableStateFlow<Map<TerminalId, GitInfo?>>(emptyMap())

    /**
     * Where each terminal is in git (`null` for one that is not in a repository), which is what each handle's [TerminalHandle.git] says, all in one
     * place for the ones that want them all: a workspace says the branch that its terminals share.
     */
    val gitInfos: StateFlow<Map<TerminalId, GitInfo?>> get() = mutableGitInfos

    // What each session follows and the scheme it has now.
    private val followers = HashMap<TerminalId, Job>()
    private val schemes = HashMap<TerminalId, String>()
    private var visible: Set<TerminalId>? = null
    private var generations = HashMap<TerminalId, Int>()

    /** Makes the sessions match the terminals of [state]. Call it from one thread, the UI's. */
    fun sync(state: KattyState) {
        val wanted = state.allTerminals().associate { it.terminal.id to startingPlace(it.terminal, it.project.directory) }
        val current = mutableHandles.value
        var next = current

        for (id in current.keys - wanted.keys) {
            next = next - id
            stop(current.getValue(id))
        }
        for ((id, spec) in wanted) {
            val scheme = spec.colorScheme ?: state.settings.colorScheme
            if (id !in current) {
                next = next + (id to begin(spec, scheme, generations[id] ?: 0))
            } else if (schemes[id] != scheme) {
                schemes[id] = scheme
                current.getValue(id).session?.let { session -> loadTheme(scheme)?.let(session::setTheme) }
            }
        }
        if (next !== current) mutableHandles.value = next
        applyVisibility()
    }

    /** Shows the sessions of [ids] and pauses the others: what is not on the screen does not need frames. */
    fun setVisible(ids: Set<TerminalId>) {
        visible = ids
        applyVisibility()
    }

    private fun applyVisibility() {
        val shown = visible ?: return
        for ((id, handle) in mutableHandles.value) handle.session?.setVisible(id in shown)
    }

    /** Ends the shell of the terminal and starts another in its place: what "Reopen shell" does. */
    fun restart(id: TerminalId) {
        val state = store.state.value
        val location = state.locate(id) ?: return
        // A new shell is a plain one: what ran over the old is not started again.
        store.setRunning(id, null)
        val spec = startingPlace(location.terminal, location.project.directory).copy(running = null)
        val old = mutableHandles.value[id] ?: return
        stop(old)
        val generation = (generations[id] ?: 0) + 1
        generations[id] = generation
        val scheme = spec.colorScheme ?: state.settings.colorScheme
        mutableHandles.value = mutableHandles.value + (id to begin(spec, scheme, generation))
        applyVisibility()
    }

    /** Closes every session: the application is quitting. */
    fun close() {
        val all = mutableHandles.value
        mutableHandles.value = emptyMap()
        followers.values.forEach { it.cancel() }
        followers.clear()
        all.values.forEach { it.session?.close() }
    }

    private fun begin(spec: TerminalSpec, scheme: String, generation: Int): TerminalHandle {
        schemes[spec.id] = scheme
        val session = try {
            start(spec, loadTheme(scheme))
        } catch (e: Exception) {
            logger.error("Could not start the shell of {}", spec.id.value, e)
            return TerminalHandle(spec.id, null, e.message ?: e.toString(), generation)
        }
        val handle = TerminalHandle(spec.id, session, generation = generation, shellName = shellName())
        // Where the shell goes is where it starts the next time, and what repository it is in is read again then, and when a command ends.
        followers[spec.id] = scope.launch {
            // The session that an OpenCode in this terminal last reported working or waiting: it reports a record for each of those, with the
            // session's id as its id, and takes it off when the session rests, so it is kept here. It is the terminal's own session, which a
            // directory and times are not when two run in one. It goes when the shell has the terminal again.
            val reportedSession = java.util.concurrent.atomic.AtomicReference<String?>(null)
            launch {
                session.programStatus.collect { records -> records.sessionOf(Agent.OPENCODE)?.let(reportedSession::set) }
            }
            launch { session.workingDirectory.filterNotNull().collect { store.setDirectory(spec.id, it) } }
            // What runs in the terminal is its name: asked often, since it takes two system calls and a shell tells nothing when a program ends.
            launch {
                // A terminal that is restored starts as a shell, which is at its prompt for a moment before it has typed the program: that is not
                // the program having ended, and what is kept of it stays until the program has been seen again, or for [RESTORE_GRACE_MS].
                var graceUntil = if (spec.running != null) System.nanoTime() + RESTORE_GRACE_MS * 1_000_000 else 0L
                // The session that a restored agent came back on: a conversation that has had no new message since is not the newest of its directory,
                // so it is kept for that program (the first of the agent that is seen), and not for another that is started after it.
                val resumed = spec.running?.takeIf { it.agent != null }?.let { it.agent to it.agentSession }
                var resumedPid = 0
                while (isActive) {
                    val systemName = session.foregroundProcessName()
                    // A program that is a script has its interpreter's name (Aider is `Python`): the agent that it is says its own.
                    val command = if (systemName != null) withContext(Dispatchers.IO) { session.foregroundCommand() } else null
                    val name = command?.let { Agent.fromCommand(it.argv) }?.takeIf { it.interpreted }?.executable ?: systemName
                    handle.processState.value = name
                    if (name == null || name == handle.shellName) reportedSession.set(null)
                    if (mutableProcessNames.value[spec.id] != name || spec.id !in mutableProcessNames.value) mutableProcessNames.update { it + (spec.id to name) }
                    // What the program is, to be started again: with what the shell is for the program that is not there (a name that is not known says nothing).
                    if (name != null) {
                        var running = command?.let { withContext(Dispatchers.IO) { it.toRunning(reportedSession = reportedSession.get()) } }
                        if (command != null && running != null && resumed?.second != null && running.agent == resumed.first) {
                            if (resumedPid == 0) resumedPid = command.pid
                            // An OpenCode that has not worked has said nothing of its session, and its directory only says the newest one: that may be another
                            // OpenCode's, so the session it came back on stays until it says its own.
                            val guessed = running.agent == Agent.OPENCODE.id && reportedSession.get() == null
                            if (command.pid == resumedPid && (running.agentSession == null || guessed)) running = running.copy(agentSession = resumed.second)
                        }
                        if (command != null) graceUntil = 0L
                        if (running != null || System.nanoTime() >= graceUntil) store.setRunning(spec.id, running)
                    }
                    delay(PROCESS_POLL_MS)
                }
            }
            // What the shell wrote stays on the screen, and says that it is over.
            launch {
                session.exited.filterNotNull().first()
                session.writeToScreen("\r\n[Process completed]\r\n")
            }
            merge(session.workingDirectory.map { }, session.events.filterIsInstance<TerminalEvent.CommandFinished>().map { }).conflate().collect {
                val directory = session.workingDirectory.value ?: spec.directory
                val info = withContext(Dispatchers.IO) { GitReader.read(Path.of(directory)) }
                handle.gitState.value = info
                mutableGitInfos.update { it + (spec.id to info) }
            }
        }
        return handle
    }

    private fun stop(handle: TerminalHandle) {
        followers.remove(handle.id)?.cancel()
        mutableProcessNames.update { it - handle.id }
        mutableGitInfos.update { it - handle.id }
        schemes.remove(handle.id)
        val session = handle.session ?: return
        // Closing waits for the shell to end: not on the thread that draws.
        scope.launch(Dispatchers.IO) { session.close() }
    }
}

// What the shell is called, as its program is: the one that the user's login shell is.
/** How often the program that has a terminal is asked for. */
private const val PROCESS_POLL_MS = 500L

/** How long a restored terminal keeps what it is to start again while its shell has not yet got to it: a shell's startup files can be slow. */
private const val RESTORE_GRACE_MS = 15_000L

private fun shellName(): String = File(System.getenv("SHELL") ?: "/bin/zsh").name.ifEmpty { "shell" }

/**
 * Where a shell starts: where the terminal last was, if that folder is still there, else the folder of its project, else (for [startShell])
 * the user's home. A terminal that is restored starts there, and a shell that is started again does.
 */
internal fun startingPlace(spec: TerminalSpec, projectDirectory: String): TerminalSpec =
    if (File(spec.directory).isDirectory) spec else spec.copy(directory = projectDirectory)

private val RESTORE_POLICY = RestorePolicy()

private fun loadColorScheme(name: String): TerminalTheme? =
    try {
        ITermColorSchemes.load(name)
    } catch (e: Exception) {
        logger.error("Could not load the color scheme {}", name, e)
        null
    }

private fun startShell(spec: TerminalSpec, theme: TerminalTheme?): TerminalSession {
    // A folder that is gone is not a reason for the terminal to have no shell: it starts in the user's home.
    // The program that ran over the shell is typed in the new one, in the folder it was in, which is the folder that the shell goes back to when
    // it ends.
    val running = spec.running
    val directory = (running?.directory ?: spec.directory).takeIf { File(it).isDirectory } ?: spec.directory.takeIf { File(it).isDirectory }
    return TerminalSession.start(cols = 80, rows = 24, directory = directory, theme = theme, typeAtPrompt = running?.toTyped(RESTORE_POLICY))
}
