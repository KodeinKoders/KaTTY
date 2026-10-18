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
 * @param sessionId The id of the conversation that the agent [Agent] runs as the pid, which started at the time; by default [AgentResume.sessionId].
 */
public fun ForegroundCommand.toRunning(
    sessionId: (Agent, Int, Long) -> String? = { agent, pid, startedAt -> AgentResume.sessionId(agent, pid, startedAt) },
): RunningCommand? {
    if (pipeline || argv.isEmpty()) return null
    val agent = Agent.fromProcess(File(argv.first()).name)
    val tooLong = argv.sumOf { it.length + 1 } > MAX_COMMAND_CHARS
    val kept = when {
        !tooLong -> argv
        agent != null -> listOf(argv.first())
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
    return TypedCommand(line, run = policy.runs(line))
}
