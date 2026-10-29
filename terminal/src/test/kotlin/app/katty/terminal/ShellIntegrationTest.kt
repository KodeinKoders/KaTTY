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

    private fun start(shell: String, home: Path, directory: Path, shellIntegration: Boolean = true, scripts: Path? = null): TerminalSession =
        TerminalSession.start(
            80, 10,
            command = listOf(shell, "-l"),
            environment = mapOf("HOME" to home.toString(), "BASH_SILENCE_DEPRECATION_WARNING" to "1", "PS1" to "$ "),
            directory = directory.toString(),
            shellIntegration = shellIntegration,
            shellIntegrationDirectory = scripts,
        ).also { sessions += it }

    // A directory for the scripts with spaces in its path, as the application's own has (`Application Support`).
    private fun scriptsDirectory(): Path =
        Files.createTempDirectory("katty support").also { it.toFile().deleteOnExit() }.resolve("Application Support/KaTTY/shell-integration")

    private fun TerminalSession.screen(): String = frames.value.rows.joinToString("\n") { row -> row.cells.joinToString("") { it.text.ifEmpty { " " } } }

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

    private fun reportsItsDirectoryAndItsCommands(shell: String, home: Path = emptyHome()) {
        val directory = Files.createTempDirectory("katty-start").also { it.toFile().deleteOnExit() }
        val session = start(shell, home, directory)

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

    @Test
    fun theScriptsAreWrittenAgainWhenTheirDirectoryIsGone() {
        val scripts = scriptsDirectory()
        val home = emptyHome().also { Files.writeString(it.resolve(".zshrc"), "echo user-zshrc\n") }
        val directory = Files.createTempDirectory("katty-start").also { it.toFile().deleteOnExit() }
        repeat(2) { time ->
            // The second time, the system cleaned the directory: zsh pointed to a directory that is gone reads none of the user's files.
            if (time == 1) scripts.toFile().deleteRecursively()
            val session = start("/bin/zsh", home, directory, scripts = scripts)
            await("the user's .zshrc, start ${time + 1}") { "user-zshrc" in session.screen() }
            await("the directory of the first prompt, start ${time + 1}") { session.workingDirectory.value == directory.toRealPath().toString() }
        }
    }

    @Test
    fun bashWithADebugTrapOfItsOwnStillMarksItsCommands() {
        // atuin, starship and others set a DEBUG trap: it is the one place that bash says a command starts.
        val home = emptyHome().also { Files.writeString(it.resolve(".bash_profile"), "trap 'true' DEBUG\n") }

        reportsItsDirectoryAndItsCommands("/bin/bash", home)
    }

    @Test
    fun aBashLoginShellLogsOutAndReadsItsLogoutFile() {
        val home = emptyHome().also { Files.writeString(it.resolve(".bash_logout"), "echo bye > \"\$HOME/logged-out\"\n") }
        val directory = Files.createTempDirectory("katty-start").also { it.toFile().deleteOnExit() }
        val session = start("/bin/bash", home, directory)
        await("the first prompt") { session.workingDirectory.value == directory.toRealPath().toString() }

        // bash does not take itself for a login shell (KaTTY starts it with --rcfile): `logout` said "not login shell".
        session.sendText("logout\r")

        await("the shell to end") { session.exited.value != null }
        await("the logout file to be read") { Files.exists(home.resolve("logged-out")) }
    }

    @Test
    fun bashReportsFromADirectoryWithSpacesInItsPath() {
        val directory = Files.createTempDirectory("katty-start").also { it.toFile().deleteOnExit() }
        val session = start("/bin/bash", emptyHome(), directory, scripts = scriptsDirectory())

        await("the directory of the first prompt") { session.workingDirectory.value == directory.toRealPath().toString() }
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

    private fun movesTheCursorOfTheCommandLine(shell: String, typed: String = "echo hello") {
        val directory = Files.createTempDirectory("katty-start").also { it.toFile().deleteOnExit() }
        val session = start(shell, emptyHome(), directory)
        await("the first prompt") { session.workingDirectory.value != null }
        // The prompt is still being drawn at first, so the frame is where the cursor was: asked where it is, nothing is sent.
        await("the cursor to be where the frame has it") {
            val cursor = session.frames.value.cursor
            cursor.x != null && cursor.x!! > 0 && !session.moveCursorTo(cursor.x!!, cursor.y!!)
        }

        session.sendText(typed)
        await("what is typed to be on the screen") { session.frames.value.rows.any { row -> typed in row.cells.joinToString("") { it.text } } }
        // The line editor may draw the line again (it does after a wide character): the cursor is at the end of it, and stays there.
        fun atEnd(): Boolean {
            val shown = session.frames.value
            val cursorRow = shown.cursor.y ?: return false
            val text = shown.rows[cursorRow].cells.joinToString("") { it.text.ifEmpty { " " } }
            return text.indexOf("hello").let { it >= 0 && shown.cursor.x == it + "hello".length }
        }
        await("the cursor to be at the end of the line") { atEnd() }
        Thread.sleep(300)
        await("the cursor to stay there") { atEnd() }
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

    // A wide character is two cells and one press of an arrow. The line is short enough not to wrap after a prompt as long as the host's name.
    @Test
    fun zshMovesTheCursorOverWideCharacters() = movesTheCursorOfTheCommandLine("/bin/zsh", "echo 中 hello")

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

    // The shell puts the user's cursor back at the next prompt: a program that ended badly left its own (a bar, or none).
    private fun theCursorIsBackAtThePromptAfterACommandThatChangedIt(shell: String) {
        val directory = Files.createTempDirectory("katty-start").also { it.toFile().deleteOnExit() }
        val session = start(shell, emptyHome(), directory)
        await("the first prompt") { session.workingDirectory.value != null }
        val events = session.collectEvents()

        session.sendText("printf '\\033[5 q\\033[?25l'\r")
        await("the end of the command") { events.count { it is TerminalEvent.PromptStarted } >= 1 }

        // The cursor of the prompt, which is the default one that the terminal has, a block.
        await("the cursor to be back") {
            val cursor = session.frames.value.cursor
            cursor.visible && cursor.shape == TerminalCursorShape.BLOCK
        }
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
        assertEquals(listOf("/bin/sh", "-l"), ShellIntegration.apply(listOf("/bin/sh", "-l"), environment).argv)
        assertEquals(listOf("/bin/zsh", "-c", "ls"), ShellIntegration.apply(listOf("/bin/zsh", "-c", "ls"), environment).argv)
        assertEquals(listOf("/bin/bash", "script.sh"), ShellIntegration.apply(listOf("/bin/bash", "script.sh"), environment).argv)
        assertEquals(emptyMap(), environment)
    }

    @Test
    fun fishIsStartedWithTheDirectoryOfTheIntegrationInFrontOfTheDataDirectories() {
        val environment = mutableMapOf("XDG_DATA_DIRS" to "/opt/share:/usr/share")

        val command = ShellIntegration.apply(listOf("/opt/homebrew/bin/fish", "-l"), environment).argv

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
        // The script takes the variable away, rather than leave the defaults that were made up for it.
        assertEquals("1", environment["KATTY_SHELL_INTEGRATION_XDG_UNSET"])
    }

    @Test
    fun fishThatHadDataDirectoriesIsNotToldToEraseThem() {
        // Inherited from a KaTTY that was started in a KaTTY: it is not this shell's.
        val environment = mutableMapOf("XDG_DATA_DIRS" to "/opt/share", "KATTY_SHELL_INTEGRATION_XDG_UNSET" to "1")

        ShellIntegration.apply(listOf("fish", "-l"), environment)

        assertEquals(null, environment["KATTY_SHELL_INTEGRATION_XDG_UNSET"])
    }

    @Test
    fun fishThatHadNoDataDirectoriesHasNoneForItsPrograms() {
        val fish = fish()
        org.junit.Assume.assumeTrue("fish is not installed", fish != null)
        org.junit.Assume.assumeTrue("XDG_DATA_DIRS is set for the tests", System.getenv("XDG_DATA_DIRS").isNullOrEmpty())
        val directory = Files.createTempDirectory("katty-start").also { it.toFile().deleteOnExit() }
        val session = start(fish!!, emptyHome(), directory)
        await("the first prompt") { session.workingDirectory.value != null }

        session.sendText("set -q XDG_DATA_DIRS; and echo is-set; or echo is-unset\r")

        await("the output") { "is-unset" in session.screen() }
        assertTrue("is-set" !in session.screen().replace("is-set; or", ""), session.screen())
    }

    @Test
    fun fishThatIsNotStartedToBeTypedInIsLeftAsItIs() {
        val environment = mutableMapOf<String, String>()

        assertEquals(listOf("fish", "-c", "echo"), ShellIntegration.apply(listOf("fish", "-c", "echo"), environment).argv)
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

    @Test
    fun clearingTheScreenAtThePromptDrawsThePromptAndWhatWasTypedAgainAtTheTop() {
        val directory = Files.createTempDirectory("katty-start").also { it.toFile().deleteOnExit() }
        val session = start("/bin/zsh", emptyHome(), directory)
        await("the first prompt") { session.workingDirectory.value != null }
        val events = session.collectEvents()
        session.sendText("printf 'one\\ntwo\\n'\r")
        await("the output and the next prompt") { events.count { it is TerminalEvent.PromptStarted } >= 1 && screen(session).any { it == "two" } }
        // Typed and not run: it is on the line when the screen is cleared, and stays there.
        session.sendText("echo partial")
        await("the text typed") { screen(session).any { it.endsWith("echo partial") } }

        session.clearScreen()

        await("the prompt at the top, with what was typed, and nothing else") {
            val rows = screen(session)
            rows[0].endsWith("echo partial") && rows.drop(1).all { it.isEmpty() }
        }
        assertEquals(null, session.frames.value.scrollbar, "The history is gone too")
    }

    private fun screen(session: TerminalSession): List<String> =
        session.frames.value.rows.map { row -> row.cells.joinToString("") { it.text.ifEmpty { " " } }.trimEnd() }

    private companion object {
        const val TIMEOUT_MS = 15_000L
    }
}
