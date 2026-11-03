package app.katty.terminals

import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.ClosedWatchServiceException
import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.nio.file.StandardWatchEventKinds.ENTRY_DELETE
import java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY
import java.nio.file.StandardWatchEventKinds.OVERFLOW
import java.nio.file.WatchKey
import java.nio.file.WatchService
import kotlin.concurrent.thread

private val logger = LoggerFactory.getLogger("app.katty.terminals.GitHeadWatcher")

/**
 * Tells when the `HEAD` of a git directory changes. A branch that is checked out by an agent that runs in a terminal, by an IDE or in another
 * terminal changes it without the shell of the terminal saying anything: the shell reports its directory and the end of a command, and an agent
 * that switches branches is still the command that runs.
 *
 * There is one [WatchService] for all, made at the first watch, with a thread of its own, and one watch for each git directory, shared by whoever
 * watches it. On the JetBrains Runtime the service is the one of macOS (`MacOSXWatchService`, FSEvents), which told a checkout 150 ms after it;
 * another JDK polls, every 10 s or so. What happens to the other files of the directory (the index, which `git status` rewrites) is ignored.
 */
internal class GitHeadWatcher : AutoCloseable {
    private class Watch(val directory: Path, val key: WatchKey) {
        val listeners = ArrayList<() -> Unit>()
    }

    private var service: WatchService? = null
    private val byDirectory = HashMap<Path, Watch>()
    private val byKey = HashMap<WatchKey, Watch>()
    private var closed = false

    /**
     * Calls [onChange] on the watcher's thread whenever the `HEAD` file of [gitDirectory] changes, until what it returns is closed. It returns `null`
     * when the directory cannot be watched. It reads the file system: not on the UI thread.
     */
    fun watch(gitDirectory: Path, onChange: () -> Unit): AutoCloseable? {
        // The same folder by two paths would be two watches of one key.
        val directory = try {
            gitDirectory.toRealPath()
        } catch (e: IOException) {
            return null
        }
        synchronized(this) {
            if (closed) return null
            val watch = byDirectory[directory] ?: run {
                val service = service ?: start() ?: return null
                val key = try {
                    directory.register(service, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE)
                } catch (e: IOException) {
                    logger.debug("Cannot watch {}", directory, e)
                    return null
                }
                Watch(directory, key).also {
                    byDirectory[directory] = it
                    byKey[key] = it
                }
            }
            watch.listeners += onChange
            return AutoCloseable { stop(watch, onChange) }
        }
    }

    @Synchronized
    private fun stop(watch: Watch, onChange: () -> Unit) {
        watch.listeners.remove(onChange)
        if (watch.listeners.isEmpty() && byDirectory[watch.directory] === watch) {
            watch.key.cancel()
            byDirectory.remove(watch.directory)
            byKey.remove(watch.key)
        }
    }

    // Under the monitor.
    private fun start(): WatchService? {
        val made = try {
            FileSystems.getDefault().newWatchService()
        } catch (e: IOException) {
            logger.warn("Cannot watch the repositories: a branch that changes while a program runs is not seen", e)
            return null
        }
        service = made
        thread(name = "katty-git-heads", isDaemon = true) { run(made) }
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
            // Git writes `HEAD.lock` and moves it over `HEAD`; events that were lost may have been that.
            val ofHead = events.any { it.kind() == OVERFLOW || (it.context() as? Path)?.fileName?.toString() == "HEAD" }
            if (!ofHead) continue
            val listeners = synchronized(this) { byKey[key]?.listeners?.toList() } ?: continue
            for (listener in listeners) {
                try {
                    listener()
                } catch (e: Exception) {
                    logger.error("A listener of {} failed", key.watchable(), e)
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
}
