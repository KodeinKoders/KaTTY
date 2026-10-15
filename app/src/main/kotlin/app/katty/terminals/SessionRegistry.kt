package app.katty.terminals

import androidx.compose.ui.focus.FocusRequester
import app.katty.core.GitInfo
import app.katty.core.GitReader
import app.katty.core.KattyState
import app.katty.core.KattyStore
import app.katty.core.TerminalId
import app.katty.core.TerminalSpec
import app.katty.terminal.TerminalSession
import app.katty.terminal.TerminalTheme
import app.katty.terminal.themes.ITermColorSchemes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.conflate
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

    // What each session follows and the scheme it has now.
    private val followers = HashMap<TerminalId, Job>()
    private val schemes = HashMap<TerminalId, String>()
    private var visible: Set<TerminalId>? = null
    private var generations = HashMap<TerminalId, Int>()

    /** Makes the sessions match the terminals of [state]. Call it from one thread, the UI's. */
    fun sync(state: KattyState) {
        val wanted = state.allTerminals().associate { it.terminal.id to it.terminal }
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
        val spec = state.locate(id)?.terminal ?: return
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
            launch { session.workingDirectory.filterNotNull().collect { store.setDirectory(spec.id, it) } }
            // What the shell wrote stays on the screen, and says that it is over.
            launch {
                session.exited.filterNotNull().first()
                session.writeToScreen("\r\n[Process completed]\r\n")
            }
            merge(session.workingDirectory.map { }, session.events.filterIsInstance<TerminalEvent.CommandFinished>().map { }).conflate().collect {
                val directory = session.workingDirectory.value ?: spec.directory
                handle.gitState.value = withContext(Dispatchers.IO) { GitReader.read(Path.of(directory)) }
            }
        }
        return handle
    }

    private fun stop(handle: TerminalHandle) {
        followers.remove(handle.id)?.cancel()
        schemes.remove(handle.id)
        val session = handle.session ?: return
        // Closing waits for the shell to end: not on the thread that draws.
        scope.launch(Dispatchers.IO) { session.close() }
    }
}

// What the shell is called, as its program is: the one that the user's login shell is.
private fun shellName(): String = File(System.getenv("SHELL") ?: "/bin/zsh").name.ifEmpty { "shell" }

private fun loadColorScheme(name: String): TerminalTheme? =
    try {
        ITermColorSchemes.load(name)
    } catch (e: Exception) {
        logger.error("Could not load the color scheme {}", name, e)
        null
    }

private fun startShell(spec: TerminalSpec, theme: TerminalTheme?): TerminalSession {
    // A folder that is gone is not a reason for the terminal to have no shell: it starts in the user's home.
    val directory = spec.directory.takeIf { File(it).isDirectory }
    return TerminalSession.start(cols = 80, rows = 24, directory = directory, theme = theme)
}
