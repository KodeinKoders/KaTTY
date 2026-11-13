package app.katty.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

private val logger = LoggerFactory.getLogger("app.katty.core.RecentProjects")

/**
 * A project that was opened, as the list of the recent ones keeps it. [openedAt] is in milliseconds since the epoch.
 *
 * @param saved The project as it was when it was removed (its workspaces, splits and terminals, with what ran in them), which opening it again
 * restores; `null` for a folder that was not removed, or that had no workspace.
 */
@Serializable
public data class RecentProject(val directory: String, val name: String, val openedAt: Long, val saved: Project? = null) {
    /** The terminals of [saved]. */
    public val savedTerminals: List<TerminalSpec> get() = saved?.workspaces?.flatMap { it.layout.terminals() }.orEmpty()
}

@Serializable
internal data class RecentProjectsData(val version: Int = RecentProjectsFile.VERSION, val projects: List<RecentProject> = emptyList())

/**
 * The file that keeps the recent projects, apart from the state of the application. It is a history and not the user's work: a file that is
 * missing, unreadable or of a later version is an empty list, and is written over.
 */
public class RecentProjectsFile(private val path: Path) {
    public fun load(): List<RecentProject> {
        if (!Files.exists(path)) return emptyList()
        return try {
            JSON.decodeFromString(RecentProjectsData.serializer(), Files.readString(path)).takeIf { it.version <= VERSION }?.projects
                ?: emptyList<RecentProject>().also { logger.warn("{} is of a later version", path) }
        } catch (e: SerializationException) {
            logger.warn("{} cannot be read", path, e)
            emptyList()
        } catch (e: IllegalArgumentException) {
            logger.warn("{} cannot be read", path, e)
            emptyList()
        } catch (e: IOException) {
            logger.warn("{} cannot be read", path, e)
            emptyList()
        }
    }

    /** Writes [projects] through a file of its own, so that a crash does not leave half of it; one write at a time. */
    @Synchronized
    public fun save(projects: List<RecentProject>) {
        writeAtomically(path, JSON.encodeToString(RecentProjectsData.serializer(), RecentProjectsData(projects = projects)))
    }

    internal companion object {
        const val VERSION = 1
        val JSON = Json {
            prettyPrint = true
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}

/**
 * The last [MAX] projects that were opened, newest first, one for each folder. A project is recorded when it is added to the store and again, with
 * the name it had and **its state** ([RecentProject.saved]), when it is removed, whatever the route: the additions are seen in the store's state, and
 * the removals are told by the store ([KattyStore.addRemovalListener]) before [KattyStore.removeProject] returns, so that [savedTerminals] has the
 * terminals of a removed project before anything that follows the store sees it gone. Projects that are open when the application starts are
 * recorded when they are removed. A folder that is added again, restored or not, has its saved state dropped.
 */
public class RecentProjects(
    private val store: KattyStore,
    private val file: RecentProjectsFile,
    scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val list = MutableStateFlow(file.load().take(MAX))

    /** What is kept, newest first, whether the folder is still a project or not: [closed] says which can be opened. */
    public val entries: StateFlow<List<RecentProject>> get() = list

    private val saved = MutableStateFlow(terminalsOf(list.value))

    /** The terminals of the projects that were removed and whose state is kept: what they showed is kept with it. */
    public val savedTerminals: StateFlow<Set<TerminalId>> get() = saved

    // The projects seen last, by folder, read now so that a change between here and the first collected state is not missed.
    private var seen: Set<String> = folders(store.state.value)

    private val removalListener: (Project) -> Unit = ::removed

    init {
        store.addRemovalListener(removalListener)
    }

    private val job: Job = scope.launch {
        store.state.collect {
            synchronized(this@RecentProjects) {
                // The state as it is now, not the one collected: one from before a removal would take the folder for one that is added again, after
                // the removal was recorded, and drop its state.
                val now = names(store.state.value)
                // What came: the user opened it. What went is told by the store, with its state.
                for ((directory, name) in now) if (directory !in seen) record(directory, name)
                seen = now.keys
            }
        }
    }

    // A project was removed: it is recorded with what it was. Its folder is not one that is seen any more, so that adding it again records it.
    @Synchronized
    private fun removed(project: Project) {
        record(project.directory, project.name, project.takeIf { it.workspaces.isNotEmpty() })
        seen = seen - project.directory
    }

    @Synchronized
    internal fun record(directory: String, name: String, state: Project? = null) {
        update((listOf(RecentProject(directory, name, clock(), state)) + list.value.filterNot { it.directory == directory }).take(MAX))
    }

    /** The state kept for the folder [directory], `null` when there is none. */
    public fun saved(directory: String): Project? = list.value.firstOrNull { it.directory == directory }?.saved

    /** Forgets the state kept for the folder [directory]: it is opened again, as it was or not. */
    @Synchronized
    public fun dropSaved(directory: String) {
        if (list.value.none { it.directory == directory && it.saved != null }) return
        update(list.value.map { if (it.directory == directory) it.copy(saved = null) else it })
    }

    // Under the monitor.
    private fun update(updated: List<RecentProject>) {
        list.value = updated
        saved.value = terminalsOf(updated)
        try {
            file.save(updated)
        } catch (e: IOException) {
            // The list works without it, and the next change tries again.
            logger.error("Could not save the recent projects", e)
        }
    }

    /** The recent projects that can be opened: not a project of [state] now, and a folder that is still there. */
    public fun closed(state: KattyState): List<RecentProject> {
        val open = state.projects.map { it.directory }.toSet()
        return entries.value.filter { it.directory !in open && Files.isDirectory(Path.of(it.directory)) }
    }

    public fun stop() {
        store.removeRemovalListener(removalListener)
        job.cancel()
    }

    private fun names(state: KattyState) = state.projects.associate { it.directory to it.name }

    private fun folders(state: KattyState) = state.projects.map { it.directory }.toSet()

    private fun terminalsOf(entries: List<RecentProject>) = entries.flatMap { entry -> entry.savedTerminals.map { it.id } }.toSet()

    public companion object {
        public const val MAX: Int = 50
    }
}
