package app.katty.core

import app.katty.terminal.ForegroundCommand
import app.katty.terminal.TypedCommand
import java.io.File

// A command line longer than this (a prompt given as an argument) is not kept: it would not be typed, and it is the user's text.
private const val MAX_COMMAND_CHARS = 2000

/**
 * What a restored terminal types to get this program back, or `null` when it cannot be started again: one program of a pipeline (the others
 * are not known), or a command line that is too long to be typed. An agent is kept by its program only, and with the id of its conversation
 * when [sessionId] can tell it: its options and its prompt are not what it comes back with (see [AgentResume.argv]).
 *
 * @param reportedSession The session that the program has said it is in (OSC 7501), when it says it: it is what an OpenCode is told apart by.
 * @param searchPath Where the shell looks for programs (as `PATH`): the login shell's when it was read ([AgentDetection.knownLoginPath]), which
 * the application's is not when it was opened from the Finder.
 * @param lookups What was found of the conversation before, for a terminal that is asked again and again: by default it is looked for each time.
 * @param sessionId The id of the conversation that the agent [Agent] runs as the pid, which started at the time; by default [AgentResume.sessionId],
 * through [lookups] when there are.
 */
public fun ForegroundCommand.toRunning(
    reportedSession: String? = null,
    searchPath: String = AgentDetection.knownLoginPath ?: System.getenv("PATH").orEmpty(),
    lookups: SessionLookups? = null,
    sessionId: (Agent, Int, Long) -> String? = { agent, pid, startedAt ->
        val find = { AgentResume.sessionId(agent, pid, startedAt, cwd = directory, groupPids = groupPids, reportedSession = reportedSession, tty = tty, argv = argv) }
        if (lookups != null) lookups.sessionOf(agent, pid, startedAt, reportedSession, find) else find()
    },
): RunningCommand? {
    if (pipeline || argv.isEmpty()) return null
    val agent = Agent.fromCommand(argv)
    val tooLong = argv.sumOf { it.length + 1 } > MAX_COMMAND_CHARS
    val line = when (agent) {
        Agent.OPENCODE -> listOf(openCodeProgram(argv.first(), executable, searchPath, directory)) + argv.drop(1)
        // The script is what is typed, not the interpreter that runs it (`Python`, from a path that a Homebrew update removes; `node`).
        // The launcher runs `node --use-system-ca …/index.js <what was typed>`: what is typed is the launcher and what was typed after it.
        Agent.CURSOR -> argv.indexOfFirst { it.endsWith("/index.js") }.let { script -> if (script < 0) argv else listOf(argv.first()) + argv.drop(script + 1) }
        else -> if (agent?.interpreted == true && File(argv.first()).name !in agent.names) argv.drop(1) else argv
    }
    val kept = when {
        !tooLong -> line
        agent != null -> listOf(line.first())
        else -> return null
    }
    return RunningCommand(kept, directory, agent?.id, agent?.let { sessionId(it, pid, startedAtMillis) })
}

/**
 * The line to type in the new shell for this program: the command line, or for an agent the one that resumes its conversation, which runs by
 * itself when [policy] says it is safe to and otherwise waits for the user to press Return.
 */
public fun RunningCommand.toTyped(policy: RestorePolicy = RestorePolicy()): TypedCommand {
    val agent = agent?.let(Agent::byId)
    val line = if (agent != null) AgentResume.argv(agent, argv, agentSession) else argv
    // An agent that was recognized runs whatever its file is called (`opencode-v2`): `RestorePolicy` judges by the name alone. Not one of
    // its other commands (`kilo uninstall`): those are typed for the user.
    return TypedCommand(line, run = if (agent != null) AgentResume.resumesInterface(agent, argv) else policy.runs(line))
}

/**
 * What starts the OpenCode that [executable] is: [name] as it was typed when the `PATH` gives that same file, else [executable] itself, so that
 * the 2.x build that was started by its own path (`opencode-v2`) is not resumed by the 1.x one that `opencode` is, which would not find its session.
 */
private fun openCodeProgram(name: String, executable: String?, searchPath: String, directory: String?): String {
    if (executable == null) return name
    // A name with a slash is a path, which the shell resolves from where the program was started: its directory.
    val onPath = if ('/' in name) File(name).let { if (it.isAbsolute || directory == null) it else File(directory, name) }
    else searchPath.split(':').filter { it.isNotEmpty() }.map { File(it, name) }.firstOrNull { it.canExecute() && it.isFile }
    val same = onPath != null && try {
        onPath.canonicalPath == File(executable).canonicalPath
    } catch (_: java.io.IOException) {
        false
    }
    return if (same) name else executable
}

/**
 * This command, with the conversation taken off when it was only guessed by the directory ([guessed]: the agent has no more than that to go by) and another
 * of [others], the programs of the other terminals, is the same agent in the same directory: the guess cannot say which of them it is, and the one that
 * comes back on the other's conversation is worse than the one that comes back on none. A command that was started on a conversation of its own
 * (restored with `--resume <id>`) is not told apart by this: the registry keeps that one.
 *
 * @param others What the other terminals run (`null` for one that runs nothing).
 */
public fun RunningCommand.apartFromOthers(others: Collection<RunningCommand?>, guessed: Boolean): RunningCommand {
    if (!guessed || agent == null || agentSession == null || directory == null) return this
    return if (others.any { it != null && it.agent == agent && it.directory == directory }) copy(agentSession = null) else this
}
