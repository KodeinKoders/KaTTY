package app.katty.core

import app.katty.terminal.TerminalProgramKind
import app.katty.terminal.TerminalProgramState
import app.katty.terminal.TerminalProgramStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentsTest {
    private fun record(
        state: TerminalProgramState,
        app: String = "",
        message: String = "",
        title: String = "",
        id: String = "",
    ) = TerminalProgramStatus(id, app, state, TerminalProgramKind.NONE, null, title, message, 0)

    @Test
    fun anAgentIsKnownByHowItCallsItself() {
        assertEquals(Agent.CLAUDE, Agent.fromApp("claude-code"))
        assertEquals(Agent.CLAUDE, Agent.fromApp("Claude Code"))
        assertEquals(Agent.CODEX, Agent.fromApp("codex"))
        assertEquals(Agent.ANTIGRAVITY, Agent.fromApp("antigravity"))
        assertEquals(Agent.ANTIGRAVITY, Agent.fromApp("agy"))
        assertEquals(Agent.COPILOT, Agent.fromApp("copilot_cli"))
        assertEquals(Agent.JUNIE, Agent.fromApp("Junie"))
        // Gemini CLI is deprecated: it is a program like any other, which reports and has a card but has no logo.
        assertNull(Agent.fromApp("gemini-cli"))
        assertEquals(Agent.OPENCODE, Agent.fromApp("opencode"))
        assertEquals(Agent.OPENCODE, Agent.fromProcess("opencode"))
        assertEquals(Agent.CRUSH, Agent.fromApp("crush"))
        assertEquals(Agent.CRUSH, Agent.fromProcess("crush"))
        assertEquals(Agent.AIDER, Agent.fromApp("aider"))
        assertEquals(Agent.AIDER, Agent.fromProcess("aider"))
        assertEquals(Agent.GOOSE, Agent.fromApp("goose"))
        assertEquals(Agent.GOOSE, Agent.fromProcess("goose"))
    }

    @Test
    fun aProgramThatIsNoAgentIsNotOne() {
        assertNull(Agent.fromApp("terraform"))
        assertNull(Agent.fromApp("cargo"))
        assertNull(Agent.fromApp(""))
    }

    @Test
    fun aScriptIsTheAgentWhenAnInterpreterWasGivenItFirst() {
        // What the system says of a running Aider: the process is the interpreter, its first argument the script.
        assertEquals(Agent.AIDER, Agent.fromCommand(listOf("/opt/homebrew/Cellar/python@3.12/Frameworks/Python.app/Contents/MacOS/Python", "/opt/homebrew/bin/aider", "--model", "x")))
        assertEquals(Agent.AIDER, Agent.fromCommand(listOf("python3.12", "/home/me/.venv/bin/aider")))
        assertEquals(Agent.CLAUDE, Agent.fromCommand(listOf("/home/me/.local/bin/claude", "a prompt")))
        // A script of another name is no agent, and neither is a program that is not an interpreter given `aider`.
        assertNull(Agent.fromCommand(listOf("python3", "/home/me/build.py")))
        assertNull(Agent.fromCommand(listOf("python3", "-m", "aider")))
        assertNull(Agent.fromCommand(listOf("emacs", "aider")))
        assertNull(Agent.fromCommand(emptyList()))
    }

    @Test
    fun theIdsAreWhatTheSettingsKeep() {
        assertEquals(listOf("claude", "codex", "antigravity", "copilot", "junie", "opencode", "crush", "aider", "goose"), Agent.entries.map { it.id })
        assertEquals(Agent.CODEX, Agent.byId("codex"))
        assertNull(Agent.byId("nothing"))
    }

    @Test
    fun noRecordIsATerminalAtRest() {
        val report = summarize(emptyList())

        assertEquals(AgentState.IDLE, report.state)
        assertFalse(report.reporting)
        assertNull(report.agent)
    }

    @Test
    fun eachStateOfTheProtocolIsOneOfTheApplications() {
        assertEquals(AgentState.IDLE, summarize(listOf(record(TerminalProgramState.IDLE, "a"))).state)
        assertEquals(AgentState.WORKING, summarize(listOf(record(TerminalProgramState.WORKING, "a"))).state)
        assertEquals(AgentState.NEEDS_INPUT, summarize(listOf(record(TerminalProgramState.BLOCKED, "a"))).state)
        assertEquals(AgentState.DONE, summarize(listOf(record(TerminalProgramState.DONE, "a"))).state)
        assertEquals(AgentState.ERROR, summarize(listOf(record(TerminalProgramState.ERROR, "a"))).state)
    }

    @Test
    fun theRecordThatMattersMostSetsTheState() {
        val records = listOf(
            record(TerminalProgramState.DONE, "claude-code"),
            record(TerminalProgramState.WORKING, id = "build"),
            record(TerminalProgramState.BLOCKED, id = "deploy", message = "Apply this patch?"),
        )

        val report = summarize(records)

        assertEquals(AgentState.NEEDS_INPUT, report.state)
        assertEquals("Apply this patch?", report.note)
    }

    @Test
    fun aFailureMattersMoreThanWorkAndWorkMoreThanDone() {
        assertEquals(AgentState.ERROR, summarize(listOf(record(TerminalProgramState.WORKING, "a"), record(TerminalProgramState.ERROR, id = "x"))).state)
        assertEquals(AgentState.WORKING, summarize(listOf(record(TerminalProgramState.DONE, "a"), record(TerminalProgramState.WORKING, id = "x"))).state)
    }

    @Test
    fun theNoteIsTheMessageElseTheTitle() {
        assertEquals("Running the tests", summarize(listOf(record(TerminalProgramState.WORKING, "a", message = "Running the tests", title = "tests"))).note)
        assertEquals("tests", summarize(listOf(record(TerminalProgramState.WORKING, "a", title = "tests"))).note)
        assertNull(summarize(listOf(record(TerminalProgramState.WORKING, "a"))).note)
        assertNull(summarize(listOf(record(TerminalProgramState.WORKING, "a", message = "   "))).note)
    }

    @Test
    fun theProgramIsTheOneThatTheMostImportantRecordNamesElseAnyThatNamesOne() {
        val named = summarize(listOf(record(TerminalProgramState.BLOCKED, "codex"), record(TerminalProgramState.WORKING, "claude-code", id = "x")))
        assertEquals(Agent.CODEX, named.agent)

        val inherited = summarize(listOf(record(TerminalProgramState.BLOCKED, ""), record(TerminalProgramState.WORKING, "claude-code", id = "x")))
        assertEquals(Agent.CLAUDE, inherited.agent)
    }

    @Test
    fun aTerminalThatReportsIsOneThatHasAProgramOrAState() {
        assertTrue(summarize(listOf(record(TerminalProgramState.IDLE, "claude-code"))).reporting)
        assertTrue(summarize(listOf(record(TerminalProgramState.WORKING))).reporting)
        // A program that only says it is at rest, with no name, still reports.
        assertTrue(summarize(listOf(record(TerminalProgramState.IDLE))).reporting)
        assertFalse(summarize(emptyList()).reporting)
    }

    @Test
    fun aProgramThatIsNotKnownKeepsItsOwnName() {
        val report = summarize(listOf(record(TerminalProgramState.WORKING, "terraform")))

        assertEquals("terraform", report.app)
        assertNull(report.agent)
    }
}
