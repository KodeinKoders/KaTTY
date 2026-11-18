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
        val result = run(template, prompt, directory, path, runner, timeoutMs) ?: return AiAnswer.Failed(noAnswer(timeoutMs))
        val line = aiCommandLine(result.output)
        return when {
            result.exitCode != 0 -> AiAnswer.Failed("it ended with the code ${result.exitCode}" + (line?.let { ": $it" } ?: ""))
            line == null -> AiAnswer.Failed("it printed nothing")
            else -> AiAnswer.Command(line)
        }
    }

    /**
     * Asks the AI [question] about the command [run], as [ask] runs it ([commandPrompt]), and reads its answer ([parseDiagnosis]): an explanation,
     * and, for a command that failed, a corrected command when it has one.
     */
    public fun askAbout(
        template: String,
        question: CommandQuestion,
        run: CommandRun,
        directory: Path,
        path: String?,
        runner: CommandRunner = ProcessCommandRunner,
        timeoutMs: Long = TIMEOUT_MS,
    ): AiDiagnosis {
        val result = run(template, commandPrompt(question, run, directory), directory, path, runner, timeoutMs)
            ?: return AiDiagnosis.Failed(noAnswer(timeoutMs))
        val diagnosis = parseDiagnosis(result.output)
        return when {
            result.exitCode != 0 -> AiDiagnosis.Failed("it ended with the code ${result.exitCode}" + (aiCommandLine(result.output)?.let { ": $it" } ?: ""))
            diagnosis == null -> AiDiagnosis.Failed("it printed nothing")
            else -> diagnosis
        }
    }

    // Runs [template] with [prompt] as `$PROMPT`: `null` when it could not be started or did not end in [timeoutMs].
    private fun run(template: String, prompt: String, directory: Path, path: String?, runner: CommandRunner, timeoutMs: Long): CommandResult? {
        val searchPath = path ?: System.getenv("PATH").orEmpty()
        val script = "PROMPT=${quoted(prompt)}; export PROMPT; PATH=${quoted(searchPath)}; export PATH\n$template"
        return runner.run(listOf("/bin/sh", "-c", script), directory, timeoutMs)
    }

    private fun noAnswer(timeoutMs: Long) = "it did not answer within ${timeoutMs / 1000} seconds, or could not be started"

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

/**
 * A command that ran, as it is told to the AI: its line, its exit code (`null` when it is not known, or it could not start), and what it printed,
 * of which only the end is sent ([tailForPrompt]).
 */
public data class CommandRun(val command: String, val exitCode: Int?, val output: String)

/** What the AI is asked about a command: why it failed, or what it did and what its output says. */
public enum class CommandQuestion {
    WHY_FAILED,
    EXPLAIN,
}

/** What the AI said of a command: an explanation and, for one that failed, a corrected command; or why it said nothing. */
public sealed interface AiDiagnosis {
    public data class Explained(val explanation: String, val command: String?) : AiDiagnosis

    public data class Failed(val reason: String) : AiDiagnosis
}

/** How many of the last lines of the output of a command that failed are sent to the AI. */
public const val DIAGNOSIS_MAX_LINES: Int = 200

/** How many characters of them at most, from the end. */
public const val DIAGNOSIS_MAX_CHARS: Int = 12_000

/**
 * The end of [output] that is sent to the AI: its last [DIAGNOSIS_MAX_LINES] lines and [DIAGNOSIS_MAX_CHARS] characters, without the blank lines
 * that end it. What a command prints when it fails is at its end.
 */
public fun tailForPrompt(output: String): String {
    val lines = output.trimEnd().lines().takeLast(DIAGNOSIS_MAX_LINES).joinToString("\n")
    return if (lines.length <= DIAGNOSIS_MAX_CHARS) lines else lines.takeLast(DIAGNOSIS_MAX_CHARS).substringAfter('\n')
}

/**
 * What the AI is asked about the command [run] in [directory]: [question], and the form of the answer that [parseDiagnosis] reads (an explanation,
 * and, for one that failed, a last line `COMMAND:` with a corrected command or `none`).
 */
public fun commandPrompt(question: CommandQuestion, run: CommandRun, directory: Path): String = buildString {
    appendLine(
        when (question) {
            CommandQuestion.WHY_FAILED -> "A command run in a terminal on macOS, in the directory $directory, failed."
            CommandQuestion.EXPLAIN -> "A command was run in a terminal on macOS, in the directory $directory."
        },
    )
    appendLine("Command: ${run.command}")
    appendLine(run.exitCode?.let { "Exit code: $it" } ?: "Exit code: unknown")
    appendLine("The end of what it printed:")
    appendLine("-----")
    appendLine(tailForPrompt(run.output))
    appendLine("-----")
    when (question) {
        CommandQuestion.WHY_FAILED -> {
            appendLine("Explain why it failed, in plain text and at most 5 sentences, without markdown.")
            append("Then, on a last line of its own, write COMMAND: followed by a corrected command that would work, on one line, or COMMAND: none when there is none.")
        }
        CommandQuestion.EXPLAIN -> append("Explain what the command did and what its output says, in plain text and at most 6 sentences, without markdown.")
    }
}

/**
 * What the AI answered to [commandPrompt]: its last line that starts with `COMMAND:` is the corrected command (without backticks nor a `$ `; `none`,
 * nothing, or a command with a control character is none), and the rest, without the lines of a code fence, is the explanation. `null` when it
 * said nothing.
 */
public fun parseDiagnosis(output: String): AiDiagnosis.Explained? {
    val lines = output.lines().map { it.trimEnd() }.filterNot { it.trim().startsWith("```") }
    val commandIndex = lines.indexOfLast { it.trim().startsWith("COMMAND:") }
    val command = lines.getOrNull(commandIndex)?.trim()?.removePrefix("COMMAND:")?.trim()
        ?.removeSurrounding("`")?.removePrefix("$ ")?.trim()
        ?.takeIf { it.isNotEmpty() && !it.equals("none", ignoreCase = true) && it.none { char -> char.isISOControl() } }
    val explanation = lines.filterIndexed { index, _ -> index != commandIndex }.joinToString("\n").trim()
    if (explanation.isEmpty() && command == null) return null
    return AiDiagnosis.Explained(explanation, command)
}
