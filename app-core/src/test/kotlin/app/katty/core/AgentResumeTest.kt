package app.katty.core

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AgentResumeTest {
    private val home: Path = Files.createTempDirectory("katty-home")

    @AfterTest
    fun cleanUp() {
        home.toFile().deleteRecursively()
    }

    private val id = "f65d7eb8-beec-40e0-9c07-40c1b1a56ba2"

    private fun session(pid: Int, sessionId: String = id, startedAt: Long = 1_000_000L) {
        home.resolve(".claude/sessions").createDirectories()
        home.resolve(".claude/sessions/$pid.json").writeText("""{"pid":$pid,"sessionId":"$sessionId","startedAt":$startedAt,"status":"idle"}""")
    }

    private fun transcript(sessionId: String = id) {
        home.resolve(".claude/projects/-some-dir").createDirectories()
        home.resolve(".claude/projects/-some-dir/$sessionId.jsonl").writeText("{}")
    }

    @Test
    fun claudeSessionIsTheOneThatTheFileOfItsProcessSays() {
        session(4242)
        transcript()

        assertEquals(id, AgentResume.sessionId(Agent.CLAUDE, 4242, 1_000_000L, home.toFile()))
    }

    @Test
    fun noSessionWhenTheConversationHasNoTranscriptYet() {
        session(4242)

        assertNull(AgentResume.sessionId(Agent.CLAUDE, 4242, 1_000_000L, home.toFile()), "Nothing to resume: it would say it found no conversation")
    }

    @Test
    fun noSessionFromTheFileOfAnOlderProcessThatHadThePid() {
        session(4242, startedAt = 1_000_000L)
        transcript()

        assertNull(AgentResume.sessionId(Agent.CLAUDE, 4242, 1_000_000L + 60_000, home.toFile()))
    }

    @Test
    fun noSessionWithoutAFileOrFromAnIdThatIsNotAnId() {
        assertNull(AgentResume.sessionId(Agent.CLAUDE, 1, 0, home.toFile()))
        session(7, sessionId = "x; rm -rf ~")
        transcript("x; rm -rf ~")

        assertNull(AgentResume.sessionId(Agent.CLAUDE, 7, 0, home.toFile()), "The id goes into a command line")
    }

    @Test
    fun otherAgentsHaveNoWayToTellYet() {
        assertNull(AgentResume.sessionId(Agent.CODEX, 1, 0, home.toFile()))
        assertNull(AgentResume.sessionId(Agent.ANTIGRAVITY, 1, 0, home.toFile()))
    }

    private val junieId = "session-261020-153615-196j"

    private fun junieProcess(pid: Int, sessionId: String = junieId, startedAt: Long = 1_000_000L) {
        home.resolve(".junie/processes").createDirectories()
        home.resolve(".junie/processes/$pid-$sessionId-0e590669f6b8.json")
            .writeText("""{"pid":$pid,"sessionId":"$sessionId","projectPath":"/some/dir","startedAt":$startedAt}""")
    }

    private fun junieEvents(sessionId: String = junieId) {
        home.resolve(".junie/sessions/$sessionId").createDirectories()
        home.resolve(".junie/sessions/$sessionId/events.jsonl").writeText("{}")
    }

    @Test
    fun junieSessionIsTheOneThatTheFileOfItsProcessNames() {
        junieProcess(4242)
        junieEvents()
        // Another process's file, whose pid starts the same.
        junieProcess(42420, sessionId = "session-261020-100607-1hef")
        junieEvents("session-261020-100607-1hef")

        assertEquals(junieId, AgentResume.sessionId(Agent.JUNIE, 4242, 1_000_000L, home.toFile()))
    }

    @Test
    fun noJunieSessionWithoutAMessageOrFromAnOlderProcess() {
        junieProcess(4242)
        assertNull(AgentResume.sessionId(Agent.JUNIE, 4242, 1_000_000L, home.toFile()), "A conversation with no message has nothing to resume")

        junieEvents()
        assertNull(AgentResume.sessionId(Agent.JUNIE, 4242, 1_000_000L + 60_000, home.toFile()), "The file of a killed process that had the pid")
    }

    private val copilotId = "35486de2-1c3f-414d-ba83-54d5ea8fae11"

    private fun copilotLock(pid: Int, sessionId: String = copilotId, at: Long = 1_000_000L, events: Boolean = true) {
        val directory = home.resolve(".copilot/session-state/$sessionId").createDirectories()
        val lock = directory.resolve("inuse.$pid.lock")
        lock.writeText("$pid")
        lock.toFile().setLastModified(at)
        if (events) directory.resolve("events.jsonl").writeText("{}")
    }

    @Test
    fun copilotSessionIsTheOneThatItsProcessHolds() {
        copilotLock(4242)
        copilotLock(4243, sessionId = "07b629d8-7b88-491f-9c3a-a88cca83bb16")

        assertEquals(copilotId, AgentResume.sessionId(Agent.COPILOT, 4242, 1_000_000L, home.toFile()))
    }

    @Test
    fun noCopilotSessionWithoutAMessageOrFromAnOlderProcess() {
        copilotLock(4242, events = false)
        assertNull(AgentResume.sessionId(Agent.COPILOT, 4242, 1_000_000L, home.toFile()), "A conversation with no message has nothing to resume")

        copilotLock(4242)
        assertNull(AgentResume.sessionId(Agent.COPILOT, 4242, 1_000_000L + 60_000, home.toFile()), "The lock of a killed process that had the pid")
    }

    @Test
    fun resumesJunieThroughItsCommandAndNotTheBinaryOfItsVersion() {
        val binary = "/Users/me/.local/share/junie/versions/3659.7/Applications/junie.app/Contents/MacOS/junie"
        assertEquals(
            listOf("junie", "--brave", "--model=opus", "--resume", "--session-id=$junieId"),
            AgentResume.argv(Agent.JUNIE, listOf(binary, "--brave", "--model=opus", "--session-id=other", "fix the bug"), junieId),
        )
        assertEquals(listOf("junie"), AgentResume.argv(Agent.JUNIE, listOf(binary), null))
    }

    @Test
    fun resumesCopilotWithTheOptionsThatSayHowItRanAndNotThePrompt() {
        assertEquals(
            listOf("copilot", "--yolo", "--model", "gpt-5", "--resume", copilotId),
            AgentResume.argv(Agent.COPILOT, listOf("copilot", "--yolo", "--model", "gpt-5", "--resume", "old-name", "-i", "fix the bug"), copilotId),
        )
    }

    @Test
    fun resumesClaudeWithTheOptionsThatSayHowItRanAndNotThePrompt() {
        assertEquals(
            listOf("claude", "--dangerously-skip-permissions", "--model", "opus", "--resume", id),
            AgentResume.argv(Agent.CLAUDE, listOf("claude", "--dangerously-skip-permissions", "--model", "opus", "fix the bug"), id),
        )
    }

    @Test
    fun dropsTheOptionsThatPickAnotherConversationOrAreNotKnown() {
        assertEquals(
            listOf("claude", "--resume", id),
            AgentResume.argv(Agent.CLAUDE, listOf("claude", "-c", "--session-id", "abc", "--add-dir", "a", "b", "-p", "do it"), id),
        )
    }

    @Test
    fun anOptionThatTakesAValueDoesNotTakeTheNextOption() {
        assertEquals(listOf("claude", "--verbose"), AgentResume.argv(Agent.CLAUDE, listOf("claude", "--model", "--verbose"), null))
        assertEquals(listOf("claude", "--model=opus"), AgentResume.argv(Agent.CLAUDE, listOf("claude", "--model=opus"), null))
    }

    @Test
    fun withNoSessionTheAgentStartsAFreshConversation() {
        assertEquals(listOf("/home/me/.local/bin/claude"), AgentResume.argv(Agent.CLAUDE, listOf("/home/me/.local/bin/claude", "a prompt"), null))
        assertEquals(listOf("codex"), AgentResume.argv(Agent.CODEX, listOf("codex", "a prompt"), "anything"))
    }
}
