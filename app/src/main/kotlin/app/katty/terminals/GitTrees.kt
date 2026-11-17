package app.katty.terminals

import app.katty.core.GitInfo
import app.katty.core.GitReader
import app.katty.core.GitStatusReader
import app.katty.core.GitStatusReading
import app.katty.core.GitTree
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

private val logger = LoggerFactory.getLogger("app.katty.terminals.GitTrees")

/**
 * What git says of each working tree that a terminal is in, read once for all the terminals of a tree: where it is ([GitReader]) and its status
 * ([GitStatusReader], which runs `git status`).
 *
 * A tree is read when it is first taken, then whenever [GitTreeWatcher] tells that something of it changed, once it has been quiet for
 * [QUIET_MS] and at most once every [MIN_INTERVAL_MS]: a build that writes without a pause in a folder of the tree does not run git without a
 * pause. It is also read every [SAFETY_MS], for what the file system did not tell, every [POLL_MS] when its folders cannot be watched (not the
 * JetBrains Runtime), and when one of its terminals asks ([Lease.refresh]: a command ended).
 *
 * **A tree that is too slow** (a status that took over [SLOW_MS] twice in a row, or none in its time: a home folder under git, a huge monorepo) is no
 * longer read on what the file system tells, only on the timer and when asked; what it said stays.
 *
 * @param watch Watches a tree ([GitTreeWatcher.watch]): what it says only makes a tree read sooner, and a tree that it cannot watch, or whose
 * watch fails, is read every [POLL_MS].
 * @param readStatus Runs `git status`: tests give their own.
 * @param dispatcher Where the trees are read: they start git and read files.
 */
internal class GitTrees(
    private val scope: CoroutineScope,
    private val watch: (GitTree, () -> Unit) -> GitTreeWatcher.Watching?,
    private val readStatus: (Path) -> GitStatusReading? = { GitStatusReader.read(it) },
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val quietMs: Long = QUIET_MS,
    private val minIntervalMs: Long = MIN_INTERVAL_MS,
    private val safetyMs: Long = SAFETY_MS,
    private val slowMs: Long = SLOW_MS,
) {
    /** A terminal's hold on a tree: what it says, until [close]. */
    inner class Lease internal constructor(private val entry: Entry) : AutoCloseable {
        val tree: GitTree get() = entry.tree

        /** What git says of the tree, `null` until it has been read, and when it is in no repository any more. */
        val info: StateFlow<GitInfo?> get() = entry.info

        /** Reads the tree again, even one that is too slow (within the same limits): a command ended in one of its terminals. */
        fun refresh() {
            entry.asked.set(true)
            entry.wake.trySend(Unit)
        }

        private val closed = AtomicBoolean(false)

        override fun close() {
            if (closed.compareAndSet(false, true)) release(entry)
        }
    }

    internal inner class Entry(val tree: GitTree) {
        val info = MutableStateFlow<GitInfo?>(null)
        val wake = Channel<Unit>(Channel.CONFLATED)

        // Whether a terminal asked, which a tree that is too slow still answers.
        val asked = AtomicBoolean(false)
        var holders = 0
        var job: Job? = null
        var watching: GitTreeWatcher.Watching? = null
    }

    private val entries = HashMap<Path, Entry>()

    /** Holds [tree] until the lease is closed: the first hold starts reading and watching it, the last one's close stops. */
    @Synchronized
    fun acquire(tree: GitTree): Lease {
        val entry = entries.getOrPut(tree.root) { Entry(tree).also(::start) }
        entry.holders++
        return Lease(entry)
    }

    @Synchronized
    private fun release(entry: Entry) {
        if (--entry.holders > 0 || entries[entry.tree.root] !== entry) return
        entries.remove(entry.tree.root)
        entry.job?.cancel()
        entry.watching?.close()
    }

    // Under the monitor.
    private fun start(entry: Entry) {
        entry.job = scope.launch(dispatcher) {
            // Whatever happens to the watch, the tree is still read: a watch that fails only makes it read on a timer.
            val watching = try {
                watch(entry.tree) { entry.wake.trySend(Unit) }
            } catch (e: Exception) {
                logger.warn("Cannot watch {}: it is read every {} s instead", entry.tree.root, POLL_MS / 1000, e)
                null
            } catch (e: LinkageError) {
                logger.warn("Cannot watch {}: it is read every {} s instead", entry.tree.root, POLL_MS / 1000, e)
                null
            }
            val stillHeld = synchronized(this@GitTrees) { (entries[entry.tree.root] === entry).also { if (it) entry.watching = watching } }
            if (!stillHeld) {
                watching?.close()
                return@launch
            }
            // Without a watch of all its folders, an edit is only seen by reading again.
            val timerMs = if (watching?.recursive == true) safetyMs else POLL_MS
            var slowReads = 0
            var lastRead = 0L
            while (true) {
                val started = System.nanoTime()
                val read = try {
                    read(entry.tree)
                } catch (e: Exception) {
                    logger.warn("Cannot read the git status of {}", entry.tree.root, e)
                    entry.info.value
                }
                val tookMs = (System.nanoTime() - started) / 1_000_000
                lastRead = System.nanoTime()
                entry.info.value = read
                if (read != null && tookMs > slowMs) {
                    if (++slowReads == SLOW_READS) logger.info("git status takes too long in {} ({} ms): it is only read every {} s", entry.tree.root, tookMs, safetyMs / 1000)
                } else {
                    slowReads = 0
                }
                // What the file system tells, or the timer. A tree that is too slow only answers the timer and the terminals.
                withTimeoutOrNull(timerMs) {
                    while (true) {
                        entry.wake.receive()
                        if (slowReads < SLOW_READS || entry.asked.get()) break
                    }
                }
                entry.asked.set(false)
                // Until it is quiet, and not more often than the limit.
                delay(quietMs)
                while (entry.wake.tryReceive().isSuccess) delay(quietMs)
                val sinceMs = (System.nanoTime() - lastRead) / 1_000_000
                if (sinceMs < minIntervalMs) delay(minIntervalMs - sinceMs)
                entry.wake.tryReceive()
            }
        }
    }

    // Where the tree is, and its status: `null` when it is no repository any more.
    private fun read(tree: GitTree): GitInfo? =
        GitReader.read(tree.root)?.copy(tree = tree.root.toString(), status = readStatus(tree.root))

    internal companion object {
        /** How long a tree must be quiet before it is read: an editor writes a file in several steps, a checkout writes many. */
        const val QUIET_MS = 100L

        /** The least time between two readings of a tree. */
        const val MIN_INTERVAL_MS = 1_000L

        /** How often a tree is read whatever the file system said. */
        const val SAFETY_MS = 60_000L

        /** How often a tree whose folders cannot be watched is read. */
        const val POLL_MS = 5_000L

        /** A status that takes longer than this is slow. */
        const val SLOW_MS = 2_000L

        /** How many slow statuses in a row make a tree too slow. */
        const val SLOW_READS = 2
    }
}
