package app.katty.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TitlesTest {
    private fun reporting(app: String?, state: AgentState = AgentState.WORKING) = AgentReport(state, app)

    @Test
    fun aTerminalAtItsPromptIsCalledAfterItsShell() {
        assertEquals(TerminalIdentity("zsh", null), terminalIdentity(null, null, "zsh", "zsh"))
        assertEquals(TerminalIdentity("zsh", null), terminalIdentity(null, null, null, "zsh"))
    }

    @Test
    fun aProgramThatRunsGivesItsNameToTheTerminalUntilItEnds() {
        assertEquals("emacs", terminalIdentity(null, null, "emacs", "zsh").title)
        // It quits: the shell has the terminal again.
        assertEquals("zsh", terminalIdentity(null, null, "zsh", "zsh").title)
    }

    @Test
    fun theNameThatTheUserGaveWinsOverEverything() {
        assertEquals("build", terminalIdentity("build", reporting("claude-code"), "claude", "zsh").title)
        assertEquals("build", terminalIdentity("build", null, "emacs", "zsh").title)
    }

    @Test
    fun aBlankNameIsNoName() {
        assertEquals("emacs", terminalIdentity("  ", null, "emacs", "zsh").title)
    }

    @Test
    fun anAgentThatReportsIsCalledByItsName() {
        val identity = terminalIdentity(null, reporting("claude-code"), "node", "zsh")

        assertEquals(TerminalIdentity("Claude Code", Agent.CLAUDE), identity)
    }

    @Test
    fun aProgramThatReportsButIsNoKnownAgentKeepsItsOwnName() {
        assertEquals(TerminalIdentity("terraform", null), terminalIdentity(null, reporting("terraform"), "terraform", "zsh"))
    }

    @Test
    fun anAgentThatIsRunningIsKnownByItsProgramEvenBeforeItReports() {
        assertEquals(TerminalIdentity("Codex", Agent.CODEX), terminalIdentity(null, null, "codex", "zsh"))
        assertEquals(TerminalIdentity("Antigravity", Agent.ANTIGRAVITY), terminalIdentity(null, null, "agy", "zsh"))
        assertEquals(TerminalIdentity("Claude Code", Agent.CLAUDE), terminalIdentity(null, null, "Claude", "zsh"))
    }

    @Test
    fun whatTheAgentReportsWinsOverWhatTheProgramIsCalled() {
        assertEquals("Codex", terminalIdentity(null, reporting("codex"), "node", "zsh").title)
    }

    @Test
    fun aTerminalWhoseAgentIsDoneAndSeenIsCalledAfterTheProgramAgain() {
        // At rest with no program named: nothing is reporting any more.
        assertEquals("zsh", terminalIdentity(null, AgentReport(AgentState.IDLE), "zsh", "zsh").title)
    }

    @Test
    fun theProgramsAreMatchedByName() {
        assertEquals(Agent.CODEX, Agent.fromProcess("codex"))
        assertEquals(Agent.JUNIE, Agent.fromProcess("junie"))
        assertNull(Agent.fromProcess("gemini"))
        assertNull(Agent.fromProcess("emacs"))
        assertNull(Agent.fromProcess(null))
        assertNull(Agent.fromProcess(""))
    }
}
