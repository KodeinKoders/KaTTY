package app.katty.core

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentDetectionTest {
    private val root: Path = Files.createTempDirectory("katty-detect")

    @AfterTest
    fun cleanUp() {
        root.toFile().deleteRecursively()
    }

    private fun program(directory: String, name: String, executable: Boolean = true): Path {
        val file = root.resolve(directory).createDirectories().resolve(name)
        file.writeText("#!/bin/sh\n")
        file.toFile().setExecutable(executable)
        return file
    }

    private fun path(vararg directories: String) = directories.joinToString(File.pathSeparator) { root.resolve(it).toString() }

    @Test
    fun findsTheAgentsThatAreInstalled() {
        val claude = program("bin", "claude")
        val copilot = program("bin", "copilot")

        val found = AgentDetection.detect(path("bin"))

        assertEquals(mapOf(Agent.CLAUDE to claude, Agent.COPILOT to copilot), found)
    }

    @Test
    fun anAgentThatIsNotThereIsNotFound() {
        program("bin", "ls")

        assertEquals(emptyMap(), AgentDetection.detect(path("bin")))
    }

    @Test
    fun aFileThatCannotBeRunIsNotAnAgent() {
        program("bin", "codex", executable = false)

        assertEquals(emptyMap(), AgentDetection.detect(path("bin")))
    }

    @Test
    fun aFolderIsNotAnAgent() {
        root.resolve("bin/copilot").createDirectories()

        assertEquals(emptyMap(), AgentDetection.detect(path("bin")))
    }

    @Test
    fun theFolderThatComesFirstWins() {
        val first = program("first", "claude")
        program("second", "claude")

        assertEquals(first, AgentDetection.detect(path("first", "second"))[Agent.CLAUDE])
    }

    @Test
    fun antigravityIsTheProgramCalledAgy() {
        val agy = program("bin", "agy")

        assertEquals(agy, AgentDetection.detect(path("bin"))[Agent.ANTIGRAVITY])
    }

    @Test
    fun foldersThatDoNotExistAreSkipped() {
        val claude = program("bin", "claude")

        assertEquals(mapOf(Agent.CLAUDE to claude), AgentDetection.detect(path("missing", "bin") + File.pathSeparator + File.pathSeparator))
    }

    @Test
    fun readsThePathOfALoginShell() {
        // A shell that runs what it is given (`-l -c <script>`), with the PATH of a profile: as a real one does.
        val shell = root.resolve("fake-shell").also { it.writeText("#!/bin/sh\nPATH=/opt/one:/opt/two:\$PATH exec /bin/sh -c \"\$3\"\n"); it.toFile().setExecutable(true) }

        assertEquals("/opt/one:/opt/two", AgentDetection.loginShellPath(shell.toString())?.split(':')?.take(2)?.joinToString(":"))
    }

    @Test
    fun whatTheProfilePrintsIsNotPartOfThePath() {
        // A greeting, a fortune, a version manager's notice: before and after what the command prints.
        val shell = root.resolve("chatty-shell").also {
            it.writeText("#!/bin/sh\necho Welcome back\nPATH=/opt/one:/opt/two /bin/sh -c \"\$3\"\necho bye\n")
            it.toFile().setExecutable(true)
        }

        assertEquals("/opt/one:/opt/two", AgentDetection.loginShellPath(shell.toString()))
    }

    @Test
    fun aProfileThatStartsABackgroundProgramDoesNotHoldTheReading() {
        // ssh-agent, keychain, gpg-agent: a program that the profile starts and that keeps the output open.
        val shell = root.resolve("agent-shell").also {
            it.writeText("#!/bin/sh\nsleep 20 &\nPATH=/opt/one:/opt/two /bin/sh -c \"\$3\"\n")
            it.toFile().setExecutable(true)
        }

        val started = System.nanoTime()
        val path = AgentDetection.loginShellPath(shell.toString(), timeoutMs = 3_000)
        val tookMs = (System.nanoTime() - started) / 1_000_000

        assertEquals("/opt/one:/opt/two", path)
        assertTrue(tookMs < 3_000, "It took ${tookMs}ms")
    }

    @Test
    fun aShellThatFailsOrIsNotThereHasNoPath() {
        val failing = root.resolve("failing-shell").also { it.writeText("#!/bin/sh\nexit 3\n"); it.toFile().setExecutable(true) }

        assertNull(AgentDetection.loginShellPath(failing.toString()))
        assertNull(AgentDetection.loginShellPath("/nonexistent/shell"))
    }

    @Test
    fun theRealLoginShellHasAPath() {
        assertNotNull(AgentDetection.loginShellPath())
        assertTrue(AgentDetection.loginShellPath()!!.contains("/"))
    }
}
