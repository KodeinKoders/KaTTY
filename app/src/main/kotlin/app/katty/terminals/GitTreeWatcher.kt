package app.katty.terminals

import app.katty.core.GitTree
import com.sun.nio.file.ExtendedWatchEventModifier
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.ClosedWatchServiceException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.nio.file.StandardWatchEventKinds.ENTRY_DELETE
import java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY
import java.nio.file.StandardWatchEventKinds.OVERFLOW
import java.nio.file.WatchEvent
import java.nio.file.WatchKey
import java.nio.file.WatchService
import kotlin.concurrent.thread

private val logger = LoggerFactory.getLogger("app.katty.terminals.GitTreeWatcher")

/**
 * Tells when what `git status` says of a working tree may have changed: a file of the tree (an agent, an IDE, another terminal writes it, while
 * the shell of the terminal says nothing), or what git keeps of it (its `HEAD`, its index, its refs, what it is in the middle of).
 *
 * The tree is watched **with all its folders** ([ExtendedWatchEventModifier.FILE_TREE]), which the JetBrains Runtime's watch service of macOS
 * (`MacOSXWatchService`, FSEvents) does with one stream for the whole tree: measured, an edit four folders down was told in about 350 ms, and the
 * ~5 900 files of Ghostty took 64 ms to register, with no thread more. Another JDK refuses it (its `PollingWatchService`): then only the folder
 * itself is watched, which tells nothing of the tree, and [Watching.recursive] says so, for the reader to poll.
 *
 * A linked working tree, or a submodule, has its git directory outside it: that directory is watched too, and so are the refs that its trees share.
 * What git writes that says nothing of the status (its objects, its logs, its locks, the file system monitor's daemon) is not told.
 *
 * There is one [WatchService] for all, made at the first watch, with a thread of its own, and one registration for each folder, shared by whoever
 * watches it.
 *
 * @param fileTree Whether the trees are watched with all their folders. Tests turn it off for what another JDK does.
 */
internal class GitTreeWatcher(private val fileTree: Boolean = true) : AutoCloseable {
    /** What [watch] gives: whether the whole tree is watched, and how to stop. */
    class Watching(val recursive: Boolean, private val stop: () -> Unit) : AutoCloseable {
        override fun close() = stop()
    }

    // What a registration watches: the root of a tree, a git directory that is outside its tree, the one that trees share, or their refs.
    private enum class Role(val recursive: Boolean) { TREE(true), GIT_DIR(false), COMMON_DIR(false), REFS(true) }

    // [recursive]: whether it has all its folders, which the service may refuse.
    private class Registration(val directory: Path, val role: Role, val key: WatchKey, val recursive: Boolean) {
        val listeners = ArrayList<() -> Unit>()
    }

    private var service: WatchService? = null
    private val byDirectory = HashMap<Pair<Path, Role>, Registration>()
    private val byKey = HashMap<WatchKey, Registration>()
    private var closed = false

    /**
     * Calls [onChange] on the watcher's thread whenever something of [tree] changes that may change its status, until what it returns is closed.
     * It returns `null` when the tree itself cannot be watched. It reads the file system: not on the UI thread.
     */
    fun watch(tree: GitTree, onChange: () -> Unit): Watching? {
        val wanted = buildList {
            add(tree.root to Role.TREE)
            if (tree.gitDirOutside) {
                add(tree.gitDir to Role.GIT_DIR)
                if (tree.commonDir != tree.gitDir) add(tree.commonDir to Role.COMMON_DIR)
                add(tree.commonDir.resolve("refs") to Role.REFS)
            }
        }
        synchronized(this) {
            if (closed) return null
            val service = service ?: start() ?: return null
            val made = ArrayList<Registration>()
            for ((directory, role) in wanted) {
                val registration = registration(service, directory, role)
                if (registration == null) {
                    // Without its tree there is nothing to tell; a git directory that cannot be watched only loses what happens in it.
                    if (role == Role.TREE) return null
                    continue
                }
                registration.listeners += onChange
                made += registration
            }
            return Watching(made.filter { it.role.recursive }.all { it.recursive }) { synchronized(this) { made.forEach { stop(it, onChange) } } }
        }
    }

    // The registration of [directory] for [role], made when there is none. Under the monitor.
    private fun registration(service: WatchService, directory: Path, role: Role): Registration? {
        // The same folder by two paths would be two registrations of one key.
        val real = try {
            directory.toRealPath()
        } catch (e: IOException) {
            return null
        }
        byDirectory[real to role]?.let { return it }
        val kinds = arrayOf<WatchEvent.Kind<*>>(ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE)
        val made = try {
            if (role.recursive && fileTree) {
                try {
                    Registration(real, role, real.register(service, kinds, ExtendedWatchEventModifier.FILE_TREE), recursive = true)
                } catch (e: UnsupportedOperationException) {
                    logger.info("This Java cannot watch the folders of a tree: the git status of the terminals is read every few seconds instead")
                    Registration(real, role, real.register(service, *kinds), recursive = false)
                } catch (e: LinkageError) {
                    // A runtime without jdk.unsupported, which has the modifier: a bundle whose runtime was made without it.
                    logger.warn("This Java has no ExtendedWatchEventModifier (jdk.unsupported): the git status of the terminals is read every few seconds instead", e)
                    Registration(real, role, real.register(service, *kinds), recursive = false)
                }
            } else {
                Registration(real, role, real.register(service, *kinds), recursive = !role.recursive)
            }
        } catch (e: IOException) {
            logger.debug("Cannot watch {}", real, e)
            return null
        }
        byDirectory[real to role] = made
        byKey[made.key] = made
        return made
    }

    // Under the monitor.
    private fun stop(registration: Registration, onChange: () -> Unit) {
        registration.listeners.remove(onChange)
        if (registration.listeners.isEmpty() && byDirectory[registration.directory to registration.role] === registration) {
            registration.key.cancel()
            byDirectory.remove(registration.directory to registration.role)
            byKey.remove(registration.key)
        }
    }

    // Under the monitor.
    private fun start(): WatchService? {
        val made = try {
            FileSystems.getDefault().newWatchService()
        } catch (e: IOException) {
            logger.warn("Cannot watch the repositories: what changes while a program runs is not seen", e)
            return null
        }
        service = made
        thread(name = "katty-git-trees", isDaemon = true) { run(made) }
        return made
    }

    private fun run(service: WatchService) {
        while (true) {
            val key = try {
                service.take()
            } catch (e: ClosedWatchServiceException) {
                return
            } catch (e: InterruptedException) {
                return
            }
            val events = key.pollEvents()
            key.reset()
            val registration = synchronized(this) { byKey[key] } ?: continue
            if (events.none { it.kind() == OVERFLOW || isRelevant(registration, it.kind(), it.context() as? Path) }) continue
            val listeners = synchronized(this) { registration.listeners.toList() }
            for (listener in listeners) {
                try {
                    listener()
                } catch (e: Exception) {
                    logger.error("A listener of {} failed", registration.directory, e)
                }
            }
        }
    }

    @Synchronized
    override fun close() {
        closed = true
        byDirectory.clear()
        byKey.clear()
        try {
            service?.close()
        } catch (e: IOException) {
            logger.debug("Closing the watch service failed", e)
        }
    }

    internal companion object {
        // What git keeps in a git directory that says what the status is. `FETCH_HEAD` and `packed-refs` move the upstream, which is how far the
        // branch is ahead or behind; the others say what git is in the middle of.
        private val GIT_DIR_FILES = setOf(
            "HEAD", "index", "refs", "packed-refs", "FETCH_HEAD",
            "MERGE_HEAD", "REBASE_HEAD", "CHERRY_PICK_HEAD", "REVERT_HEAD", "BISECT_LOG", "rebase-merge", "rebase-apply",
        )

        /**
         * Whether an event of [path] (relative to what is watched, as the service gives it; `null` when it does not say) may change the status:
         * a file of the tree, or one of [GIT_DIR_FILES] of a git directory, but no lock. What git-status-watch's `is_relevant` does.
         */
        private fun isRelevant(registration: Registration, kind: WatchEvent.Kind<*>, path: Path?): Boolean {
            path ?: return true
            if (path.fileName?.toString()?.endsWith(".lock") == true) return false
            // Windows tells a folder whose entries changed as modified (`.git` when an object is written in `.git/objects`: measured), which says
            // nothing of its own: what changed in it is told for itself, when the folders of the tree are watched.
            if (kind == ENTRY_MODIFY && registration.recursive && Files.isDirectory(registration.directory.resolve(path))) return false
            return when (registration.role) {
                Role.TREE -> path.getName(0).toString() != ".git" || path.nameCount == 1 || path.getName(1).toString() in GIT_DIR_FILES
                Role.GIT_DIR, Role.COMMON_DIR -> path.getName(0).toString() in GIT_DIR_FILES
                Role.REFS -> true
            }
        }
    }
}
