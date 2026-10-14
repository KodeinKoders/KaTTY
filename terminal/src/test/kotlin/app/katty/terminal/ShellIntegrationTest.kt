package app.katty.terminal

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ShellIntegrationTest {
    private val sessions = mutableListOf<TerminalSession>()
    private val collectors = mutableListOf<Job>()

    @AfterTest
    fun close() {
        collectors.forEach { it.cancel() }
        sessions.forEach { it.close() }
    }

    // A home with nothing in it but an empty .zshrc, which spares zsh its first-run questions: the user's own files are not read.
    private fun emptyHome(): Path = Files.createTempDirectory("katty-home").also {
        Files.createFile(it.resolve(".zshrc"))
        it.toFile().deleteOnExit()
    }

    private fun start(shell: String, home: Path, directory: Path, shellIntegration: Boolean = true): TerminalSession =
        TerminalSession.start(
            80, 10,
            command = listOf(shell, "-l"),
            environment = mapOf("HOME" to home.toString(), "BASH_SILENCE_DEPRECATION_WARNING" to "1", "PS1" to "$ "),
            directory = directory.toString(),
            shellIntegration = shellIntegration,
        ).also { sessions += it }

    private fun TerminalSession.collectEvents(): List<TerminalEvent> {
        val events = CopyOnWriteArrayList<TerminalEvent>()
        collectors += CoroutineScope(Dispatchers.Unconfined).launch { this@collectEvents.events.collect { events += it } }
        return events
    }

    private fun await(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TIMEOUT_MS * 1_000_000
        while (!condition()) {
            check(System.nanoTime() < deadline) { "Still waiting for $what" }
            Thread.sleep(10)
        }
    }

    private fun reportsItsDirectoryAndItsCommands(shell: String) {
        val directory = Files.createTempDirectory("katty-start").also { it.toFile().deleteOnExit() }
        val session = start(shell, emptyHome(), directory)

        // The first prompt reports where the shell started, as the system names it.
        await("the directory of the first prompt") { session.workingDirectory.value == directory.toRealPath().toString() }

        val events = session.collectEvents()
        session.sendText("cd /tmp\r")
        await("the directory to follow the shell") { session.workingDirectory.value == "/tmp" }

        session.sendText("false\r")
        await("the end of the command") { TerminalEvent.CommandFinished(1) in events }
        await("the next prompt") { events.lastOrNull() == TerminalEvent.PromptStarted }
        assertTrue(
            events.indexOf(TerminalEvent.CommandStarted(null)) < events.indexOf(TerminalEvent.CommandFinished(1)),
            "The command starts before it ends: $events",
        )
    }

    private fun givesTheOutputOfTheLastCommand(shell: String) {
        val directory = Files.createTempDirectory("katty-start").also { it.toFile().deleteOnExit() }
        val session = start(shell, emptyHome(), directory)
        await("the first prompt") { session.workingDirectory.value != null }
        val events = session.collectEvents()
        assertEquals(null, session.lastCommandOutput(), "No command yet")

        session.sendText("printf 'a\\nb\\n'\r")
        await("the end of the first command") { events.count { it is TerminalEvent.PromptStarted } >= 1 }
        assertEquals("a\nb", session.lastCommandOutput())

        session.sendText("echo second\r")
        await("the end of the second command") { events.count { it is TerminalEvent.PromptStarted } >= 2 }
        assertEquals("second", session.lastCommandOutput(), "The last one, not the first")
    }

    @Test
    fun zshGivesTheOutputOfTheLastCommand() = givesTheOutputOfTheLastCommand("/bin/zsh")

    @Test
    fun bashGivesTheOutputOfTheLastCommand() = givesTheOutputOfTheLastCommand("/bin/bash")

    @Test
    fun zshReportsItsDirectoryAndItsCommands() = reportsItsDirectoryAndItsCommands("/bin/zsh")

    @Test
    fun bashReportsItsDirectoryAndItsCommands() = reportsItsDirectoryAndItsCommands("/bin/bash")

    @Test
    fun theUsersOwnStartupFilesAreStillRead() {
        val home = emptyHome()
        Files.writeString(home.resolve(".zshenv"), "export FROM_ZSHENV=env\n")
        Files.writeString(home.resolve(".zshrc"), "export FROM_ZSHRC=rc\nPS1='mine> '\n")
        Files.writeString(home.resolve(".bash_profile"), "export FROM_PROFILE=profile\nPS1='mine> '\n")
        for (shell in listOf("/bin/zsh", "/bin/bash")) {
            val session = start(shell, home, home)
            await("the prompt of $shell") { session.workingDirectory.value != null }

            session.sendText("echo \"[\$FROM_ZSHENV\$FROM_ZSHRC\$FROM_PROFILE]\"\r")

            val expected = if (shell.endsWith("zsh")) "[envrc]" else "[profile]"
            await("the user's files to be read by $shell") {
                val frame = session.frames.value
                frame.rows.indices.any { row -> frame.rows[row].cells.joinToString("") { it.text }.trim() == expected }
            }
        }
    }

    @Test
    fun nothingIsReportedWithoutTheIntegration() {
        val directory = Files.createTempDirectory("katty-start").also { it.toFile().deleteOnExit() }
        val session = start("/bin/zsh", emptyHome(), directory, shellIntegration = false)
        val events = session.collectEvents()

        session.sendText("echo marker-\$((1+1))\r")

        await("the output of the command") {
            val frame = session.frames.value
            frame.rows.any { row -> row.cells.joinToString("") { it.text }.trim() == "marker-2" }
        }
        assertEquals(emptyList(), events.toList())
        assertEquals(null, session.workingDirectory.value)
    }

    @Test
    fun otherShellsAndCommandsAreLeftAsTheyAre() {
        val environment = mutableMapOf<String, String>()
        assertEquals(listOf("/bin/sh", "-l"), ShellIntegration.apply(listOf("/bin/sh", "-l"), environment))
        assertEquals(listOf("/bin/zsh", "-c", "ls"), ShellIntegration.apply(listOf("/bin/zsh", "-c", "ls"), environment))
        assertEquals(listOf("/bin/bash", "script.sh"), ShellIntegration.apply(listOf("/bin/bash", "script.sh"), environment))
        assertEquals(emptyMap(), environment)
    }

    private companion object {
        const val TIMEOUT_MS = 15_000L
    }
}
