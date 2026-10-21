package app.katty.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.isDirectory

private val logger = LoggerFactory.getLogger("app.katty.core.CursorStore")

// The process starts a moment before the chat that it makes is written, and the times are the system's and the program's.
private const val BEFORE_START_MS = 2_000L

private val CHAT_ID = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

/**
 * What Cursor's agent keeps of its chats: a folder for each, `~/.cursor/chats/<MD5 of the directory it works in>/<id>/`, with `meta.json`
 * (`{"schemaVersion":1,"createdAtMs":…,"hasConversation":false,"title":"…","updatedAtMs":…,"isSubagent":true,"cwd":"…"}`), `prompt_history.json` and, once there is
 * something said, the chat's own `store.db`. The chat is **made when the program starts** (a second before, to the millisecond, with `hasConversation` false until
 * there is a first exchange), and `/new-chat` makes another at once. A sub-agent's chat has `isSubagent`. `--resume <id>` makes no other: it takes the chat
 * again, and for one that has no `store.db` (nothing was said) it writes `createdAtMs` anew.
 *
 * Nothing names the process, but a chat that is empty is no conversation, and that tells what to come back on: the newest chat of the directory that the
 * process made is the one it is in (`/new-chat` is seen the moment it is done), and when it is empty nothing is resumed, which is what is wanted: an empty chat,
 * not an older one. So, in order:
 * 1. the newest chat of the directory made since the process started: its id when it has a conversation, else none;
 * 2. the chat of the command line (`--resume <id>`, which is how KaTTY starts it again) when it has a conversation: a chat that was resumed keeps the time that
 *    it was made at, so it is not the newest made;
 * 3. when it was told to resume with no id (`--continue`, `--resume` alone, `resume`, `ls`), the newest chat of the directory with a conversation that was
 *    written to since.
 *
 * Two agents in one directory are not told apart (like the other agents that go by the directory). A Cursor started with `--workspace` has its chats under the
 * directory it was given, which is not looked at: it starts with none.
 */
internal class CursorStore(private val chats: Path = defaultDirectory()) {
    private class Chat(val id: String, val createdAtMs: Long, val updatedAtMs: Long, val hasConversation: Boolean)

    /** The id of the chat of the Cursor agent that runs [argv] in [cwd] and started at [startedAtMs], or `null`. */
    fun sessionFor(cwd: String, startedAtMs: Long, argv: List<String>): String? {
        return try {
            val folder = chats.resolve(md5(realPath(cwd)))
            if (!folder.isDirectory()) return null
            val all = Files.newDirectoryStream(folder).use { it.toList() }.mapNotNull(::read)

            val made = all.filter { it.createdAtMs >= startedAtMs - BEFORE_START_MS }.maxByOrNull { it.createdAtMs }
            if (made != null) return made.id.takeIf { made.hasConversation }

            val named = resumeId(argv)?.let { id -> all.firstOrNull { it.id.equals(id, ignoreCase = true) } }
            if (named != null) return named.id.takeIf { named.hasConversation }

            if (told(argv)) all.filter { it.hasConversation && it.updatedAtMs >= startedAtMs - BEFORE_START_MS }.maxByOrNull { it.updatedAtMs }?.id else null
        } catch (e: Exception) {
            logger.debug("Could not read {}", chats, e)
            null
        }
    }

    private fun read(folder: Path): Chat? =
        try {
            val meta = Json.parseToJsonElement(Files.readString(folder.resolve("meta.json"))).jsonObject
            val created = meta["createdAtMs"]?.jsonPrimitive?.longOrNull
            if (created == null || meta["isSubagent"]?.jsonPrimitive?.booleanOrNull == true) {
                null
            } else {
                Chat(folder.fileName.toString(), created, meta["updatedAtMs"]?.jsonPrimitive?.longOrNull ?: created, meta["hasConversation"]?.jsonPrimitive?.booleanOrNull == true)
            }
        } catch (e: Exception) {
            null
        }

    // The word after `--resume` (or the value of `--resume=`), when it is a chat's id and not another option.
    private fun resumeId(argv: List<String>): String? {
        val words = argv.drop(1)
        val value = words.firstOrNull { it.startsWith("--resume=") }?.substringAfter('=')
            ?: words.indexOf("--resume").takeIf { it >= 0 }?.let { words.getOrNull(it + 1) }
        return value?.takeIf { CHAT_ID.matches(it) }
    }

    // It was told to resume a chat and not which one.
    private fun told(argv: List<String>): Boolean {
        val words = argv.drop(1)
        return resumeId(argv) == null && words.any { it == "--continue" || it == "--resume" || it == "resume" || it == "ls" }
    }

    // The folder of the chats is named by the MD5 of the real path of the directory.
    private fun realPath(cwd: String): String =
        try {
            Path.of(cwd).toRealPath().toString()
        } catch (e: Exception) {
            cwd
        }

    private fun md5(text: String): String = MessageDigest.getInstance("MD5").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    internal companion object {
        fun defaultDirectory(): Path = Path.of(System.getProperty("user.home"), ".cursor", "chats")
    }
}
