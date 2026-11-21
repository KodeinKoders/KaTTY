package app.katty.terminal

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.junit.Assume.assumeTrue
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The shell integration of PowerShell. The tests that run a shell run every PowerShell that the machine has (`pwsh`, and Windows PowerShell on
 * Windows), and are skipped when it has none, as a Mac without pwsh.
 */
class PowerShellIntegrationTest {
    private val sessions = mutableListOf<TerminalSession>()
    private val collectors = mutableListOf<Job>()

    @AfterTest
    fun close() {
        collectors.forEach { it.cancel() }
        sessions.forEach { it.close() }
    }

    @Test
    fun powerShellIsKnownWhateverItsCaseAndItsExtension() {
        assertEquals("pwsh", ShellIntegration.shellOf(listOf("pwsh.exe", "-NoLogo")))
        assertEquals("pwsh", ShellIntegration.shellOf(listOf("/usr/local/bin/pwsh", "-Login")))
        assertEquals("powershell", ShellIntegration.shellOf(listOf("PowerShell.EXE", "-nologo")))
        assertEquals("powershell", ShellIntegration.shellOf(listOf("powershell")))
        assertNull(ShellIntegration.shellOf(listOf("pwsh.exe", "-Command", "ls")), "A command runs as it is")
        assertNull(ShellIntegration.shellOf(listOf("pwsh.exe", "-l", "-i")), "-i is not an option of PowerShell")
    }

    @Test
    fun theScriptIsACommandThatLeavesTheShellOpen() {
        val environment = mutableMapOf<String, String>()
        val applied = ShellIntegration.apply(listOf("pwsh.exe", "-NoLogo"), environment)

        assertTrue(applied.installed)
        assertEquals(listOf("pwsh.exe", "-NoLogo", "-NoExit", "-EncodedCommand"), applied.argv.dropLast(1))
        val script = String(Base64.getDecoder().decode(applied.argv.last()), Charsets.UTF_16LE)
        val resource = javaClass.getResourceAsStream("/app/katty/terminal/shell-integration/pwsh/katty.ps1")!!.use { it.readBytes().decodeToString() }
        assertEquals(resource, script)
        assertTrue(environment.isEmpty(), "Nothing of the environment changes: $environment")
    }

    @Test
    fun aCommandLineIsQuotedForPowerShell() {
        assertEquals("vim notes.txt C:\\Users\\me\\a.txt", shellLine(listOf("vim", "notes.txt", "C:\\Users\\me\\a.txt"), "pwsh"))
        assertEquals("echo 'a b' '' '\$HOME' 'it''s' 'a,b' '@x' 'a`b'", shellLine(listOf("echo", "a b", "", "\$HOME", "it's", "a,b", "@x", "a`b"), "pwsh"))
        assertEquals("echo 'it\u2019\u2019s'", shellLine(listOf("echo", "it\u2019s"), "powershell"), "A typographic quote is a quote of PowerShell")
        assertEquals("& 'C:\\Program Files\\x.exe' -v", shellLine(listOf("C:\\Program Files\\x.exe", "-v"), "pwsh"), "A quoted string alone is a value")
        assertNull(shellLine(listOf("echo", "a\rb"), "pwsh"))
    }

    // The PowerShells of this machine: pwsh where it is on the PATH (the Microsoft Store's is an alias of the system, which File.exists does
    // not see), and Windows PowerShell on Windows.
    private fun powerShells(): List<String> {
        val pwsh = System.getenv("PATH").orEmpty().split(File.pathSeparatorChar).filter { it.isNotBlank() }
            .map { Path.of(it, if (isWindows) "pwsh.exe" else "pwsh") }.firstOrNull { Files.exists(it, LinkOption.NOFOLLOW_LINKS) }
        return listOfNotNull(pwsh?.toString(), if (isWindows) "powershell.exe" else null)
    }

    private fun start(shell: String, directory: Path, typeAtPrompt: TypedCommand? = null): TerminalSession =
        TerminalSession.start(100, 20, command = listOf(shell, "-NoLogo"), directory = directory.toString(), typeAtPrompt = typeAtPrompt)
            .also { sessions += it }

    private fun TerminalSession.screen(): String = frames.value.rows.joinToString("\n") { row -> row.cells.joinToString("") { it.text.ifEmpty { " " } } }

    private fun TerminalSession.collectEvents(): MutableList<TerminalEvent> {
        val events = CopyOnWriteArrayList<TerminalEvent>()
        collectors += CoroutineScope(Dispatchers.Unconfined).launch { this@collectEvents.events.collect { events += it } }
        return events
    }

    private fun TerminalSession.await(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TIMEOUT_MS * 1_000_000
        while (!condition()) {
            check(System.nanoTime() < deadline) { "Still waiting for $what, the screen is:\n${screen()}" }
            Thread.sleep(10)
        }
    }

    private fun directory(name: String): Path = Files.createTempDirectory(name).also { it.toFile().deleteOnExit() }.toRealPath()

    @Test
    fun powerShellReportsItsDirectoryAndItsCommands() {
        val shells = powerShells()
        assumeTrue("No PowerShell is installed", shells.isNotEmpty())
        for (shell in shells) {
            val start = directory("katty start é")
            val other = directory("katty other")
            val session = start(shell, start)
            val events = session.collectEvents()

            // The first prompt says where the shell started, a name that is not ASCII included.
            session.await("$shell to report the directory it started in") { session.workingDirectory.value == start.toString() }

            session.sendText("cd '$other'\r")
            session.await("$shell to report the directory it went to") { session.workingDirectory.value == other.toString() }
            session.await("$shell to report the end of cd") { TerminalEvent.CommandFinished(0) in events }

            // A cmdlet that fails ends with 1, a program with its own code.
            events.clear()
            session.sendText("Get-Item no-such-file\r")
            session.await("$shell to report a cmdlet that failed") { TerminalEvent.CommandFinished(1) in events }
            session.await("the next prompt of $shell") { events.lastOrNull() == TerminalEvent.PromptStarted }
            assertTrue(events.indexOf(TerminalEvent.CommandStarted(null)) in 0..<events.indexOf(TerminalEvent.CommandFinished(1)), "$shell: $events")
            val exit = if (isWindows) "cmd.exe /c exit 3" else "sh -c 'exit 3'"
            session.sendText("$exit\r")
            session.await("$shell to report the code of a program") { TerminalEvent.CommandFinished(3) in events }

            // Return on an empty line runs nothing: a prompt and no command.
            session.await("the prompt after the program of $shell") { events.lastOrNull() == TerminalEvent.PromptStarted }
            events.clear()
            session.sendText("\r")
            session.await("the prompt after an empty line in $shell") { TerminalEvent.PromptStarted in events }
            Thread.sleep(300)
            assertFalse(events.any { it is TerminalEvent.CommandStarted || it is TerminalEvent.CommandFinished }, "$shell: $events")
            assertTrue(session.atPrompt(), "$shell waits for a command")
        }
    }

    @Test
    fun aCommandIsTypedAtThePromptOfPowerShell() {
        val shells = powerShells()
        assumeTrue("No PowerShell is installed", shells.isNotEmpty())
        for (shell in shells) {
            val session = start(shell, directory("katty typed"), TypedCommand(listOf("Write-Output", "it's typed, and a b"), run = true))
            session.await("$shell to run the typed command") { session.screen().lines().any { it.trimEnd() == "it's typed, and a b" } }
        }
    }

    @Test
    fun powerShellWritesBelowWhatARestoredTerminalShows() {
        val shells = powerShells()
        assumeTrue("No PowerShell is installed", shells.isNotEmpty())
        for (shell in shells) {
            val old = TerminalSession.start(100, 20, command = sh("printf 'old 1\\nold 2\\nold 3\\n'; sleep 30"), shellIntegration = false).also { sessions += it }
            old.await("the old content") { "old 3" in old.screen() }
            val restored = TerminalSession.start(
                100, 20, command = listOf(shell, "-NoLogo"), directory = directory("katty restored").toString(), snapshot = old.snapshot(), restoredNote = "restored",
            ).also { sessions += it }
            restored.await("the prompt of $shell") { restored.atPrompt() }

            // ConPTY takes its console for empty, with the cursor at the top, unless it asks the terminal where the cursor is: the line that
            // PSReadLine draws again as it is typed went over the old content.
            restored.sendText("Write-Output typed")
            restored.await("the line typed in $shell") { "Write-Output typed" in restored.screen() }
            restored.sendText("\r")
            restored.await("the output of $shell") { restored.screen().lines().any { it.trimEnd() == "typed" } }
            val lines = restored.screen().lines().map { it.trimEnd() }
            assertEquals(listOf("old 1", "old 2", "old 3", "", "restored"), lines.take(5), "$shell: the old content stays\n${restored.screen()}")
            assertTrue(lines.indexOfFirst { it.endsWith("Write-Output typed") } > 4, "$shell: the line is below it\n${restored.screen()}")
        }
    }

    private companion object {
        // PowerShell takes a second or two to start, more on a virtual machine.
        const val TIMEOUT_MS = 30_000L
    }
}
