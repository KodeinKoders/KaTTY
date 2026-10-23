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
    fun anAgentsCommandThatIsNotItsInterfaceIsOnlyTyped() {
        // What it does is not a conversation to come back to: uninstalling, logging out, updating.
        for ((argv, agent) in listOf(
            listOf("kilo", "uninstall") to "kilocode",
            listOf("kilo", "upgrade") to "kilocode",
            listOf("cursor-agent", "logout") to "cursor",
            listOf("goose", "update") to "goose",
            listOf("openhands", "logout") to "openhands",
        )) {
            val typed = RunningCommand(argv, "/work", agent).toTyped()
            assertEquals(argv, typed.argv)
            assertEquals(false, typed.run, "${argv.joinToString(" ")} is typed, not run")
        }
    }

    @Test
    fun anAgentsInterfaceStillRunsByItself() {
        assertEquals(true, RunningCommand(listOf("kilo"), "/work", "kilocode", "ses_abc").toTyped().run)
        assertEquals(true, RunningCommand(listOf("goose", "session"), "/work", "goose").toTyped().run)
        assertEquals(true, RunningCommand(listOf("cursor-agent", "resume"), "/work", "cursor").toTyped().run)
        assertEquals(true, RunningCommand(listOf("openhands", "--always-approve"), "/work", "openhands").toTyped().run)
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
    fun anOpenCodeThatThePathOfTheLoginShellResolvesIsKeptByItsName() {
        // Homebrew's link to the file of a version, which an upgrade removes; KaTTY opened from the Finder has not got that PATH.
        val root = java.nio.file.Files.createTempDirectory("katty-path").toFile().also { it.deleteOnExit() }
        val real = root.resolve("Cellar/opencode/1.2.3/bin/opencode").also { it.parentFile.mkdirs(); it.writeText("#!/bin/sh\n"); it.setExecutable(true) }
        val bin = root.resolve("bin").also { it.mkdirs() }
        java.nio.file.Files.createSymbolicLink(bin.resolve("opencode").toPath(), real.toPath())

        val running = command("opencode", executable = real.canonicalPath).toRunning(searchPath = bin.path) { _, _, _ -> null }!!

        assertEquals(listOf("opencode"), running.argv)
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

    @Test
    fun anAiderIsKeptByItsScriptAndComesBackWithItsChat() {
        val python = "/opt/homebrew/Cellar/python@3.12/Frameworks/Python.app/Contents/MacOS/Python"

        val running = command(python, "/opt/homebrew/bin/aider", "--model", "sonnet", "--message", "fix it").toRunning()!!

        assertEquals("aider", running.agent)
        assertEquals(listOf("/opt/homebrew/bin/aider", "--model", "sonnet", "--message", "fix it"), running.argv)
        // The prompt is not typed again, the chat of the directory is loaded, and it runs by itself.
        assertEquals(listOf("/opt/homebrew/bin/aider", "--model", "sonnet", "--restore-chat-history"), running.toTyped().argv)
        assertEquals(true, running.toTyped().run)
    }

    @Test
    fun aClineIsKeptByItsScriptAndComesBackWithHowItWasRunAndNotItsPromptOrKey() {
        val running = command("node", "/opt/homebrew/bin/cline", "--yolo", "-m", "sonnet", "-k", "sk-secret", "fix it").toRunning { _, _, _ -> null }!!

        assertEquals("cline", running.agent)
        assertEquals(listOf("/opt/homebrew/bin/cline", "--yolo", "-m", "sonnet", "-k", "sk-secret", "fix it"), running.argv)
        // The key is the user's, and the prompt would be sent twice: neither is typed again.
        assertEquals(listOf("/opt/homebrew/bin/cline", "--yolo", "-m", "sonnet"), running.toTyped().argv)
        assertEquals(true, running.toTyped().run)
    }

    @Test
    fun aKiloIsKeptByItsScriptWhateverItIsCalledAndComesBackWithHowItWasRun() {
        val running = command("node", "/opt/homebrew/bin/kilocode", "--auto", "-m", "anthropic/sonnet", "--prompt", "fix it").toRunning { _, _, _ -> "ses_abc123" }!!

        assertEquals("kilocode", running.agent)
        assertEquals(listOf("/opt/homebrew/bin/kilocode", "--auto", "-m", "anthropic/sonnet", "--prompt", "fix it"), running.argv)
        // The prompt is not typed again, and the conversation is the one it was in.
        assertEquals(listOf("/opt/homebrew/bin/kilocode", "--auto", "-m", "anthropic/sonnet", "--session", "ses_abc123"), running.toTyped().argv)
        assertEquals(true, running.toTyped().run)
    }

    @Test
    fun aPiComesBackAsPiAloneBecauseItHidesItsCommandLineAndItsConversationIsNotKnown() {
        // What the system has of a running Pi: its title, and an empty argument.
        val running = command("pi", "", executable = "/opt/homebrew/bin/node").toRunning()!!

        assertEquals("pi", running.agent)
        assertNull(running.agentSession)
        assertEquals(listOf("pi"), running.toTyped().argv)
        assertEquals(true, running.toTyped().run)
    }


    @Test
    fun anOmpComesBackWithItsOptionsOnTheSessionOfItsTerminalAndNotWithWhatOpenedIt() {
        val running = command("omp", "--model", "opus", "--api-key", "sk-secret", "--continue", "--thinking", "high", "fix it").toRunning { _, _, _ -> "01a15f68-e895-7281-9c21-4abd1f48cab2" }!!

        assertEquals("omp", running.agent)
        // The key is not typed again, `--continue` is replaced by the session it was in, and the prompt is not sent twice.
        assertEquals(listOf("omp", "--model", "opus", "--thinking", "high", "--resume=01a15f68-e895-7281-9c21-4abd1f48cab2"), running.toTyped().argv)
        assertEquals(true, running.toTyped().run)
    }

    @Test
    fun anOmpWithNoConversationComesBackAsItWasStartedWithNone() {
        val running = command("omp", "--model", "opus").toRunning { _, _, _ -> null }!!

        assertEquals(listOf("omp", "--model", "opus"), running.toTyped().argv)
    }

    @Test
    fun anOpenHandsComesBackOnItsConversationWithHowItWasRunAndNotWhatSeededOrResumedIt() {
        val running = command("openhands", "--always-approve", "-t", "fix it", "--resume", "11111111-2222-3333-4444-555555555555", "--last")
            .toRunning { _, _, _ -> "22a2ff2b-1255-419b-9636-5045c8977380" }!!

        assertEquals("openhands", running.agent)
        // The task is not given again, and the id it was resumed with is the one it is in now.
        assertEquals(listOf("openhands", "--always-approve", "--resume", "22a2ff2b-1255-419b-9636-5045c8977380"), running.toTyped().argv)
        assertEquals(true, running.toTyped().run)
    }

    private fun running(agent: String, directory: String?, session: String? = "ses_1") = RunningCommand(listOf(agent), directory, agent, session)

    @Test
    fun anAgentGuessedByItsDirectoryComesBackOnNoneWhenAnotherTerminalRunsTheSameAgentThere() {
        val crush = running("crush", "/work", "aac2da97-b49c-412f-8847-0302db8a3994")

        // Another Crush in the same directory: the newest conversation of the directory may be that one's.
        assertEquals(crush.copy(agentSession = null), crush.apartFromOthers(listOf(running("crush", "/work", "other")), guessed = true))
        // Another directory, another agent, or nothing at all, leave it as it is.
        assertEquals(crush, crush.apartFromOthers(listOf(running("crush", "/elsewhere")), guessed = true))
        assertEquals(crush, crush.apartFromOthers(listOf(running("goose", "/work")), guessed = true))
        assertEquals(crush, crush.apartFromOthers(listOf(null, RunningCommand(listOf("emacs"), "/work")), guessed = true))
        assertEquals(crush, crush.apartFromOthers(emptyList(), guessed = true))
    }

    @Test
    fun anAgentThatKnowsItsConversationKeepsItWhateverIsInTheDirectory() {
        // Claude Code has its own file for each process: `guessed` is false, and the other one in the directory changes nothing.
        val claude = running("claude", "/work", "7a1c2b3d-0000-4000-8000-000000000001")

        assertEquals(claude, claude.apartFromOthers(listOf(running("claude", "/work")), guessed = false))
        // Nor is there anything to take off when there is no conversation, or when the directory is not known.
        assertEquals(running("crush", "/work", null), running("crush", "/work", null).apartFromOthers(listOf(running("crush", "/work")), guessed = true))
        assertEquals(running("crush", null), running("crush", null).apartFromOthers(listOf(running("crush", null)), guessed = true))
    }

    @Test
    fun theAgentsThatAreGuessedByTheirDirectoryAreTheOnesThatHaveNothingElseToGoBy() {
        assertEquals(setOf(Agent.CRUSH, Agent.GOOSE, Agent.AIDER, Agent.OPENHANDS, Agent.CURSOR), Agent.entries.filter { it.byDirectory }.toSet())
    }

    @Test
    fun aCursorAgentComesBackAsItsLauncherWithHowItWasRunOnItsChatAndNotWhatSeededOrResumedIt() {
        val script = "/Users/me/.local/share/cursor-agent/versions/2026.10.19-6bbf2c4/index.js"
        val running = command("/Users/me/.local/bin/agent", "--use-system-ca", script, "--force", "--model", "gpt-5", "--api-key", "sk-secret", "--continue", "fix it")
            .toRunning { _, _, _ -> "11a1677b-658c-48c7-9c10-d0bfa8ee6fb2" }!!

        assertEquals("cursor", running.agent)
        // The node flag and the script are not typed: they are what the launcher runs. The key and the prompt are not typed again.
        assertEquals(listOf("/Users/me/.local/bin/agent", "--force", "--model", "gpt-5", "--api-key", "sk-secret", "--continue", "fix it"), running.argv)
        assertEquals(
            listOf("/Users/me/.local/bin/agent", "--force", "--model", "gpt-5", "--resume", "11a1677b-658c-48c7-9c10-d0bfa8ee6fb2"),
            running.toTyped().argv,
        )
        assertEquals(true, running.toTyped().run)
    }
}
