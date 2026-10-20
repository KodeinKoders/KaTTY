package app.katty.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File

/**
 * How a coding agent is started again on the conversation it had: which one it was, read from the files that the agent keeps (nothing is
 * asked of it, and no credential is read), and the command line that resumes it.
 */
public object AgentResume {
    /**
     * The id of the conversation that the agent [agent] is in, which runs as [pid] since [startedAtMillis], or `null` when it cannot be told
     * or the agent has no way to resume by id. [home] is the user's home.
     *
     * Each agent says it in a file of its own, none of them documented, so each can stop working with a new version (there is then no id,
     * and the agent starts a new conversation). The id is only given when the conversation has a message (one with none has nothing to
     * resume), and when the file is the one of **this** process: a process that is killed leaves its file, and a later one can have its pid.
     *
     * - Claude Code writes `~/.claude/sessions/<pid>.json` with the `sessionId` that its conversation has now, which follows `/clear` and
     * `/resume`.
     * - Junie writes `~/.junie/processes/<pid>-<sessionId>-<hash>.json` while it runs, with `pid`, `sessionId` and `startedAt`.
     * - Copilot CLI makes `~/.copilot/session-state/<sessionId>/inuse.<pid>.lock` while it runs.
     *
     * Not done, and why: Codex is not installed where this was written, and Antigravity says which conversation a process has in no file
     * (only in the ones that it keeps open): they start again with no conversation. Whether Junie's and Copilot's files follow a conversation
     * that is changed from inside the agent was not checked.
     */
    public fun sessionId(agent: Agent, pid: Int, startedAtMillis: Long, home: File = File(System.getProperty("user.home"))): String? =
        when (agent) {
            Agent.CLAUDE -> claudeSessionId(pid, startedAtMillis, home)
            Agent.JUNIE -> junieSessionId(pid, startedAtMillis, home)
            Agent.COPILOT -> copilotSessionId(pid, startedAtMillis, home)
            else -> null
        }

    private fun claudeSessionId(pid: Int, startedAtMillis: Long, home: File): String? {
        val file = home.resolve(".claude/sessions/$pid.json")
        val record = try {
            Json.parseToJsonElement(file.readText()).jsonObject
        } catch (_: Exception) {
            return null
        }
        val id = record.string("sessionId")?.takeIf(::isSessionId) ?: return null
        // A file of an older process that had this pid: this one writes its own at start.
        val startedAt = (record["startedAt"] as? kotlinx.serialization.json.JsonPrimitive)?.longOrNull
        if (startedAt != null && isOlder(startedAt, startedAtMillis)) return null
        val projects = home.resolve(".claude/projects")
        // Its folder is named after the directory it ran in, every character that is not a letter or a digit a dash: one stat. A name that
        // was made another way is looked for in all of them.
        val directory = record.string("cwd")?.replace(Regex("[^A-Za-z0-9]"), "-")
        if (directory != null && projects.resolve("$directory/$id.jsonl").isFile) return id
        return id.takeIf { projects.listFiles { f -> f.isDirectory }?.any { it.resolve("$id.jsonl").isFile } == true }
    }

    private fun junieSessionId(pid: Int, startedAtMillis: Long, home: File): String? {
        val sessions = home.resolve(".junie/sessions")
        return home.resolve(".junie/processes").listFiles { f -> f.isFile && f.name.startsWith("$pid-") && f.name.endsWith(".json") }.orEmpty()
            .mapNotNull { file ->
                val record = try {
                    Json.parseToJsonElement(file.readText()).jsonObject
                } catch (_: Exception) {
                    return@mapNotNull null
                }
                val startedAt = (record["startedAt"] as? kotlinx.serialization.json.JsonPrimitive)?.longOrNull
                if (startedAt != null && isOlder(startedAt, startedAtMillis)) return@mapNotNull null
                record.string("sessionId")?.takeIf(::isSessionId)?.let { it to (startedAt ?: 0L) }
            }
            // A conversation with a message has its events.
            .filter { (id, _) -> sessions.resolve("$id/events.jsonl").isFile }
            .maxByOrNull { it.second }?.first
    }

    private fun copilotSessionId(pid: Int, startedAtMillis: Long, home: File): String? =
        home.resolve(".copilot/session-state").listFiles { f -> f.isDirectory }.orEmpty()
            .mapNotNull { directory ->
                val lock = directory.resolve("inuse.$pid.lock")
                // The lock is made when the process takes the conversation: one older than the process is another's that had the pid.
                if (!lock.isFile || isOlder(lock.lastModified(), startedAtMillis)) return@mapNotNull null
                // A conversation with a message has its events.
                directory.takeIf { isSessionId(it.name) && it.resolve("events.jsonl").isFile }?.let { it.name to lock.lastModified() }
            }
            .maxByOrNull { it.second }?.first

    // Whether what was written at [writtenAt] comes from before the process that started at [startedAtMillis] (unknown when it is 0).
    private fun isOlder(writtenAt: Long, startedAtMillis: Long): Boolean = startedAtMillis > 0 && writtenAt < startedAtMillis - STALE_MARGIN_MS

    private fun JsonObject.string(key: String): String? = (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.jsonPrimitive?.content

    // The id goes into a command line: only what an id is made of.
    private fun isSessionId(id: String): Boolean = id.length in 8..64 && id.all { it.isLetterOrDigit() || it == '-' }

    /**
     * The command line that starts [agent] again, from the one it had ([original]): the program, a few of its options that say how it was
     * run, and the conversation when there is [sessionId].
     *
     * The rest is left out on purpose: a prompt that was given on the command line must not be sent twice, an option that takes a variable
     * number of values (`--add-dir a b`) cannot be told from the words after it, and an unknown option followed by a word cannot be told from
     * one that takes it. What is kept is a list of options that are known, and the next word of the ones that take one.
     */
    public fun argv(agent: Agent, original: List<String>, sessionId: String?): List<String> {
        // Junie's command is a script that starts a binary of its version (`~/.local/share/junie/versions/3659.7/…/junie`): that is the
        // program that runs, and it is gone after an update, and started without the script it misses what the script sets.
        val program = original.firstOrNull()?.takeIf { agent != Agent.JUNIE } ?: agent.executable
        val kept = ArrayList<String>()
        kept += program
        val known = knownOptions(agent)
        var index = 1
        while (index < original.size) {
            val word = original[index]
            val name = word.substringBefore('=')
            val takesValue = known[name]
            when {
                takesValue == null -> index++
                takesValue && '=' in word -> { kept += word; index++ }
                takesValue -> {
                    val value = original.getOrNull(index + 1)
                    // No value, or the next option: what is left of it is not a value, and is looked at by itself.
                    if (value != null && !value.startsWith("-")) {
                        kept += word
                        kept += value
                        index += 2
                    } else {
                        index++
                    }
                }
                else -> { kept += word; index++ }
            }
        }
        if (sessionId != null) {
            when (agent) {
                Agent.CLAUDE -> kept += listOf("--resume", sessionId)
                Agent.JUNIE -> kept += listOf("--resume", "--session-id=$sessionId")
                Agent.COPILOT -> kept += listOf("--resume", sessionId)
                else -> Unit
            }
        }
        return kept
    }

    // The options that are kept, and whether each takes the next word as its value.
    private fun knownOptions(agent: Agent): Map<String, Boolean> = when (agent) {
        Agent.CLAUDE -> mapOf(
            "--dangerously-skip-permissions" to false,
            "--allow-dangerously-skip-permissions" to false,
            "--chrome" to false,
            "--no-chrome" to false,
            "--ide" to false,
            "--verbose" to false,
            "--model" to true,
            "--permission-mode" to true,
            "--effort" to true,
        )
        Agent.JUNIE -> mapOf(
            "--brave" to false,
            "--model" to true,
            "--effort" to true,
            "--provider" to true,
        )
        Agent.COPILOT -> mapOf(
            "--allow-all" to false,
            "--yolo" to false,
            "--allow-all-tools" to false,
            "--allow-all-paths" to false,
            "--allow-all-urls" to false,
            "--experimental" to false,
            "--model" to true,
            "--reasoning-effort" to true,
            "--agent" to true,
        )
        else -> emptyMap()
    }

    private const val STALE_MARGIN_MS = 5_000L
}
