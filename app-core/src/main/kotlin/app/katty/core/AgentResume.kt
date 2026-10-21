package app.katty.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import app.katty.terminal.openFilePaths
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
     * - Antigravity writes no file for a process, but keeps `~/.gemini/antigravity-cli/presence/<conversationId>.lock` open while it has a
     * conversation (it opens it with the first message): the id is read from the files that the process has open ([openFiles]).
     *
     * - OpenCode keeps its conversations in a database, not in a file named after the process: [cwd] and [startedAtMillis] pick the newest one
     * of that directory that the process created or updated ([openCodeSession], which is [OpenCodeStore.sessionFor] by default).
     *
     * - Crush keeps its conversations in a database for each project, like OpenCode: [cwd] and [startedAtMillis] pick the newest one of that
     * project that the process created or updated ([crushSession], which is [CrushStore.sessionFor] by default).
     *
     * - Aider has no conversation id: it keeps the chat of a directory in `.aider.chat.history.md` there and loads it with `--restore-chat-history`
     * (0.35.0 and later), which is what it is started with. Two Aiders in one directory write the same file, which Aider does not support: both
     * come back with the two chats mixed.
     *
     * - Goose keeps its conversations in one database, like OpenCode: [cwd] and [startedAtMillis] pick the newest one of that directory that
     * the process created or updated ([gooseSession], which is [GooseStore.sessionFor] by default), and it is started again with
     * `session --resume --session-id`.
     *
     * Not done, and why: Codex has no file that ties a process to its conversation (a server that the terminals share holds them), and
     * neither the id of what it runs: it starts again with no conversation. Whether the files of the others follow a conversation that is
     * changed from inside the agent was not checked.
     *
     * @param openFiles The paths of the files that the process has open.
     * @param cwd The process's own directory.
     */
    public fun sessionId(
        agent: Agent,
        pid: Int,
        startedAtMillis: Long,
        home: File = File(System.getProperty("user.home")),
        openFiles: (Int) -> List<String> = ::openFilePaths,
        cwd: String? = null,
        openCodeSession: (String, Long) -> String? = { directory, startedAt -> OpenCodeStore().sessionFor(directory, startedAt) },
        crushSession: (String, Long) -> String? = { directory, startedAt -> CrushStore().sessionFor(directory, startedAt) },
        gooseSession: (String, Long) -> String? = { directory, startedAt -> GooseStore().sessionFor(directory, startedAt) },
    ): String? =
        when (agent) {
            Agent.ANTIGRAVITY -> antigravitySessionId(pid, home, openFiles)
            Agent.CLAUDE -> claudeSessionId(pid, startedAtMillis, home)
            Agent.JUNIE -> junieSessionId(pid, startedAtMillis, home)
            Agent.COPILOT -> copilotSessionId(pid, startedAtMillis, home)
            Agent.OPENCODE -> cwd?.let { openCodeSession(it, startedAtMillis) }?.takeIf(::isOpenCodeId)
            Agent.CRUSH -> cwd?.let { crushSession(it, startedAtMillis) }?.takeIf(::isSessionId)
            Agent.GOOSE -> cwd?.let { gooseSession(it, startedAtMillis) }?.takeIf(::isGooseId)
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

    private fun antigravitySessionId(pid: Int, home: File, openFiles: (Int) -> List<String>): String? {
        val root = home.resolve(".gemini/antigravity-cli")
        val presence = root.resolve("presence").path + File.separator
        // The files may be given by a path that goes through a link (`/var` is `/private/var`): the name is what counts.
        val ids = openFiles(pid)
            .filter { it.startsWith(presence) || it.contains("/.gemini/antigravity-cli/presence/") }
            .map { File(it).name }
            .filter { it.endsWith(".lock") }
            .map { it.removeSuffix(".lock") }
            .filter(::isSessionId)
            .distinct()
        // More than one (the process is in another's conversation too) cannot be told apart: a new conversation is better than another's.
        val id = ids.singleOrNull() ?: return null
        return id.takeIf { root.resolve("conversations/$id.db").isFile }
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

    // What Goose makes its ids of (`20261021_5`): it goes into a command line.
    private fun isGooseId(id: String): Boolean = id.length in 1..64 && id.all { it.isLetterOrDigit() || it == '_' || it == '-' }

    // `ses_` and what OpenCode makes the rest of an id of: it goes into a command line.
    private fun isOpenCodeId(id: String): Boolean = Regex("ses_[A-Za-z0-9]+").matches(id)

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
        if (agent == Agent.GOOSE) {
            // Its options belong to the `session` command, which is what a bare `goose` runs as well. Another command (`goose run`,
            // `goose acp`) is not a conversation to come back to.
            val command = original.drop(1).firstOrNull { !it.startsWith("-") }
            if (command != null && command != "session" && command != "s") return listOf(program, command)
            kept += "session"
        }
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
        // Aider has no ids: it keeps the chat of a directory in a file there and loads it when it is asked to.
        if (agent == Agent.AIDER) kept += "--restore-chat-history"
        if (sessionId != null) {
            when (agent) {
                Agent.CLAUDE -> kept += listOf("--resume", sessionId)
                Agent.JUNIE -> kept += listOf("--resume", "--session-id=$sessionId")
                Agent.COPILOT -> kept += listOf("--resume", sessionId)
                Agent.ANTIGRAVITY -> kept += listOf("--conversation", sessionId)
                Agent.OPENCODE -> kept += listOf("-s", sessionId)
                Agent.CRUSH -> kept += listOf("--session", sessionId)
                Agent.GOOSE -> kept += listOf("--resume", "--session-id", sessionId)
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
        // `-c key=value` is left out (a configuration override can hold a secret), and so are `--worktree` (it would make another one) and `-i`
        // (it takes several files).
        Agent.CODEX -> mapOf(
            "--dangerously-bypass-approvals-and-sandbox" to false,
            "--approve-for-me" to false,
            "--oss" to false,
            "--search" to false,
            "--no-alt-screen" to false,
            "--strict-config" to false,
            "--model" to true,
            "-m" to true,
            "--sandbox" to true,
            "-s" to true,
            "--ask-for-approval" to true,
            "-a" to true,
            "--profile" to true,
            "-p" to true,
            "--local-provider" to true,
            "--add-dir" to true,
            "--enable" to true,
            "--disable" to true,
        )
        Agent.JUNIE -> mapOf(
            "--brave" to false,
            "--model" to true,
            "--effort" to true,
            "--provider" to true,
        )
        Agent.ANTIGRAVITY -> mapOf(
            "--dangerously-skip-permissions" to false,
            "--sandbox" to false,
            "--model" to true,
            "--effort" to true,
            "--mode" to true,
            "--agent" to true,
            "--project" to true,
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
        Agent.OPENCODE -> mapOf("-m" to true, "--model" to true, "--agent" to true, "--port" to true, "--hostname" to true)
        Agent.CRUSH -> mapOf("--yolo" to false, "-y" to false, "--debug" to false, "-d" to false, "--cwd" to true, "-c" to true, "--data-dir" to true, "-D" to true)
        Agent.GOOSE -> mapOf(
            "--debug" to false,
            "--no-profile" to false,
            "--model" to true,
            "--provider" to true,
            "--max-turns" to true,
            "--max-tool-repetitions" to true,
            "--with-builtin" to true,
            "--container" to true,
        )
        Agent.AIDER -> mapOf(
            "--architect" to false,
            "--yes-always" to false,
            "--no-git" to false,
            "--no-auto-commits" to false,
            "--watch-files" to false,
            "--vim" to false,
            "--dark-mode" to false,
            "--light-mode" to false,
            "--model" to true,
            "--editor-model" to true,
            "--weak-model" to true,
            "--edit-format" to true,
            "--reasoning-effort" to true,
            // Where the chat is kept: the history that comes back is the one of these files.
            "--chat-history-file" to true,
            "--input-history-file" to true,
        )
        else -> emptyMap()
    }

    private const val STALE_MARGIN_MS = 5_000L
}
