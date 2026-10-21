package app.katty.core

import app.katty.terminal.TerminalProgramState
import app.katty.terminal.TerminalProgramStatus
import java.io.File

/**
 * The coding agents that the application knows by name: their name, as the user sees it, and the program that runs them.
 * They are not what decides that a terminal is on the board (any program that reports is): they give a logo, a name, a usage reading and a resume.
 *
 * [id] is what the settings keep, and what a program's name is matched against: see [fromApp].
 */
public enum class Agent(public val id: String, public val displayName: String, public val executable: String, public val interpreted: Boolean = false) {
    CLAUDE("claude", "Claude Code", "claude"),
    CODEX("codex", "Codex", "codex"),
    ANTIGRAVITY("antigravity", "Antigravity", "agy"),
    COPILOT("copilot", "Copilot CLI", "copilot"),
    JUNIE("junie", "Junie", "junie"),
    OPENCODE("opencode", "OpenCode", "opencode"),
    CRUSH("crush", "Crush", "crush"),
    AIDER("aider", "Aider", "aider", interpreted = true),
    GOOSE("goose", "Goose", "goose"),
    ;

    public companion object {
        /**
         * The agent that a program calls itself (the `app` of a program status report: `claude-code`, `codex`…), or `null` for a program that
         * is not one of them. Case and punctuation do not matter: it is how a program names itself, and nothing says how.
         */
        public fun fromApp(app: String): Agent? {
            val name = app.lowercase().filter { it.isLetterOrDigit() }
            return when {
                "claude" in name -> CLAUDE
                "codex" in name -> CODEX
                "antigravity" in name || name == "agy" -> ANTIGRAVITY
                "copilot" in name -> COPILOT
                "junie" in name -> JUNIE
                "opencode" in name -> OPENCODE
                "crush" in name -> CRUSH
                "aider" in name -> AIDER
                "goose" in name -> GOOSE
                else -> null
            }
        }

        public fun byId(id: String): Agent? = entries.firstOrNull { it.id == id }

        /** The agent whose program is called [process] (`claude`, `agy`…), `null` for any other program. */
        public fun fromProcess(process: String?): Agent? = process?.let { name -> entries.firstOrNull { it.executable.equals(name, ignoreCase = true) } }

        /**
         * The agent that runs as [argv], a command line as the system has it: its program, or, for an agent that is a script (Aider is Python), the
         * script that an interpreter (`python3.12`, `Python`) was given first: `Python /opt/homebrew/bin/aider --model x` is Aider, whose process is called `Python`.
         */
        public fun fromCommand(argv: List<String>): Agent? {
            val program = argv.firstOrNull()?.let { File(it).name } ?: return null
            if (!Regex("(?i)python[0-9.]*").matches(program)) return fromProcess(program)
            return argv.getOrNull(1)?.let { File(it).name }?.let { script -> entries.firstOrNull { it.interpreted && it.executable == script } }
        }
    }
}

/**
 * What a terminal says of itself, as of one moment: its state, which program says it, and the line that goes with it.
 *
 * @param app What the program calls itself, as it said it, `null` when it did not say.
 * @param note One line of the program's own words about what it does or waits for: the text is the program's, shown as it is.
 * @param reporting Whether a program is reporting at all, which is what puts a terminal on the board: a shell at its prompt reports nothing.
 * A program that only says it is at rest, with no name, reports.
 */
public data class AgentReport(
    val state: AgentState,
    val app: String? = null,
    val note: String? = null,
    val reporting: Boolean = app != null || state != AgentState.IDLE,
) {
    /** The agent that [app] is, `null` for a program that is not a known one, and for a terminal that says nothing. */
    val agent: Agent? get() = app?.let(Agent::fromApp)
}

/**
 * What the records of a terminal's program status (OSC 7501) come to: the state of the one that matters most (waiting for the user, then
 * failed, then working, then done, then at rest), the name of the program that says it, and its note, which is the message of that record, or
 * its title when it has no message.
 *
 * No record is a terminal at rest.
 */
public fun summarize(records: List<TerminalProgramStatus>): AgentReport {
    if (records.isEmpty()) return AgentReport(AgentState.IDLE)
    val ranked = records.sortedByDescending { it.state.toAgentState().urgency }
    val top = ranked.first()
    val note = (top.message.ifBlank { top.title }).trim().ifEmpty { null }
    // The program is the one that the most important record names, else any record that names one.
    val app = ranked.firstOrNull { it.app.isNotEmpty() }?.app
    return AgentReport(top.state.toAgentState(), app, note, reporting = true)
}

internal fun TerminalProgramState.toAgentState(): AgentState = when (this) {
    TerminalProgramState.IDLE -> AgentState.IDLE
    TerminalProgramState.WORKING -> AgentState.WORKING
    TerminalProgramState.BLOCKED -> AgentState.NEEDS_INPUT
    TerminalProgramState.DONE -> AgentState.DONE
    TerminalProgramState.ERROR -> AgentState.ERROR
}

/**
 * A terminal's [report] and since when it has been in that state: what the board's card and the list of attention say. Reports of the same
 * state do not move the time.
 */
public data class AgentInfo(val report: AgentReport, val since: Long) {
    val state: AgentState get() = report.state
}
