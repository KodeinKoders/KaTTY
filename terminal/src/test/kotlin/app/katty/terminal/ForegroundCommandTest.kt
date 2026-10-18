package app.katty.terminal

import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ForegroundCommandTest {
    private val sessions = mutableListOf<TerminalSession>()

    @AfterTest
    fun close() {
        sessions.forEach { it.close() }
    }

    private val startDirectory = Files.createTempDirectory("katty-fg").also { it.toFile().deleteOnExit() }

    // An interactive zsh, with no startup files: it has job control, as the user's shell has.
    private fun shell(): TerminalSession =
        TerminalSession.start(
            80, 10,
            command = listOf("/bin/zsh", "-f", "-i"),
            directory = startDirectory.toString(),
            shellIntegration = false,
        ).also { sessions += it }

    private fun TerminalSession.awaitCommand(what: String, condition: (ForegroundCommand) -> Boolean = { true }): ForegroundCommand {
        val deadline = System.nanoTime() + TIMEOUT_MS * 1_000_000
        while (true) {
            foregroundCommand()?.takeIf(condition)?.let { return it }
            check(System.nanoTime() < deadline) { "Still waiting for $what. The screen:\n${frames.value.rows.joinToString("\n") { r -> r.cells.joinToString("") { it.text.ifEmpty { " " } }.trimEnd() }}" }
            Thread.sleep(20)
        }
    }

    @Test
    fun isNothingAtThePromptOfTheShell() {
        val session = shell()
        val deadline = System.nanoTime() + TIMEOUT_MS * 1_000_000
        while (session.foregroundProcessName() != "zsh") {
            check(System.nanoTime() < deadline) { "Still waiting for the shell" }
            Thread.sleep(20)
        }

        assertNull(session.foregroundCommand())
    }

    @Test
    fun hasTheCommandLineAndTheStartOfTheProgramThatTheShellRuns() {
        val session = shell()
        val before = System.currentTimeMillis() - 2000
        session.sendText("sleep 30\r")

        val command = session.awaitCommand("sleep") { it.argv.firstOrNull() == "sleep" }

        assertEquals(listOf("sleep", "30"), command.argv)
        assertEquals(startDirectory.toRealPath().toString(), command.directory)
        assertTrue(command.startedAtMillis in before..System.currentTimeMillis() + 2000, "It started just now: ${command.startedAtMillis}")
        assertFalse(command.pipeline)
    }

    @Test
    fun hasTheDirectoryOfTheProgramAndNotTheOneOfTheShell() {
        val session = shell()
        session.sendText("sh -c 'cd /tmp && exec sleep 31'\r")

        val command = session.awaitCommand("sleep") { it.argv == listOf("sleep", "31") }

        assertEquals("/private/tmp", command.directory)
    }

    @Test
    fun keepsTheArgumentsThatHoldSpacesAndQuotes() {
        val session = shell()
        session.sendText("""sh -c 'sleep 32; :' "an argument" it\'s""" + "\r")

        val command = session.awaitCommand("sh") { it.argv.firstOrNull() == "sh" }

        assertEquals(listOf("sh", "-c", "sleep 32; :", "an argument", "it's"), command.argv)
    }

    @Test
    fun isAPipelineWhenTheShellStartedSeveralProgramsInOneGroup() {
        val session = shell()
        session.sendText("sleep 33 | cat\r")

        val command = session.awaitCommand("the pipeline") { it.argv.firstOrNull() == "sleep" }

        assertTrue(command.pipeline)
    }

    @Test
    fun isNotAPipelineBecauseTheProgramRunsOthersItself() {
        val session = shell()
        session.sendText("sh -c 'sleep 34; :'\r")
        session.awaitCommand("sh") { it.argv.firstOrNull() == "sh" }
        // Its child is in the group, and is the child of the program and not of the shell.
        session.awaitCommand("the child to exist") { true }
        Thread.sleep(300)

        assertFalse(session.foregroundCommand()!!.pipeline)
    }

    @Test
    fun readsTheArgumentsOfABuffer() {
        val buffer = byteArrayOf(2, 0, 0, 0) + "/bin/echo\u0000\u0000\u0000".encodeToByteArray() + "echo\u0000hi there\u0000HOME=/x\u0000".encodeToByteArray()

        assertEquals(listOf("echo", "hi there"), parseProcessArguments(buffer))
    }

    private companion object {
        const val TIMEOUT_MS = 15_000L
    }
}
