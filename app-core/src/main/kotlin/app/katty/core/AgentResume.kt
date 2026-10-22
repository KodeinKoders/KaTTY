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
     * - OpenCode keeps its conversations in a database, not in a file named after the process. What names the terminal's own is what it reports
     * (OSC 7501, from 2.1.0): it reports a record for each session that works or waits, with the session's id as the record's id, which the
     * terminal remembers ([reportedSession], checked and taken to its root by [openCodeRoot], which is [OpenCodeStore.rootOf] by default). A
     * session that has never worked has no such record, and is guessed: [cwd] and [startedAtMillis] pick the newest one of that directory that
     * the process created or updated ([openCodeSession], which is [OpenCodeStore.sessionFor] by default).
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
     * - Cline keeps its sessions in a database, and its sessions run in a daemon that the terminals share, so the `pid` of its rows says nothing of
     * the process. What does is the hub's record of its clients: the one that registered with a pid of the process's group ([groupPids]: the native
     * program is a child of the script that has the terminal) is the one whose last session is the terminal's ([clineSession], which is
     * [ClineStore.sessionFor] by default), and `--id` resumes it. A Cline that is not in the hub is looked for by [cwd] and [startedAtMillis].
     *
     * - Kilo Code is OpenCode's CLI with its own folder (`~/.local/share/kilo/kilo.db`): the process has its database and a log of its own open
     * ([openFiles] of [groupPids]), and the log names the session when one is made, which is the terminal's own whichever directory it is in
     * ([kiloSession], which is [KiloStore.sessionFor] by default). A Kilo whose log says nothing is guessed by [cwd] and [startedAtMillis].
     *
     * Not resumed, on purpose: Codex and Pi. A Codex has the thread that it is in nowhere that tells it (a daemon that the terminals share holds
     * them, and logs the connection that started one, not the process behind it), and a Pi renames its process and empties its command line. What
     * could be found is the conversation that they started with (the thread or the file made when the process started, and `codex resume <id>`
     * resumes the same one), and the one they are in after `/new` is not it: they came back on an older conversation, which is worse than a new one.
     * They start again with no conversation.
     *
     * - omp (oh-my-pi, Pi's fork) writes a file for each terminal with the session that it is in ([tty]: `~/.omp/agent/terminal-sessions/ttys018`), which follows
     * `/new` and `/resume` and says `fresh` for a session with no conversation ([ompSession], which is [OmpStore.sessionFor] by default). Both are no session:
     * nothing is resumed, and the registry does not put an older one back. It keeps its command line ([argv]).
     *
     * - OpenHands keeps a folder for each conversation (`~/.openhands/conversations/<id>`, with the directory it works in) and nothing of which process one
     * is: the newest of the directory that was made since the process started, else the id on its command line ([argv]: `--resume <id>`, which is how it is
     * started again), else for a `--resume` with no id the newest that was written to since ([openHandsSession], which is [OpenHandsStore.sessionFor]).
     *
     * - Cursor's agent makes its chat when it starts and `/new-chat` makes another at once (`~/.cursor/chats/<MD5 of the directory>/<id>/meta.json`, with
     * `hasConversation`), and an empty chat is no conversation: the newest chat of the directory that the process made, when it has a conversation; else the one of
     * its command line (`--resume <id>`); else for `--continue` the newest written to since ([cursorSession], which is [CursorStore.sessionFor] by default). `null` is
     * an empty chat, and the registry does not put an older one back.
     *
     * Whether the files of the others follow a conversation that is changed from inside the agent was not checked.
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
        clineSession: (String, Long, List<Int>) -> String? = { directory, startedAt, pids -> ClineStore().sessionFor(directory, startedAt, pids) },
        groupPids: List<Int> = emptyList(),
        reportedSession: String? = null,
        openCodeRoot: (String) -> String? = { OpenCodeStore().rootOf(it) },
        kiloSession: (String, Long, List<String>) -> String? = { directory, startedAt, paths -> KiloStore().sessionFor(directory, startedAt, paths) },
        tty: String? = null,
        argv: List<String> = emptyList(),
        ompSession: (String, Long, String?, List<String>) -> String? = { directory, startedAt, terminal, arguments -> OmpStore().sessionFor(directory, startedAt, terminal, arguments) },
        openHandsSession: (String, Long, List<String>) -> String? = { directory, startedAt, arguments -> OpenHandsStore().sessionFor(directory, startedAt, arguments) },
        cursorSession: (String, Long, List<String>) -> String? = { directory, startedAt, arguments -> CursorStore().sessionFor(directory, startedAt, arguments) },
    ): String? =
        when (agent) {
            Agent.ANTIGRAVITY -> antigravitySessionId(pid, home, openFiles)
            Agent.CLAUDE -> claudeSessionId(pid, startedAtMillis, home)
            Agent.JUNIE -> junieSessionId(pid, startedAtMillis, home)
            Agent.COPILOT -> copilotSessionId(pid, startedAtMillis, home)
            // What the program said of itself (the session it reported working or waiting) is the terminal's own; its directory and times are a guess.
            Agent.OPENCODE -> (reportedSession?.let(openCodeRoot) ?: cwd?.let { openCodeSession(it, startedAtMillis) })?.takeIf(::isOpenCodeId)
            Agent.CRUSH -> cwd?.let { crushSession(it, startedAtMillis) }?.takeIf(::isSessionId)
            Agent.GOOSE -> cwd?.let { gooseSession(it, startedAtMillis) }?.takeIf(::isUnderscoreId)
            Agent.CLINE -> cwd?.let { clineSession(it, startedAtMillis, groupPids) }?.takeIf(::isUnderscoreId)
            // OpenHands keeps nothing of which process a conversation is: its directory, when it was made and its own command line are what there is.
            Agent.OPENHANDS -> cwd?.let { openHandsSession(it, startedAtMillis, argv) }?.takeIf(::isSessionId)
            // Cursor's chat is made when the program starts, and an empty one is no conversation: `null` is an empty chat, and is not a reason to guess.
            Agent.CURSOR -> cwd?.let { cursorSession(it, startedAtMillis, argv) }?.takeIf(::isSessionId)
            // Aider has no conversation to name: it loads the chat of the directory. What stands for it is a word that says it is to be loaded, so that it can be taken off.
            Agent.AIDER -> AIDER_HISTORY
            // omp keeps what session each terminal is in, and says `fresh` for one with no conversation: that is `null`, and it is not a reason to guess.
            Agent.OMP -> cwd?.let { ompSession(it, startedAtMillis, tty, argv) }?.takeIf(::isSessionId)
            // Its database and its log are what the process has open: the script that has the terminal and the native program that it started.
            Agent.KILO -> cwd?.let { kiloSession(it, startedAtMillis, groupPids.ifEmpty { listOf(pid) }.flatMap(openFiles)) }?.takeIf(::isOpenCodeId)
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

    // What Goose and Cline make their ids of (`20261021_5`, `1792591989752_0vnpk`): it goes into a command line.
    private fun isUnderscoreId(id: String): Boolean = id.length in 1..64 && id.all { it.isLetterOrDigit() || it == '_' || it == '-' }

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
        otherCommand(agent, original)?.let { return listOf(program, it) }
        // Goose's options belong to the `session` command, which is what a bare `goose` runs as well.
        if (agent == Agent.GOOSE) kept += "session"
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
        if (agent == Agent.AIDER && sessionId != null) kept += "--restore-chat-history"
        if (sessionId != null) {
            when (agent) {
                Agent.CLAUDE -> kept += listOf("--resume", sessionId)
                Agent.JUNIE -> kept += listOf("--resume", "--session-id=$sessionId")
                Agent.COPILOT -> kept += listOf("--resume", sessionId)
                Agent.ANTIGRAVITY -> kept += listOf("--conversation", sessionId)
                Agent.OPENCODE -> kept += listOf("-s", sessionId)
                Agent.CRUSH -> kept += listOf("--session", sessionId)
                Agent.GOOSE -> kept += listOf("--resume", "--session-id", sessionId)
                Agent.CLINE -> kept += listOf("--id", sessionId)
                Agent.KILO -> kept += listOf("--session", sessionId)
                // Its option takes its value with an `=`: without it, `--resume` opens the picker, and the id would be taken for a message.
                Agent.OMP -> kept += "--resume=$sessionId"
                Agent.OPENHANDS -> kept += listOf("--resume", sessionId)
                Agent.CURSOR -> kept += listOf("--resume", sessionId)
                else -> Unit
            }
        }
        return kept
    }

    /**
     * Whether [original], a command line of [agent], is its interface, which comes back on its conversation: not one of its other commands
     * (`kilo uninstall`, `cursor-agent logout`, `goose update`), which are typed again for the user to run or not, since what they do is not
     * a conversation to come back to.
     */
    public fun resumesInterface(agent: Agent, original: List<String>): Boolean = otherCommand(agent, original) == null

    // The command of [original] that is not the agent's interface, which it starts again as that word alone, or null for its interface.
    private fun otherCommand(agent: Agent, original: List<String>): String? = when (agent) {
        // `openhands serve`, `web`, `mcp`… are not a conversation to come back to.
        Agent.OPENHANDS -> original.drop(1).firstOrNull { !it.startsWith("-") }?.takeIf { it in OPENHANDS_COMMANDS }
        // Its options are the interface's. Another command (`kilo run`, `kilo serve`) is not a conversation to come back to, and the project that
        // `kilo [project]` is given is where the process already is.
        Agent.KILO -> firstCommand(original, agent)?.takeIf { it in KILO_COMMANDS }
        // `login`, `models`, `update`… are not a conversation. `agent`, `resume` and `ls` start or resume the interface: what it was in replaces them.
        Agent.CURSOR -> firstCommand(original, agent)?.takeIf { it in CURSOR_COMMANDS }
        // A bare `goose` runs the `session` command. Another command (`goose run`, `goose acp`) is not a conversation to come back to.
        Agent.GOOSE -> original.drop(1).firstOrNull { !it.startsWith("-") }?.takeIf { it != "session" && it != "s" }
        else -> null
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
        // `-p` is the plan mode here and `-k` the API key, which is not kept. `--thinking` may have no value, and then the next word cannot be
        // told from a prompt. `--worktree` would make another one, and `--zen` runs the session in the background.
        // `--api-key` and the headers could hold a secret, `--print` and its formats are not the interface, `--worktree` would make another one, and `--resume` and
        // `--continue` are replaced by what it was in.
        Agent.CURSOR -> mapOf(
            "--model" to true,
            "--mode" to true,
            "--sandbox" to true,
            "--workspace" to true,
            "--add-dir" to true,
            "--plugin-dir" to true,
            "--endpoint" to true,
            "-e" to true,
            "--plan" to false,
            "--force" to false,
            "-f" to false,
            "--yolo" to false,
            "--auto-review" to false,
            "--approve-mcps" to false,
            "--trust" to false,
        )
        // `--prompt` would be sent twice, `--worktree` would make another one, and `--fork` would make another conversation of the one it comes back on.
        Agent.KILO -> mapOf(
            "--auto" to false,
            "--mini" to false,
            "--pure" to false,
            "--model" to true,
            "-m" to true,
            "--agent" to true,
            "--port" to true,
            "--hostname" to true,
        )
        // `--api-key` and the system prompts would be in the command line; `--goal`, `--from-claude`, `--from-codex`, `--print` and `--mode` are not how it
        // started the conversation that it comes back on; `--no-session` would not save it; `--restore` and `--continue` are replaced by what it was in.
        Agent.OMP -> mapOf(
            "--model" to true,
            "--smol" to true,
            "--slow" to true,
            "--plan" to true,
            "--provider" to true,
            "--profile" to true,
            "--cwd" to true,
            "--thinking" to true,
            "--service-tier" to true,
            "--tools" to true,
            "--skills" to true,
            "--models" to true,
            "--add-dir" to true,
            "--session-dir" to true,
            "--config" to true,
            "--prewalk-into" to true,
            "--plan-yolo-into" to true,
            "--prewalk" to false,
            "--no-prewalk" to false,
            "--plan-yolo" to false,
            "--allow-home" to false,
            "--no-tools" to false,
            "--no-lsp" to false,
            "--no-pty" to false,
            "--hide-thinking" to false,
            "--advisor" to false,
            "--no-extensions" to false,
            "--no-skills" to false,
            "--no-rules" to false,
            "--no-title" to false,
        )
        // `--task` and `--file` would seed the conversation again, `--headless` and `--json` are not the interface, and `--resume` and `--last` are replaced by what it was in.
        Agent.OPENHANDS -> mapOf(
            "--always-approve" to false,
            "--llm-approve" to false,
            "--exit-without-confirmation" to false,
            "--override-with-envs" to false,
        )
        Agent.CLINE -> mapOf(
            "--plan" to false,
            "-p" to false,
            "--yolo" to false,
            "-y" to false,
            "--tui" to false,
            "-i" to false,
            "--auto-approve" to true,
            "--compaction" to true,
            "--provider" to true,
            "-P" to true,
            "--model" to true,
            "-m" to true,
            "--cwd" to true,
            "-c" to true,
            "--retries" to true,
            "--timeout" to true,
            "-t" to true,
            "--config" to true,
            "--data-dir" to true,
            "--hooks-dir" to true,
        )
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

    // The first word that is not an option nor the value of one: the command, when it is one.
    private fun firstCommand(original: List<String>, agent: Agent): String? {
        val takesValue = knownOptions(agent).filterValues { it }.keys
        var skip = false
        for (word in original.drop(1)) {
            when {
                skip -> skip = false
                word.startsWith("-") -> skip = word.substringBefore('=') in takesValue && '=' !in word
                else -> return word
            }
        }
        return null
    }

    // What `cursor-agent --help` lists as commands, but the ones that start or resume the interface.
    private val CURSOR_COMMANDS = setOf(
        "persist", "install-shell-integration", "uninstall-shell-integration", "login", "logout", "mcp", "plugin", "worker", "status", "whoami", "models", "team",
        "bedrock", "about", "update", "create-chat", "generate-rule", "rule", "help",
    )

    // What `kilo --help` lists as commands: the first word of a command line that is one of them is not a project to start in.
    private val KILO_COMMANDS = setOf(
        "completion", "acp", "mcp", "attach", "run", "debug", "auth", "providers", "agent", "upgrade", "uninstall", "serve", "models", "stats", "export",
        "import", "github", "pr", "session", "plugin", "plug", "db", "console", "cloud", "roll-call", "profile", "auto", "remote", "daemon", "config",
        "worktree", "help",
    )

    // What `openhands --help` lists as commands.
    private val OPENHANDS_COMMANDS = setOf("acp", "serve", "web", "mcp", "cloud", "login", "logout", "view")

    /** What [sessionId] says for Aider: the chat of the directory is to be loaded when it starts again. */
    internal const val AIDER_HISTORY = "history"

    private const val STALE_MARGIN_MS = 5_000L
}
