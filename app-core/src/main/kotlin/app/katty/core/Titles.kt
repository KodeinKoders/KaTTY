package app.katty.core

/** What a terminal is called, and what its tile shows: decided from what is known of it. */
public data class TerminalIdentity(val title: String, val agent: Agent?)

/**
 * What a terminal is called, in this order:
 *
 * . the name the user gave it;
 * . the agent that reports in it (OSC 7501): its name, or what the program calls itself when it is not a known agent;
 * . the agent that is running in it, known by its program: its name;
 * . the program that is running in it: `emacs`, until the user quits it;
 * . the shell, which is what is left at the prompt.
 *
 * @param name The name that the user gave, `null` for none.
 * @param process The name of the program that has the terminal, `null` when it is not known.
 * @param shell The name of the shell (`zsh`).
 */
public fun terminalIdentity(name: String?, report: AgentReport?, process: String?, shell: String): TerminalIdentity {
    val reported = report?.takeIf { it.reporting }
    val agent = reported?.agent ?: Agent.fromProcess(process)
    val title = name?.takeIf { it.isNotBlank() }
        ?: reported?.let { it.agent?.displayName ?: it.app }
        ?: agent?.displayName
        ?: process?.takeIf { it.isNotBlank() }
        ?: shell
    return TerminalIdentity(title, agent)
}
