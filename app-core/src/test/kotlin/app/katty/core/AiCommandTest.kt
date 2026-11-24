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

        // On Windows the command is PowerShell's (the line breaks of its output are taken off).
        val command = if (isWindows) "Write-Output (\$PROMPT -replace \"`n\", '|')" else "printf '%s' \"\$PROMPT\" | tr '\\n' '|'"
        val answer = AiPrompt.ask(command, prompt, directory, path = null)

        assertEquals(AiAnswer.Command(prompt.replace('\n', '|')), answer)
    }

    @Test
    fun itRunsInTheDirectoryWithThePathItIsGiven() {
        val bin = Files.createTempDirectory("katty-ai-bin")
        // A batch file on Windows, which PowerShell finds by the extensions of PATHEXT.
        if (isWindows) {
            Files.writeString(bin.resolve("fake-agent.cmd"), "@echo ./run.sh in %CD%\r\n")
        } else {
            val tool = bin.resolve("fake-agent")
            Files.writeString(tool, "#!/bin/sh\necho \"./run.sh in \$(pwd -P)\"\n")
            tool.toFile().setExecutable(true)
        }
        val path = if (isWindows) "$bin;${System.getenv("SystemRoot")}\\System32" else "$bin:/usr/bin:/bin"

        val answer = AiPrompt.ask("fake-agent \"\$PROMPT\"", "anything", directory, path = path)

        // The directory as the system gives it, which may be a short name on Windows (RUNNER~1).
        val line = (answer as AiAnswer.Command).line
        assertTrue(line.startsWith("./run.sh in "), line)
        assertEquals(directory.toRealPath(), java.nio.file.Path.of(line.removePrefix("./run.sh in ")).toRealPath())
    }

    @Test
    fun aCommandThatFailsPrintsNothingOrTakesTooLongIsNoAnswer() {
        val failed = AiPrompt.ask("echo 'not logged in'; exit 3", "x", directory, path = null)
        assertEquals(AiAnswer.Failed("it ended with the code 3: not logged in"), failed)

        assertEquals(AiAnswer.Failed("it printed nothing"), AiPrompt.ask(if (isWindows) "\$null" else "true", "x", directory, path = null))

        val slow = AiPrompt.ask(if (isWindows) "Start-Sleep 5" else "sleep 5", "x", directory, path = null, timeoutMs = 300)
        assertTrue(slow is AiAnswer.Failed && "within" in slow.reason, "$slow")
    }

    // --- Why a command failed ---

    @Test
    fun thePromptHasTheCommandItsCodeAndTheEndOfItsOutput() {
        val output = (1..500).joinToString("\n") { "line $it" } + "\n\n"

        val prompt = commandPrompt(CommandQuestion.WHY_FAILED, CommandRun("make deploy", 2, output), directory)

        assertTrue("Command: make deploy" in prompt, prompt)
        assertTrue("Exit code: 2" in prompt, prompt)
        assertTrue("line 500\n-----" in prompt, "The last line is kept, without the blank ones after it")
        assertTrue("line 301\n" in prompt && "line 300\n" !in prompt, "The last $DIAGNOSIS_MAX_LINES lines")
        assertTrue("COMMAND: none" in prompt, "It says the form of the answer")
        assertTrue("Exit code: unknown" in commandPrompt(CommandQuestion.WHY_FAILED, CommandRun("x", null, ""), directory))
    }

    // A command that went well is explained: what it did and what its output says, with no correction asked for.
    @Test
    fun aCommandIsExplainedWithoutACorrection() {
        val prompt = commandPrompt(CommandQuestion.EXPLAIN, CommandRun("git log -1", 0, "commit abc"), directory)

        assertTrue("Command: git log -1" in prompt && "Exit code: 0" in prompt && "commit abc" in prompt, prompt)
        assertTrue("Explain what the command did" in prompt, prompt)
        assertTrue("COMMAND:" !in prompt && "failed" !in prompt, "Neither a failure nor a correction")
    }

    @Test
    fun theEndOfALongOutputIsCutToItsCharacters() {
        val long = (1..50).joinToString("\n") { "x".repeat(1_000) + it }

        val tail = tailForPrompt(long)

        assertTrue(tail.length <= DIAGNOSIS_MAX_CHARS, "${tail.length}")
        assertTrue(tail.endsWith("x50"))
        assertTrue(tail.lines().all { it.startsWith("xxx") }, "A line cut in its middle is left out")
    }

    @Test
    fun theAnswerIsAnExplanationAndACorrectedCommand() {
        assertEquals(
            AiDiagnosis.Explained("The folder does not exist.\nIt was mistyped.", "ls /tmp"),
            parseDiagnosis("The folder does not exist.\nIt was mistyped.\nCOMMAND: `ls /tmp`\n"),
        )
        assertEquals(AiDiagnosis.Explained("Nothing to fix.", null), parseDiagnosis("Nothing to fix.\nCOMMAND: none"))
        assertEquals(AiDiagnosis.Explained("No form.", null), parseDiagnosis("No form.\n"), "Without a COMMAND line, all is the explanation")
        assertEquals(AiDiagnosis.Explained("Fenced.", "make"), parseDiagnosis("```\nFenced.\n```\nCOMMAND: \$ make"))
        assertEquals(AiDiagnosis.Explained("Bad.", null), parseDiagnosis("Bad.\nCOMMAND: rm -rf /\u0007"), "A control character refuses the command")
        assertNull(parseDiagnosis("\n\n"))
    }

    @Test
    fun theAiIsAskedWhyAndItsAnswerRead() {
        // The question, which is in the script of sh, and on Windows in the environment of PowerShell.
        var asked: String? = null
        val runner = object : CommandRunner {
            override fun run(command: List<String>, directory: java.nio.file.Path, timeoutMs: Long): CommandResult? = error("Run with its environment")

            override fun run(command: List<String>, directory: java.nio.file.Path, timeoutMs: Long, environment: EnvironmentChange): CommandResult? {
                asked = if (isWindows) environment.variables["PROMPT"] else command.last()
                return CommandResult(0, "The file is missing.\nCOMMAND: touch a.txt\n")
            }
        }

        val diagnosis = AiPrompt.askAbout("agent \"\$PROMPT\"", CommandQuestion.WHY_FAILED, CommandRun("cat a.txt", 1, "cat: a.txt: No such file"), directory, null, runner)

        assertEquals(AiDiagnosis.Explained("The file is missing.", "touch a.txt"), diagnosis)
        assertTrue("cat: a.txt: No such file" in asked!!, "The output is in the question")
        val failing = AiPrompt.askAbout("echo 'not logged in'; exit 3", CommandQuestion.WHY_FAILED, CommandRun("x", 1, ""), directory, path = null)
        assertEquals(AiDiagnosis.Failed("it ended with the code 3: not logged in"), failing)
    }

    @Test
    fun windowsRunsTheCommandWithPowerShellAndTheQuestionInTheEnvironment() {
        val command = AiPrompt.powerShellCommand("agent \"\$PROMPT\"")

        assertEquals(listOf("-NoLogo", "-NoProfile", "-NonInteractive", "-EncodedCommand"), command.subList(1, 5))
        val script = String(java.util.Base64.getDecoder().decode(command.last()), Charsets.UTF_16LE)
        assertTrue(script.endsWith("\n\$PROMPT = \$env:PROMPT\nagent \"\$PROMPT\""), script)
    }

    @Test
    fun theQuestionReachesTheAgentWhole() {
        // With a real PowerShell on Windows, and sh elsewhere: the quotes, the dollar and the line break are the agent's as they were asked.
        val question = "it's \"quoted\", \$HOME\nand a second line"
        val answer = AiPrompt.ask(if (isWindows) "Write-Output \"[\$PROMPT]\"" else "printf '[%s]' \"\$PROMPT\"", question, directory, path = null)

        assertEquals(AiAnswer.Command("[it's \"quoted\", \$HOME"), answer, "The first line")
    }

    @Test
    fun theAiIsRunWithTheEnvironmentOfTheWorkspaceButItsOwnPath() {
        // One that this process has, taken out: not one that the shell needs.
        val needed = setOf("PATH", "PWD", "OLDPWD", "SHLVL", "HOME", "LANG", "COMSPEC", "PATHEXT", "SYSTEMROOT", "SYSTEMDRIVE", "WINDIR", "TEMP", "TMP")
        val inherited = System.getenv().keys.sorted().first { it.matches(Regex("[A-Z][A-Z_]*")) && it.uppercase() !in needed && !it.startsWith("PS") }
        val path = if (isWindows) "C:\\katty-nowhere" else "/usr/bin:/bin"
        val environment = EnvironmentChange(mapOf("FOO" to "bar", "PATH" to "/nowhere"), setOf(inherited))
        // On Windows the command is PowerShell's, which reads the environment through env:.
        val command = if (isWindows) {
            "Write-Output ('{0} {1} {2}' -f \$env:FOO, \$(if (Test-Path env:$inherited) { 'there' } else { 'gone' }), \$env:PATH)"
        } else {
            "printf '%s %s %s' \"\$FOO\" \"\${$inherited-gone}\" \"\$PATH\""
        }

        val answer = AiPrompt.ask(command, "?", directory, path = path, environment = environment)

        // PowerShell of the Microsoft Store puts its own folder in front of the path it is given.
        val line = (answer as AiAnswer.Command).line
        assertTrue(line.startsWith("bar gone ") && line.endsWith(path) && (isWindows || line == "bar gone $path"), line)
    }

    // --- A command asked for in plain words ---

    @Test
    fun thePromptForACommandHasTheRequestTheShellAndTheDirectory() {
        val prompt = commandRequestPrompt("  find the files over 100 MB modified this week\n", "zsh", directory)

        assertTrue("find the files over 100 MB modified this week\n" in prompt, prompt)
        assertTrue("zsh shell on $systemName" in prompt && directory.toString() in prompt, prompt)
        assertTrue("one single line" in prompt, "It says the form of the answer")
    }

    @Test
    fun aCommandWithAControlCharacterCannotBeTyped() {
        assertTrue(isTypable("find . -size +100M"))
        kotlin.test.assertFalse(isTypable("rm -rf build\u001b[A"), "An escape is a key for the shell")
        kotlin.test.assertFalse(isTypable("  "))
    }
}
