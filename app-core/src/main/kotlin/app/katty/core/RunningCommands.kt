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
 * @param sessionId The id of the conversation that the agent [Agent] runs as the pid, which started at the time; by default [AgentResume.sessionId].
 */
public fun ForegroundCommand.toRunning(
    reportedSession: String? = null,
    sessionId: (Agent, Int, Long) -> String? = { agent, pid, startedAt ->
        AgentResume.sessionId(agent, pid, startedAt, cwd = directory, groupPids = groupPids, reportedSession = reportedSession, argv = argv)
    },
): RunningCommand? {
    if (pipeline || argv.isEmpty()) return null
    val agent = Agent.fromCommand(argv)
    val tooLong = argv.sumOf { it.length + 1 } > MAX_COMMAND_CHARS
    val line = when (agent) {
        Agent.OPENCODE -> listOf(openCodeProgram(argv.first(), executable)) + argv.drop(1)
        // The script is what is typed, not the interpreter that runs it (`Python`, from a path that a Homebrew update removes; `node`).
        Agent.AIDER, Agent.CLINE -> if (File(argv.first()).name == agent.executable) argv else argv.drop(1)
        else -> argv
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
    // An agent that was recognized runs whatever its file is called (`opencode-v2`): `RestorePolicy` judges by the name alone.
    return TypedCommand(line, run = agent != null || policy.runs(line))
}

/**
 * What starts the OpenCode that [executable] is: [name] as it was typed when the `PATH` gives that same file, else [executable] itself, so that
 * the 2.x build that was started by its own path (`opencode-v2`) is not resumed by the 1.x one that `opencode` is, which would not find its session.
 */
private fun openCodeProgram(name: String, executable: String?): String {
    if (executable == null) return name
    val onPath = if ('/' in name) File(name) else System.getenv("PATH").orEmpty().split(':').filter { it.isNotEmpty() }
        .map { File(it, name) }.firstOrNull { it.canExecute() && it.isFile }
    val same = onPath != null && try {
        onPath.canonicalPath == File(executable).canonicalPath
    } catch (_: java.io.IOException) {
        false
    }
    return if (same) name else executable
}
