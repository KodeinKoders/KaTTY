package app.katty.terminals

import androidx.compose.ui.focus.FocusRequester
import app.katty.core.Agent
import app.katty.core.CursorShape
import app.katty.core.GitInfo
import app.katty.core.GitReader
import app.katty.core.KattyState
import app.katty.core.KattyStore
import app.katty.core.RestorePolicy
import app.katty.core.SessionLookups
import app.katty.core.Settings
import app.katty.core.TerminalId
import app.katty.core.TerminalSnapshotStore
import app.katty.core.TerminalSpec
import app.katty.core.apartFromOthers
import app.katty.core.sessionOf
import app.katty.core.restartDirectory
import app.katty.core.toRunning
import app.katty.core.toTyped
import app.katty.terminal.TerminalCursorShape
import app.katty.terminal.TerminalSession
import app.katty.terminal.TerminalSnapshot
import app.katty.terminal.TerminalSnapshotException
import app.katty.terminal.TerminalTheme
import app.katty.terminal.themes.ITermColorSchemes
import kotlinx.coroutines.CoroutineDispatcher
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.slf4j.LoggerFactory
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

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
 * A shell is started on [starter], not on the thread that calls [sync] (the UI's: starting a program and reading a color scheme take a moment, and
 * a folder on a network drive longer), and its handle is in [handles] once it has started: a terminal has none for that moment. A terminal that
 * is gone, or started again, before its shell has started has that shell closed.
 *
 * @param start Starts the shell of a terminal. Tests give their own program.
 * @param loadTheme The colors of a color scheme, `null` when it cannot be read. Each one is read once.
 * @param shellIntegration Where the scripts of the shell integration are kept (see [TerminalSession.start]).
 * @param snapshots Where what the terminals showed is kept (while `Settings.restoreContents` is on): a terminal that starts with one has its screen
 * and history back, under a line that says it is restored, and its shell below. They are saved every [autosaveMillis] for the terminals that changed,
 * and when the registry is closed. The snapshot of a terminal that is gone, or that is started again by the user, is deleted.
 * @param startRestored Starts the shell of a terminal that has a snapshot, which [start] starts without.
 * @param starter Where the shells are started and the color schemes read. Tests give one that does it at once, on the caller's thread.
 */
class SessionRegistry(
    private val store: KattyStore,
    private val scope: CoroutineScope,
    private val loadTheme: (String) -> TerminalTheme? = ::loadColorScheme,
    shellIntegration: Path? = null,
    private val starter: CoroutineDispatcher = Dispatchers.IO,
    private val start: (TerminalSpec, TerminalTheme?) -> TerminalSession = { spec, theme -> startShell(spec, theme, shellIntegration, store.state.value.settings) },
    private val snapshots: TerminalSnapshotStore? = null,
    private val startRestored: (TerminalSpec, TerminalTheme?, TerminalSnapshot) -> TerminalSession = { spec, theme, snapshot ->
        startShell(spec, theme, shellIntegration, store.state.value.settings, snapshot)
    },
    private val autosaveMillis: Long = AUTOSAVE_MS,
) {
    private val mutableHandles = MutableStateFlow<Map<TerminalId, TerminalHandle>>(emptyMap())

    init {
        // What the terminals show is kept as it changes, so that a crash restores it as well as a quit does.
        if (snapshots != null) {
            scope.launch {
                while (isActive) {
                    delay(autosaveMillis)
                    autosave()
                }
            }
        }
    }

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

    // What each session follows, the scheme it has now, and the generation of the shell that is being started for each terminal. Under the
    // registry's monitor: a shell that has started is published from [starter].
    private val followers = HashMap<TerminalId, Job>()

    // Whether the followers of each session may still write: they are cancelled when it stops, but a poll that is between two suspensions goes on
    // to its writes. Checked and cleared under the flag's own monitor, so that nothing is written once [stop] has returned.
    private val alive = HashMap<TerminalId, AtomicBoolean>()
    private val schemes = HashMap<TerminalId, String>()
    private val starting = HashMap<TerminalId, Int>()
    private var visible: Set<TerminalId>? = null
    private var generations = HashMap<TerminalId, Int>()
    private var closed = false

    // The terminals that are in the store, as the snapshots last saw them, and whether what they showed is kept (`Settings.restoreContents`).
    private var snapshotsWanted: Set<TerminalId>? = null
    private var contentsKept: Boolean? = null

    // What each terminal's content was when its snapshot was last saved: a terminal that has not changed is not saved again. Under the monitor.
    private val savedVersions = HashMap<TerminalId, Long>()

    // The cursor that the sessions have been given: a change of the settings is given to the ones that are running.
    private var cursor: Pair<TerminalCursorShape, Boolean>? = null

    // The color schemes that were read, by name: a change of the default one is given to every terminal.
    private val themes = java.util.concurrent.ConcurrentHashMap<String, java.util.Optional<TerminalTheme>>()

    private fun theme(name: String): TerminalTheme? = themes.computeIfAbsent(name) { java.util.Optional.ofNullable(loadTheme(it)) }.orElse(null)

    /** Makes the sessions match the terminals of [state]: the shells of the new ones are started, the ones that are gone are closed. */
    @Synchronized
    fun sync(state: KattyState) {
        val terminals = state.allTerminals()
        val wanted = terminals.map { it.terminal.id }.toSet()
        val current = mutableHandles.value
        var next = current

        syncSnapshots(wanted, state.settings.restoreContents)

        val settingsCursor = state.settings.cursorShape.toTerminal() to state.settings.cursorBlink
        if (settingsCursor != cursor) {
            cursor = settingsCursor
            for (handle in current.values) handle.session?.setDefaultCursor(settingsCursor.first, settingsCursor.second)
        }

        for (id in current.keys - wanted) {
            next = next - id
            stop(current.getValue(id))
        }
        // A shell that is being started for a terminal that is gone is closed when it has started.
        starting.keys.retainAll(wanted)
        // And which shell a terminal that is gone was at: not in [stop], since [restart] reads it after stopping the old shell.
        generations.keys.retainAll(wanted)
        for (place in terminals) {
            val id = place.terminal.id
            val scheme = place.terminal.colorScheme ?: state.settings.colorScheme
            if (id !in current && id !in starting) {
                // Where it starts is only worked out for a terminal that is new: it asks the file system.
                begin(startingPlace(place.terminal, place.project.directory), scheme, generations[id] ?: 0)
            } else if (schemes[id] != scheme) {
                schemes[id] = scheme
                current[id]?.session?.let { session -> scope.launch(starter) { theme(scheme)?.let(session::setTheme) } }
            }
        }
        if (next !== current) mutableHandles.value = next
        applyVisibility()
    }

    // What the snapshots follow of the store: the ones of the terminals that are gone are deleted, and all of them when the setting is turned off.
    // Under the monitor; the files are touched on [starter].
    private fun syncSnapshots(wanted: Set<TerminalId>, keep: Boolean) {
        val store = snapshots ?: return
        if (keep != contentsKept) {
            contentsKept = keep
            if (!keep) {
                savedVersions.clear()
                scope.launch(starter) { store.clear() }
            }
        }
        if (keep && wanted != snapshotsWanted) {
            snapshotsWanted = wanted
            scope.launch(starter) { store.keepOnly(wanted) }
        }
    }

    // Saves the snapshot of each terminal whose content is not what it was when it was last saved: the terminals do not wait for it.
    private suspend fun autosave() {
        val store = snapshots ?: return
        if (contentsKept != true) return
        val due = synchronized(this) {
            mutableHandles.value.mapNotNull { (id, handle) ->
                val session = handle.session ?: return@mapNotNull null
                val version = session.contentVersion()
                if (savedVersions[id] == version) null else Triple(id, session, version)
            }
        }
        for ((id, session, version) in due) {
            val saved = withContext(Dispatchers.IO) { saveSnapshot(store, id, session) }
            if (saved) synchronized(this) { if (mutableHandles.value[id]?.session === session && contentsKept == true) savedVersions[id] = version }
        }
    }

    // The snapshot of [session], saved. `false` when it could not be made (a program in the middle of a long sequence) or written.
    private fun saveSnapshot(store: TerminalSnapshotStore, id: TerminalId, session: TerminalSession): Boolean = try {
        store.save(id, session.snapshot())
    } catch (e: TerminalSnapshotException) {
        logger.info("No snapshot of {}: {}", id.value, e.message)
        false
    }

    // The snapshot that a terminal that starts is made from, if it has one and what the terminals showed is kept.
    private fun snapshotOf(id: TerminalId): TerminalSnapshot? =
        if (store.state.value.settings.restoreContents) snapshots?.load(id) else null

    /** The color scheme that the terminal [id] is given. */
    @Synchronized
    internal fun schemeOf(id: TerminalId): String? = schemes[id]

    /** Shows the sessions of [ids] and pauses the others: what is not on the screen does not need frames. */
    @Synchronized
    fun setVisible(ids: Set<TerminalId>) {
        visible = ids
        applyVisibility()
    }

    private fun applyVisibility() {
        val shown = visible ?: return
        for ((id, handle) in mutableHandles.value) handle.session?.setVisible(id in shown)
    }

    /** Ends the shell of the terminal and starts another in its place: what "Reopen shell" does. */
    @Synchronized
    fun restart(id: TerminalId) {
        val state = store.state.value
        val location = state.locate(id) ?: return
        // At the root of the project, or of the worktree it was in: a new shell starts where the work is, not in the folder the last one was in.
        val directory = restartDirectory(location.project.directory, location.terminal.directory)
        if (directory != location.terminal.directory) store.setDirectory(id, directory)
        val spec = startingPlace(location.terminal.copy(directory = directory), location.project.directory).copy(running = null)
        val old = mutableHandles.value[id] ?: return
        stop(old)
        // And a plain one has none of what the old one showed, which must be gone before the new one starts (stop deletes it on another thread).
        snapshots?.delete(id)
        // A new shell is a plain one: what ran over the old is not started again. After the old one's poll can write no more.
        store.setRunning(id, null)
        mutableHandles.value = mutableHandles.value - id
        val generation = (generations[id] ?: 0) + 1
        generations[id] = generation
        begin(spec, spec.colorScheme ?: state.settings.colorScheme, generation)
        applyVisibility()
    }

    /**
     * Closes every session: the application is quitting. The followers are cancelled first, so that the shells ending does not clear what
     * ran over them. The sessions are closed together, off the calling thread, which waits for them for [CLOSE_ALL_TIMEOUT_MS] at most: each
     * one waits for its program to end.
     */
    fun close() {
        val all = synchronized(this) {
            closed = true
            starting.clear()
            alive.values.forEach { flag -> synchronized(flag) { flag.set(false) } }
            alive.clear()
            followers.values.forEach { it.cancel() }
            followers.clear()
            mutableHandles.value.also { mutableHandles.value = emptyMap() }
        }
        val sessions = all.values.mapNotNull { it.session }
        // What the terminals show now, before their shells are ended, for the next launch.
        snapshots?.takeIf { contentsKept == true }?.let { store -> saveAll(store, all) }
        val closed = CountDownLatch(sessions.size)
        for (session in sessions) {
            thread(name = "katty-close-session", isDaemon = true) {
                try {
                    session.close()
                } finally {
                    closed.countDown()
                }
            }
        }
        if (!closed.await(CLOSE_ALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)) logger.warn("Some sessions did not close within {}ms", CLOSE_ALL_TIMEOUT_MS)
    }

    // Starts the shell of [spec] on [starter], and publishes its handle once it has started, if the terminal still wants it. Under the monitor.
    private fun begin(spec: TerminalSpec, scheme: String, generation: Int) {
        schemes[spec.id] = scheme
        starting[spec.id] = generation
        scope.launch(starter) {
            val started = try {
                Result.success(startWithContents(spec, theme(scheme)))
            } catch (e: Exception) {
                logger.error("Could not start the shell of {}", spec.id.value, e)
                Result.failure(e)
            }
            val session = started.getOrNull()
            val wanted = synchronized(this@SessionRegistry) {
                // The terminal is gone, was started again, or the application quits: this shell is nobody's.
                if (closed || starting[spec.id] != generation) {
                    false
                } else {
                    starting.remove(spec.id)
                    val handle = session?.let { follow(spec, it, generation) }
                        ?: TerminalHandle(spec.id, null, started.exceptionOrNull()?.let { it.message ?: it.toString() }, generation)
                    // A cursor that was chosen while it started.
                    cursor?.let { (shape, blink) -> session?.setDefaultCursor(shape, blink) }
                    // A scheme that was chosen while it started.
                    schemes[spec.id]?.takeIf { it != scheme }?.let { latest -> session?.let { s -> scope.launch(starter) { theme(latest)?.let(s::setTheme) } } }
                    mutableHandles.update { it + (spec.id to handle) }
                    applyVisibility()
                    true
                }
            }
            if (!wanted) session?.close()
        }
    }

    // Starts the shell of [spec], with what the terminal showed when there is a snapshot of it. A snapshot that cannot be restored is deleted, and the shell
    // starts without it: a terminal never fails to start for its history.
    private fun startWithContents(spec: TerminalSpec, theme: TerminalTheme?): TerminalSession {
        val snapshot = snapshotOf(spec.id) ?: return start(spec, theme)
        return try {
            startRestored(spec, theme, snapshot)
        } catch (e: TerminalSnapshotException) {
            logger.warn("The snapshot of {} could not be restored: its shell starts without it", spec.id.value, e)
            snapshots?.delete(spec.id)
            start(spec, theme)
        }
    }

    // The handle of the shell that started, with what follows it. Under the monitor.
    private fun follow(spec: TerminalSpec, session: TerminalSession, generation: Int): TerminalHandle {
        val handle = TerminalHandle(spec.id, session, generation = generation, shellName = shellName())
        // Where the shell goes is where it starts the next time, and what repository it is in is read again then, and when a command ends.
        val flag = AtomicBoolean(true).also { alive[spec.id] = it }
        // What the followers write, only while the session is followed.
        val whileFollowed = { write: () -> Unit -> synchronized(flag) { if (flag.get()) write() } }
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
                // Finding the conversation of an agent reads its files: it is done again every few seconds, not at every poll.
                val lookups = SessionLookups()
                while (isActive) {
                    // The shell ended (after `exec vim`, the program was the shell): nothing runs over it any more, and nothing is to be started
                    // again over it at the next launch. Quitting cancels this before it closes the sessions, which keeps what ran then.
                    if (session.exited.value != null) {
                        whileFollowed { store.setRunning(spec.id, null) }
                        break
                    }
                    val systemName = session.foregroundProcessName()
                    // A program that is a script has its interpreter's name (Aider is `Python`): the agent that it is says its own.
                    val command = if (systemName != null) withContext(Dispatchers.IO) { session.foregroundCommand() } else null
                    // A program that is a script has its interpreter's name (Aider is `Python`), and one that renames its process to the path it was
                    // started with (OpenCode 1) has one that the system cuts: its command line says which agent it is.
                    val name = Agent.executableOf(systemName, command?.argv)
                    handle.processState.value = name
                    if (name == null || name == handle.shellName) reportedSession.set(null)
                    if (mutableProcessNames.value[spec.id] != name || spec.id !in mutableProcessNames.value) whileFollowed { mutableProcessNames.update { it + (spec.id to name) } }
                    // What the program is, to be started again: with what the shell is for the program that is not there (a name that is not known says nothing).
                    if (name != null) {
                        var running = command?.let { withContext(Dispatchers.IO) { it.toRunning(reportedSession = reportedSession.get(), lookups = lookups) } }
                        // An agent that only guesses its conversation by its directory cannot tell it from another's when another terminal runs the same agent there:
                        // it comes back on none (an OpenCode, unless it reported its session, guesses as well).
                        var ambiguous = false
                        if (running != null) {
                            val agent = running.agent?.let(Agent::byId)
                            val guessed = agent?.byDirectory == true || (agent == Agent.OPENCODE && reportedSession.get() == null)
                            val others = store.state.value.allTerminals().filter { it.terminal.id != spec.id }.map { it.terminal.running }
                            val apart = running.apartFromOthers(others, guessed)
                            ambiguous = apart != running
                            running = apart
                        }
                        // omp says itself what session its terminal is in, and `fresh` for none (after `/new`), and a Cursor agent whose chat is empty is the same: not
                        // knowing is not what that is, so the session it came back on is not put back.
                        if (command != null && running != null && resumed?.second != null && running.agent == resumed.first && running.agent != Agent.OMP.id && running.agent != Agent.CURSOR.id &&
                            // What an Aider comes back on is the chat of the directory, and the other Aider loads the same one: it is not the one that was resumed.
                            !(ambiguous && running.agent == Agent.AIDER.id)
                        ) {
                            if (resumedPid == 0) resumedPid = command.pid
                            // An OpenCode that has not worked has said nothing of its session, and its directory only says the newest one: that may be another
                            // OpenCode's, so the session it came back on stays until it says its own.
                            val guessed = running.agent == Agent.OPENCODE.id && reportedSession.get() == null
                            if (command.pid == resumedPid && (running.agentSession == null || guessed)) running = running.copy(agentSession = resumed.second)
                        }
                        if (command != null) graceUntil = 0L
                        if (running != null || System.nanoTime() >= graceUntil) whileFollowed { store.setRunning(spec.id, running) }
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
                whileFollowed { mutableGitInfos.update { it + (spec.id to info) } }
            }
        }
        return handle
    }

    // Saves the snapshot of every terminal together, on threads of their own, waiting [CLOSE_ALL_TIMEOUT_MS] at most: the application is quitting.
    private fun saveAll(store: TerminalSnapshotStore, handles: Map<TerminalId, TerminalHandle>) {
        val withSessions = handles.mapNotNull { (id, handle) -> handle.session?.let { id to it } }
        val done = CountDownLatch(withSessions.size)
        for ((id, session) in withSessions) {
            thread(name = "katty-save-snapshot", isDaemon = true) {
                try {
                    saveSnapshot(store, id, session)
                } finally {
                    done.countDown()
                }
            }
        }
        if (!done.await(CLOSE_ALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)) logger.warn("Some snapshots were not saved within {}ms", CLOSE_ALL_TIMEOUT_MS)
    }

    private fun stop(handle: TerminalHandle) {
        alive.remove(handle.id)?.let { flag -> synchronized(flag) { flag.set(false) } }
        followers.remove(handle.id)?.cancel()
        mutableProcessNames.update { it - handle.id }
        mutableGitInfos.update { it - handle.id }
        schemes.remove(handle.id)
        val session = handle.session ?: run {
            snapshots?.let { store -> scope.launch(Dispatchers.IO) { store.delete(handle.id) } }
            return
        }
        // The terminal is gone, or its shell is started again: what it showed is not for the next launch.
        savedVersions.remove(handle.id)
        snapshots?.let { store -> scope.launch(Dispatchers.IO) { store.delete(handle.id) } }
        // Closing waits for the shell to end: not on the thread that draws.
        scope.launch(Dispatchers.IO) { session.close() }
    }
}

// What the shell is called, as its program is: the one that the user's login shell is.
/** How long quitting waits for the sessions to close, all together. */
private const val CLOSE_ALL_TIMEOUT_MS = 3_000L

// How a restored terminal says when what it shows was saved.
private val RESTORED_FORMAT: DateTimeFormatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)

// How often what the terminals show is saved, for the ones that changed: a crash loses what came after.
private const val AUTOSAVE_MS = 30_000L

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

private fun CursorShape.toTerminal(): TerminalCursorShape = when (this) {
    CursorShape.BLOCK -> TerminalCursorShape.BLOCK
    CursorShape.BAR -> TerminalCursorShape.BAR
}

private fun startShell(spec: TerminalSpec, theme: TerminalTheme?, shellIntegration: Path?, settings: Settings, snapshot: TerminalSnapshot? = null): TerminalSession {
    // A folder that is gone is not a reason for the terminal to have no shell: it starts in the user's home.
    // The program that ran over the shell is typed in the new one, in the folder it was in, which is the folder that the shell goes back to when
    // it ends.
    val running = spec.running
    val directory = (running?.directory ?: spec.directory).takeIf { File(it).isDirectory } ?: spec.directory.takeIf { File(it).isDirectory }
    return TerminalSession.start(
        // At the size that the old terminal had, so that its content is reflowed once, when the pane gives the real size, and not twice.
        cols = snapshot?.cols ?: 80, rows = snapshot?.rows ?: 24, directory = directory, theme = theme, typeAtPrompt = running?.toTyped(RESTORE_POLICY),
        cursorShape = settings.cursorShape.toTerminal(), cursorBlink = settings.cursorBlink,
        snapshot = snapshot, restoredNote = snapshot?.let { "Restored session: ${RESTORED_FORMAT.format(Instant.ofEpochMilli(it.createdAt).atZone(ZoneId.systemDefault()))}" },
        shellIntegrationDirectory = shellIntegration,
    )
}
