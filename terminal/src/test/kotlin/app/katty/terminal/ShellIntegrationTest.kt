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

    private fun selectsTheOutputOfTheLastCommand(shell: String) {
        val directory = Files.createTempDirectory("katty-start").also { it.toFile().deleteOnExit() }
        val session = start(shell, emptyHome(), directory)
        await("the first prompt") { session.workingDirectory.value != null }
        val events = session.collectEvents()
        assertEquals(false, session.selectLastCommandOutput(), "No command yet")
        assertEquals(null, session.selectedText())

        session.sendText("printf 'a\\nb\\n'\r")
        await("the end of the command") { events.count { it is TerminalEvent.PromptStarted } >= 1 }

        // The shell goes on drawing its prompt, which unselects what it writes over: the user selects when it is done, so it is tried until it holds.
        await("the output to be selected and shown") {
            session.selectLastCommandOutput() && session.selectedText() == "a\nb" && session.frames.value.selection.any { it != null }
        }
    }

    private fun movesTheCursorOfTheCommandLine(shell: String) {
        val directory = Files.createTempDirectory("katty-start").also { it.toFile().deleteOnExit() }
        val session = start(shell, emptyHome(), directory)
        await("the first prompt") { session.workingDirectory.value != null }
        // The prompt is still being drawn at first, so the frame is where the cursor was: asked where it is, nothing is sent.
        await("the cursor to be where the frame has it") {
            val cursor = session.frames.value.cursor
            cursor.x != null && cursor.x!! > 0 && !session.moveCursorTo(cursor.x!!, cursor.y!!)
        }

        session.sendText("echo hello")
        await("what is typed to be on the screen") { session.frames.value.rows.any { row -> "echo hello" in row.cells.joinToString("") { it.text } } }
        val frame = session.frames.value
        val row = frame.cursor.y!!
        val line = frame.rows[row].cells.joinToString("") { it.text.ifEmpty { " " } }
        val column = line.indexOf("hello")

        assertTrue(session.moveCursorTo(column, row), "The cursor goes to the h")
        session.sendText("X\r")
        await("what is typed after the click to be in the line") { session.frames.value.rows.any { r -> "Xhello" in r.cells.joinToString("") { it.text } } }
        assertEquals(false, session.moveCursorTo(0, row + 1), "Not on the row of the cursor")
    }

    @Test
    fun zshMovesTheCursorOfTheCommandLineToAClick() = movesTheCursorOfTheCommandLine("/bin/zsh")

    @Test
    fun bashMovesTheCursorOfTheCommandLineToAClick() = movesTheCursorOfTheCommandLine("/bin/bash")

    @Test
    fun zshSelectsTheOutputOfTheLastCommand() = selectsTheOutputOfTheLastCommand("/bin/zsh")

    @Test
    fun bashSelectsTheOutputOfTheLastCommand() = selectsTheOutputOfTheLastCommand("/bin/bash")

    @Test
    fun zshGivesTheOutputOfTheLastCommand() = givesTheOutputOfTheLastCommand("/bin/zsh")

    @Test
    fun bashGivesTheOutputOfTheLastCommand() = givesTheOutputOfTheLastCommand("/bin/bash")

    private fun theCursorIsBackAtThePromptAfterACommandThatChangedIt(shell: String) {
        val directory = Files.createTempDirectory("katty-start").also { it.toFile().deleteOnExit() }
        val session = start(shell, emptyHome(), directory)
        await("the first prompt") { session.workingDirectory.value != null }
        val events = session.collectEvents()

        // A program that leaves a bar and no cursor, as one that crashes does.
        session.sendText("printf '\\033[5 q\\033[?25l'\r")
        await("the end of the command") { events.count { it is TerminalEvent.PromptStarted } >= 1 }

        // The cursor of the prompt, which is what it was before the command.
        val frame = session.frames.value
        await("the cursor to be back") {
            val cursor = session.frames.value.cursor
            cursor.visible && cursor.shape == TerminalCursorShape.BLOCK
        }
        assertTrue(frame.rows.isNotEmpty())
    }

    @Test
    fun zshGivesTheCursorBackAtThePrompt() = theCursorIsBackAtThePromptAfterACommandThatChangedIt("/bin/zsh")

    @Test
    fun bashGivesTheCursorBackAtThePrompt() = theCursorIsBackAtThePromptAfterACommandThatChangedIt("/bin/bash")

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

    @Test
    fun fishIsStartedWithTheDirectoryOfTheIntegrationInFrontOfTheDataDirectories() {
        val environment = mutableMapOf("XDG_DATA_DIRS" to "/opt/share:/usr/share")

        val command = ShellIntegration.apply(listOf("/opt/homebrew/bin/fish", "-l"), environment)

        // The command is the same: fish reads the directory by itself.
        assertEquals(listOf("/opt/homebrew/bin/fish", "-l"), command)
        val root = environment.getValue("KATTY_SHELL_INTEGRATION_XDG_DIR")
        assertEquals("$root:/opt/share:/usr/share", environment["XDG_DATA_DIRS"])
        val script = Path.of(root, "fish", "vendor_conf.d", "katty-shell-integration.fish")
        assertTrue(Files.isRegularFile(script), "fish reads the files of vendor_conf.d of the directories in XDG_DATA_DIRS")
        val text = Files.readString(script)
        for (mark in listOf("133;A", "133;C", "133;D", "]7;file://")) assertTrue(mark in text, "The script says $mark")
    }

    @Test
    fun fishGetsTheDefaultDataDirectoriesBehindOursWhenThereAreNone() {
        val environment = mutableMapOf<String, String>()

        ShellIntegration.apply(listOf("fish", "-l"), environment)

        assertEquals("${environment.getValue("KATTY_SHELL_INTEGRATION_XDG_DIR")}:/usr/local/share:/usr/share", environment["XDG_DATA_DIRS"])
    }

    @Test
    fun fishThatIsNotStartedToBeTypedInIsLeftAsItIs() {
        val environment = mutableMapOf<String, String>()

        assertEquals(listOf("fish", "-c", "echo"), ShellIntegration.apply(listOf("fish", "-c", "echo"), environment))
        assertEquals(emptyMap(), environment)
    }

    // fish is not on every machine: the tests that run it are skipped where it is not.
    private fun fish(): String? = listOf("/opt/homebrew/bin/fish", "/usr/local/bin/fish", "/usr/bin/fish", "/bin/fish").firstOrNull { java.io.File(it).canExecute() }

    @Test
    fun fishReportsItsDirectoryAndItsCommands() {
        val fish = fish()
        org.junit.Assume.assumeTrue("fish is not installed", fish != null)
        val directory = Files.createTempDirectory("katty-start").also { it.toFile().deleteOnExit() }
        val session = start(fish!!, emptyHome(), directory)

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

    @Test
    fun fishDoesNotLeaveTheDirectoryOfTheIntegrationInWhatItsProgramsSee() {
        val fish = fish()
        org.junit.Assume.assumeTrue("fish is not installed", fish != null)
        val directory = Files.createTempDirectory("katty-start").also { it.toFile().deleteOnExit() }
        val session = start(fish!!, emptyHome(), directory)
        await("the first prompt") { session.workingDirectory.value != null }

        session.sendText("echo \"[\$KATTY_SHELL_INTEGRATION_XDG_DIR][\$XDG_DATA_DIRS]\"\r")

        await("the output") {
            session.frames.value.rows.any { row -> row.cells.joinToString("") { it.text }.trim().startsWith("[][") }
        }
        val line = session.frames.value.rows.map { r -> r.cells.joinToString("") { it.text }.trim() }.first { it.startsWith("[][") }
        assertTrue("katty-shell-integration" !in line && "katty" !in line.substringAfter("]["), line)
    }

    private companion object {
        const val TIMEOUT_MS = 15_000L
    }
}
