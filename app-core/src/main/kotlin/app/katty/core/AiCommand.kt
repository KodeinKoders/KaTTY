package app.katty.core

import java.nio.file.Path

/**
 * What the run terminal asks the AI for, when its field is empty: the command that runs the project, alone on one line.
 */
public const val RUN_COMMAND_PROMPT: String =
    "Give me the command to run this project with its debug options. Your output will be used as-is in a terminal. " +
        "Do not print anything else but the command on one single line. Do not decorate it."

/**
 * The command line that runs a prompt with this agent without its interface, and prints the answer alone: `$PROMPT` is the prompt (see
 * [AiPrompt.ask]). No model is named: the agent's own default is used, and the user adds one in the setting. `null` for an agent that has no such
 * mode that prints the answer alone (Aider prints its banner and its costs around it, Cline's headless mode approves every action, Junie runs a whole
 * task).
 *
 * Only Claude Code's was run (2.1.x); the others are from each agent's documentation.
 */
public val Agent.defaultAiCommand: String?
    get() = when (this) {
        Agent.CLAUDE -> "claude -p \"\$PROMPT\""
        // The final message alone is on its standard output, the progress on its error; it refuses a folder that is not a git repository without the flag.
        Agent.CODEX -> "codex exec --skip-git-repo-check \"\$PROMPT\""
        Agent.ANTIGRAVITY -> "agy -p \"\$PROMPT\""
        // `-s`: the answer alone, without the statistics.
        Agent.COPILOT -> "copilot -p \"\$PROMPT\" -s"
        Agent.JUNIE -> null
        Agent.OPENCODE -> "opencode run \"\$PROMPT\""
        // `-q`: no spinner.
        Agent.CRUSH -> "crush run -q \"\$PROMPT\""
        Agent.AIDER -> null
        // `-q`: the answer alone; no session is kept for it.
        Agent.GOOSE -> "goose run --no-session -q -t \"\$PROMPT\""
        Agent.CLINE -> null
        Agent.KILO -> "kilo run \"\$PROMPT\""
        Agent.PI -> "pi -p \"\$PROMPT\""
        Agent.OMP -> "omp --print \"\$PROMPT\""
        // Without `--force`, nothing that it would change is applied.
        Agent.CURSOR -> "cursor-agent -p --output-format text \"\$PROMPT\""
    }

/**
 * The AI command that KaTTY uses when the user has not set one: the one of the first agent of [installed] that has one, **in the order of [Agent]**,
 * which is the order of the agent table of the README. Changing the order of that table changes this priority, and the other way round.
 */
public fun defaultAiCommand(installed: Collection<Agent>): String? =
    Agent.entries.firstOrNull { it in installed && it.defaultAiCommand != null }?.defaultAiCommand

/** The agents of [installed] that have an AI command, in the order of [Agent]: the first is the one of [defaultAiCommand]. */
public fun aiAgents(installed: Collection<Agent>): List<Agent> = Agent.entries.filter { it in installed && it.defaultAiCommand != null }

/**
 * The AI command that is used for the [setting] of the user ([Settings.aiCommand]): `null` follows the default ([defaultAiCommand] of [installed]),
 * a blank one turns the AI off (`null`: nothing asks it), anything else is the user's own.
 */
public fun aiCommand(setting: String?, installed: Collection<Agent>): String? = when {
    setting == null -> defaultAiCommand(installed)
    setting.isBlank() -> null
    else -> setting
}

/** What the AI answered: a command, or why there is none. */
public sealed interface AiAnswer {
    public data class Command(val line: String) : AiAnswer

    public data class Failed(val reason: String) : AiAnswer
}

/** Runs the AI command. */
public object AiPrompt {
    /** How long an answer is waited for: an agent that reads the project before it answers takes a while. */
    public const val TIMEOUT_MS: Long = 180_000

    /**
     * Runs [template] in [directory] with [prompt] as `$PROMPT`, and gives the first line of what it printed (see [aiCommandLine]).
     *
     * It is run by `/bin/sh` and not by the user's shell: what the user's files print (a greeting, a prompt) is not in the output, and `$PROMPT` is
     * written the same whatever the user's shell is. Its `PATH` is [path], the one of the user's interactive login shell (where `~/.local/bin` is
     * added), else the application's. Its input is empty and what it prints on its error output is not read.
     */
    public fun ask(
        template: String,
        prompt: String,
        directory: Path,
        path: String?,
        runner: CommandRunner = ProcessCommandRunner,
        timeoutMs: Long = TIMEOUT_MS,
    ): AiAnswer {
        val searchPath = path ?: System.getenv("PATH").orEmpty()
        val script = "PROMPT=${quoted(prompt)}; export PROMPT; PATH=${quoted(searchPath)}; export PATH\n$template"
        val result = runner.run(listOf("/bin/sh", "-c", script), directory, timeoutMs)
            ?: return AiAnswer.Failed("it did not answer within ${timeoutMs / 1000} seconds, or could not be started")
        val line = aiCommandLine(result.output)
        return when {
            result.exitCode != 0 -> AiAnswer.Failed("it ended with the code ${result.exitCode}" + (line?.let { ": $it" } ?: ""))
            line == null -> AiAnswer.Failed("it printed nothing")
            else -> AiAnswer.Command(line)
        }
    }

    // A word of the shell in single quotes, which keep everything as it is but a single quote.
    private fun quoted(text: String): String = "'" + text.replace("'", "'\\''") + "'"
}

/**
 * The command in what an AI printed: its first line that is not blank, outside the lines of a code fence (```` ```sh ````), without the backticks
 * around it nor a `$ ` before it. `null` when there is none.
 */
public fun aiCommandLine(output: String): String? =
    output.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("```") }
        .firstOrNull()
        ?.removeSurrounding("`")
        ?.removePrefix("$ ")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
