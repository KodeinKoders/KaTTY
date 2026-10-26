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

/** A project that was opened, as the list of the recent ones keeps it. [openedAt] is in milliseconds since the epoch. */
@Serializable
public data class RecentProject(val directory: String, val name: String, val openedAt: Long)

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
 * The last [MAX] projects that were opened, newest first, one for each folder. A project is recorded when it is added to the store and again,
 * with the name it had, when it is removed, whatever the route: the store's state is what is watched, so nothing has to call this.
 * Projects that are open when the application starts are recorded when they are removed.
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

    // The projects seen last, by folder, read now so that a change between here and the first collected state is not missed.
    private var seen: Map<String, String> = names(store.state.value)

    private val job: Job = scope.launch {
        store.state.collect { state ->
            val now = names(state)
            synchronized(this@RecentProjects) {
                // What came, then what went (with the name it had): both are things the user did with the project.
                for ((directory, name) in now) if (directory !in seen) record(directory, name)
                for ((directory, name) in seen) if (directory !in now) record(directory, name)
                seen = now
            }
        }
    }

    @Synchronized
    internal fun record(directory: String, name: String) {
        val updated = (listOf(RecentProject(directory, name, clock())) + list.value.filterNot { it.directory == directory }).take(MAX)
        list.value = updated
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
        job.cancel()
    }

    private fun names(state: KattyState) = state.projects.associate { it.directory to it.name }

    public companion object {
        public const val MAX: Int = 50
    }
}
