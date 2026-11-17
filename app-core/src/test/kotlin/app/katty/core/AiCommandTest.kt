package app.katty.core

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AiCommandTest {
    private val directory = Files.createTempDirectory("katty-ai")

    @Test
    fun theDefaultIsTheOneOfTheFirstInstalledAgentInTheOrderOfTheReadmeTable() {
        assertEquals("claude -p \"\$PROMPT\"", defaultAiCommand(setOf(Agent.CURSOR, Agent.CLAUDE, Agent.CODEX)))
        assertEquals("codex exec --skip-git-repo-check \"\$PROMPT\"", defaultAiCommand(listOf(Agent.PI, Agent.CODEX)))
        assertEquals("opencode run \"\$PROMPT\"", defaultAiCommand(setOf(Agent.JUNIE, Agent.AIDER, Agent.CLINE, Agent.OPENCODE)), "Junie, Aider and Cline have none")
        assertNull(defaultAiCommand(setOf(Agent.JUNIE, Agent.AIDER, Agent.CLINE)))
        assertNull(defaultAiCommand(emptySet()))
    }

    @Test
    fun theSettingFollowsTheDefaultWhenNullAndTurnsTheAiOffWhenEmpty() {
        val installed = setOf(Agent.CODEX, Agent.CLAUDE)
        assertEquals("claude -p \"\$PROMPT\"", aiCommand(null, installed))
        assertNull(aiCommand(null, emptySet()))
        assertNull(aiCommand("", installed))
        assertNull(aiCommand("  ", installed))
        assertEquals("make run", aiCommand("make run", installed))
        assertEquals(listOf(Agent.CLAUDE, Agent.CODEX), aiAgents(setOf(Agent.JUNIE, Agent.CODEX, Agent.CLAUDE)), "In the order of Agent, without the ones that have none")
    }

    @Test
    fun everyDefaultTakesThePrompt() {
        for (agent in Agent.entries) agent.defaultAiCommand?.let { assertTrue("\"\$PROMPT\"" in it, "$agent: $it") }
    }

    @Test
    fun theCommandIsTheFirstLineOutsideTheDecorations() {
        assertEquals("./gradlew :app:run --debug-jvm", aiCommandLine("./gradlew :app:run --debug-jvm\n"))
        assertEquals("npm run dev", aiCommandLine("\n\n  npm run dev  \nsecond line\n"))
        assertEquals("cargo run", aiCommandLine("```sh\ncargo run\n```\n"))
        assertEquals("make debug", aiCommandLine("`make debug`"))
        assertEquals("go run .", aiCommandLine("$ go run ."))
        assertNull(aiCommandLine(""))
        assertNull(aiCommandLine("```\n\n```"))
    }

    @Test
    fun thePromptReachesTheCommandAsItIs() {
        val prompt = "It's \"quoted\", \$HOME and `ticks` \\ and\na second line"

        val answer = AiPrompt.ask("printf '%s' \"\$PROMPT\" | tr '\\n' '|'", prompt, directory, path = null)

        assertEquals(AiAnswer.Command(prompt.replace('\n', '|')), answer)
    }

    @Test
    fun itRunsInTheDirectoryWithThePathItIsGiven() {
        val bin = Files.createTempDirectory("katty-ai-bin")
        val tool = bin.resolve("fake-agent")
        Files.writeString(tool, "#!/bin/sh\necho \"./run.sh in \$(pwd -P)\"\n")
        tool.toFile().setExecutable(true)

        val answer = AiPrompt.ask("fake-agent \"\$PROMPT\"", "anything", directory, path = "$bin:/usr/bin:/bin")

        assertEquals(AiAnswer.Command("./run.sh in ${directory.toRealPath()}"), answer)
    }

    @Test
    fun aCommandThatFailsPrintsNothingOrTakesTooLongIsNoAnswer() {
        val failed = AiPrompt.ask("echo 'not logged in'; exit 3", "x", directory, path = null)
        assertEquals(AiAnswer.Failed("it ended with the code 3: not logged in"), failed)

        assertEquals(AiAnswer.Failed("it printed nothing"), AiPrompt.ask("true", "x", directory, path = null))

        val slow = AiPrompt.ask("sleep 5", "x", directory, path = null, timeoutMs = 300)
        assertTrue(slow is AiAnswer.Failed && "within" in slow.reason, "$slow")
    }
}
