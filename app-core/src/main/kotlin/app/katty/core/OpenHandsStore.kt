package app.katty.core

import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isDirectory

private val logger = LoggerFactory.getLogger("app.katty.core.OpenHandsStore")

private val ID = Regex("[0-9a-fA-F]{8}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{12}")
private val WORKING_DIR = Regex("\"workspace\"\\s*:\\s*\\{\\s*\"working_dir\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")

/**
 * What OpenHands keeps of its conversations: a folder for each in `~/.openhands/conversations/<id in hexadecimal>/`, with the state of the conversation
 * (`base_state.json`, which has the directory it works in, `workspace.working_dir`) and its events (`events/event-<number>-<id>.json`). A conversation is made
 * when the first message is sent, not when the program starts, and the system prompt is its first event.
 *
 * Nothing tells which process a conversation is: the list of the conversations is one for every terminal and every directory, `--last` is the newest of all,
 * and the program writes down nothing of what it is in. What there is: the directory of the conversation, when it was made, and the command line, which it keeps
 * whole (`openhands --resume <id>`). So the conversation is, in order:
 * 1. the newest one of the directory that was made since the process started: it is the first one that was written, or what `/new` started after it;
 * 2. the one on its command line (`--resume <id>`, which is how KaTTY starts it again), when it has not started another since;
 * 3. when it was told to resume with no id (`--resume` alone, `--last`), the newest of the directory that was written to since.
 *
 * Otherwise none: it has not been told anything yet. Two OpenHands in one directory are not told apart, and a `/new` that has not been followed by a message is
 * not seen (the conversation that is made is not there yet): the program comes back on the one before.
 */
internal class OpenHandsStore(private val conversations: Path = defaultDirectory()) {
    /**
     * The conversation of the OpenHands that runs [argv] in [cwd] and started at [startedAtMs], in the form the program is given (`8-4-4-4-12`), or `null`.
     */
    fun sessionFor(cwd: String, startedAtMs: Long, argv: List<String>): String? {
        return try {
            if (!conversations.isDirectory()) return null
            val written = Files.list(conversations).use { folders ->
                folders.filter { it.isDirectory() && it.getLastModifiedTime().toMillis() >= startedAtMs - BEFORE_START_MS }.toList()
            }.filter { workingDirectory(it).let { dir -> dir != null && sameDirectory(dir, cwd) } }

            val made = written.mapNotNull { folder -> createdAt(folder)?.takeIf { it >= startedAtMs - BEFORE_START_MS }?.let { folder to it } }.maxByOrNull { it.second }?.first
            val named = resumeId(argv)?.takeIf { conversations.resolve(it).resolve("events").isDirectory() }
            val resumed = if (told(argv)) written.maxByOrNull { it.getLastModifiedTime().toMillis() }?.fileName?.toString() else null
            (made?.fileName?.toString() ?: named ?: resumed)?.let(::dashed)
        } catch (e: Exception) {
            logger.debug("Could not read {}", conversations, e)
            null
        }
    }

    // The word after `--resume` (or the value of `--resume=`), when it is an id and not another option.
    private fun resumeId(argv: List<String>): String? = resumeValue(argv)?.takeIf { ID.matches(it) }?.replace("-", "")?.lowercase()

    // It was told to resume a conversation and not which one: `--resume` alone, or with `--last`.
    private fun told(argv: List<String>): Boolean = resumeId(argv) == null && argv.drop(1).any { it == "--last" || it == "--resume" }

    // The first event is the system prompt, written when the conversation is made: the time of its file is the time of the conversation. `null` for a
    // conversation that has none yet (it is being made): it is not a reason to give up on the others.
    private fun createdAt(folder: Path): Long? =
        try {
            Files.newDirectoryStream(folder.resolve("events"), "event-00000-*.json").use { files -> files.firstOrNull()?.getLastModifiedTime()?.toMillis() }
        } catch (e: Exception) {
            null
        }

    // Kept once it was read: a state that could not be (it is being written) is read again the next time.
    private fun workingDirectory(folder: Path): String? {
        val key = "$conversations/${folder.fileName}"
        directories[key]?.let { return it }
        val read = try {
            WORKING_DIR.find(Files.readString(folder.resolve("base_state.json")))?.groupValues?.get(1)?.let { Json.decodeFromString<String>("\"$it\"") }
        } catch (e: Exception) {
            null
        }
        return read?.also { directories[key] = it }
    }


    private fun dashed(hex: String): String =
        if (hex.length == 32) "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}" else hex

    internal companion object {
        // The directory of a conversation does not change, and its state is a file of hundreds of kilobytes: it is read once.
        private val directories = ConcurrentHashMap<String, String>()

        fun defaultDirectory(): Path = Path.of(System.getProperty("user.home"), ".openhands", "conversations")
    }
}
