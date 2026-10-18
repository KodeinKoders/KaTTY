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
        assertNull(AgentResume.sessionId(Agent.GEMINI, 1, 0, home.toFile()))
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
