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
     * Only Claude Code is known: it writes `~/.claude/sessions/<pid>.json` with the `sessionId` that its conversation has now, which follows
     * `/clear` and `/resume`. The id is only given if its transcript exists (a conversation with no message has nothing to resume), and the
     * file is the one of **this** process: a file left by an older process with the same pid started before this one.
     * Not done, and why: Codex, Copilot and Antigravity are not installed where this was written: they start again with no conversation.
     */
    public fun sessionId(agent: Agent, pid: Int, startedAtMillis: Long, home: File = File(System.getProperty("user.home"))): String? =
        when (agent) {
            Agent.CLAUDE -> claudeSessionId(pid, startedAtMillis, home)
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
        if (startedAt != null && startedAtMillis > 0 && startedAt < startedAtMillis - STALE_MARGIN_MS) return null
        val projects = home.resolve(".claude/projects")
        // Its folder is named after the directory it ran in, every character that is not a letter or a digit a dash: one stat. A name that
        // was made another way is looked for in all of them.
        val directory = record.string("cwd")?.replace(Regex("[^A-Za-z0-9]"), "-")
        if (directory != null && projects.resolve("$directory/$id.jsonl").isFile) return id
        return id.takeIf { projects.listFiles { f -> f.isDirectory }?.any { it.resolve("$id.jsonl").isFile } == true }
    }

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
        val program = original.firstOrNull() ?: agent.executable
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
        else -> emptyMap()
    }

    private const val STALE_MARGIN_MS = 5_000L
}
