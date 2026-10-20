package app.katty.core

import app.katty.terminal.ForegroundCommand
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RunningCommandsTest {
    private fun command(vararg argv: String, pipeline: Boolean = false, directory: String? = "/work", executable: String? = null) =
        ForegroundCommand(pid = 99, argv = argv.toList(), directory = directory, startedAtMillis = 5_000L, pipeline = pipeline, executable = executable)

    @Test
    fun aProgramIsKeptWithItsCommandLineAndItsDirectory() {
        assertEquals(RunningCommand(listOf("emacs", "notes.txt"), "/work"), command("emacs", "notes.txt").toRunning())
    }

    @Test
    fun aProgramOfAPipelineCannotBeStartedAgain() {
        assertNull(command("git", "log", pipeline = true).toRunning())
    }

    @Test
    fun aCommandLineThatIsTooLongIsNotKeptExceptForAnAgent() {
        val prompt = "x".repeat(3000)

        assertNull(command("make", prompt).toRunning())
        assertEquals(listOf("/bin/claude"), command("/bin/claude", prompt).toRunning(sessionId = { _, _, _ -> null })!!.argv)
    }

    @Test
    fun anAgentIsKeptWithTheConversationThatTheLocatorFinds() {
        var asked: Triple<Agent, Int, Long>? = null

        val running = command("claude", "fix it").toRunning { agent, pid, startedAt -> asked = Triple(agent, pid, startedAt); "abc-123-def" }

        assertEquals(Triple(Agent.CLAUDE, 99, 5_000L), asked)
        assertEquals(RunningCommand(listOf("claude", "fix it"), "/work", "claude", "abc-123-def"), running)
        assertNull(command("emacs").toRunning { _, _, _ -> error("Only agents have a conversation") }!!.agentSession)
    }

    @Test
    fun aRestoredProgramIsTypedAsItWasAndRunsOnlyWhenThePolicySaysSo() {
        val emacs = RunningCommand(listOf("emacs", "notes.txt")).toTyped()
        assertEquals(listOf("emacs", "notes.txt"), emacs.argv)
        assertEquals(true, emacs.run)

        val make = RunningCommand(listOf("make", "deploy")).toTyped()
        assertEquals(false, make.run, "It does something: the user decides")
    }

    @Test
    fun aRestoredAgentResumesItsConversationAndNotItsPrompt() {
        val typed = RunningCommand(listOf("claude", "--model", "opus", "fix it"), "/work", "claude", "abc-123-def").toTyped()

        assertEquals(listOf("claude", "--model", "opus", "--resume", "abc-123-def"), typed.argv)
        assertEquals(true, typed.run)
    }

    @Test
    fun anOpenCodeThatIsNotWhatThePathResolvesIsKeptByItsExecutable() {
        val v2 = "/opt/opencode-v2/bin/opencode-v2"

        val running = command("opencode", "fix it", executable = v2).toRunning { _, _, _ -> "ses_abc123" }!!

        assertEquals(RunningCommand(listOf(v2, "fix it"), "/work", "opencode", "ses_abc123"), running)
        assertEquals(listOf(v2, "-s", "ses_abc123"), running.toTyped().argv)
        assertEquals(true, running.toTyped().run)
    }

    @Test
    fun anOpenCodeWithNoExecutableIsKeptAsItWasTyped() {
        assertEquals(listOf("opencode"), command("opencode", executable = null).toRunning { _, _, _ -> null }!!.argv)
    }

    @Test
    fun openCodeAsksForTheSessionOfItsOwnDirectory() {
        // The default locator is the real one: only its directory is checked, through a database that is not there.
        val running = command("opencode", directory = "/nowhere/at/all").toRunning()!!

        assertNull(running.agentSession, "No database, no session: it reruns bare")
        assertEquals(listOf("opencode"), running.toTyped().argv)
    }
}
