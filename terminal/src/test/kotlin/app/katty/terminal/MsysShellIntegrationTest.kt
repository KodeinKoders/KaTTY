package app.katty.terminal

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.junit.Assume.assumeTrue
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The shells of the POSIX environments of Windows (Git for Windows, MSYS2, Cygwin) with KaTTY's integration, real ones: each that this Windows has
 * is run, and none on another system. They mark their commands, say their directory in their own paths, which is read as Windows writes it, and
 * a program of Windows that they run is seen through the copy of the shell that their `fork` leaves.
 */
class MsysShellIntegrationTest {
    private val sessions = mutableListOf<TerminalSession>()
    private val collectors = mutableListOf<Job>()

    @AfterTest
    fun close() {
        collectors.forEach { it.cancel() }
        sessions.forEach { it.close() }
    }

    // The shells that this machine may have, as the settings start them (Shells.kt of app-core), and what they need.
    private fun shells(): List<Pair<List<String>, Map<String, String>>> {
        val git = System.getenv("ProgramFiles")?.let { "$it\\Git" }
        val gitSystem = listOf("clangarm64", "mingw64").firstOrNull { git != null && File("$git\\$it").isDirectory }?.uppercase() ?: "MINGW64"
        val msys = mapOf("MSYSTEM" to "UCRT64", "MSYS2_PATH_TYPE" to "inherit")
        return listOf(
            listOf("$git\\usr\\bin\\bash.exe", "--login", "-i") to mapOf("MSYSTEM" to gitSystem),
            listOf("C:\\msys64\\usr\\bin\\bash.exe", "--login", "-i") to msys,
            listOf("C:\\msys64\\usr\\bin\\zsh.exe", "--login", "-i") to msys,
            listOf("C:\\cygwin64\\bin\\bash.exe", "--login", "-i") to emptyMap(),
            listOf("C:\\cygwin64\\bin\\zsh.exe", "--login", "-i") to emptyMap(),
        ).filter { (argv, _) -> File(argv.first()).isFile }
    }

    private fun TerminalSession.screen(): String = frames.value.rows.joinToString("\n") { row -> row.cells.joinToString("") { it.text.ifEmpty { " " } }.trimEnd() }

    private fun TerminalSession.await(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TIMEOUT_MS * 1_000_000
        while (!condition()) {
            check(System.nanoTime() < deadline) { "Still waiting for $what, the screen is:\n${screen()}" }
            Thread.sleep(20)
        }
    }

    private fun TerminalSession.collectEvents(): MutableList<TerminalEvent> {
        val events = CopyOnWriteArrayList<TerminalEvent>()
        collectors += CoroutineScope(Dispatchers.Unconfined).launch { this@collectEvents.events.collect { events += it } }
        return events
    }

    private fun sameFolder(reported: String?, expected: Path) = reported != null && runCatching { Path.of(reported).toRealPath() == expected.toRealPath() }.getOrDefault(false)

    @Test
    fun theShellsOfThePosixEnvironmentsAreIntegrated() {
        assumeTrue("Windows", testsOnWindows)
        val shells = shells()
        assumeTrue("No POSIX environment is installed", shells.isNotEmpty())
        for ((argv, environment) in shells) {
            val label = argv.first()
            val directory = Files.createTempDirectory("katty posix").also { it.toFile().deleteOnExit() }
            // Not as a session of SSH, which these tests may run under (the VM's): MSYS2's bash.bashrc reads /etc/profile again then, which goes to
            // the home. A KaTTY started from the desktop has no SSH_CONNECTION.
            val session = TerminalSession.start(
                120, 30, command = argv, environment = environment, unsetEnvironment = setOf("SSH_CONNECTION"), directory = directory.toString(),
            ).also { sessions += it }
            val events = session.collectEvents()

            // It starts in the terminal's directory (not the home, where their profile goes otherwise), which it says in its own path.
            session.await("$label to say the directory it started in (${session.workingDirectory.value})") { sameFolder(session.workingDirectory.value, directory) }
            session.await("the prompt of $label") { session.atPrompt() }

            // A command (bash's DEBUG trap, which marks the start of one, does not run for a subshell).
            session.sendText("sh -c 'exit 3'\r")
            session.await("$label to mark a command that failed ($events)") { TerminalEvent.CommandFinished(3) in events }

            session.sendText("cd /c/Windows 2>/dev/null || cd /cygdrive/c/Windows\r")
            session.await("$label to say C:\\Windows (${session.workingDirectory.value})") { session.workingDirectory.value.equals("C:\\Windows", ignoreCase = true) }

            // A program of Windows, under the copy of the shell that its fork leaves.
            session.await("the prompt of $label after cd") { session.atPrompt() }
            session.sendText("ping -n 30 127.0.0.1\r")
            session.await("$label to have ping in front (${session.foregroundProcessName()})") { session.foregroundProcessName().equals("PING", ignoreCase = true) }
            assertEquals(listOf("-n", "30", "127.0.0.1"), session.foregroundCommand()!!.argv.drop(1), label)
            session.sendText("\u0003")
            session.await("$label to be back at its prompt") { session.atPrompt() }
        }
    }

    private companion object {
        // A shell of Cygwin forks slowly, more so when it is x64 and runs emulated on ARM64.
        const val TIMEOUT_MS = 60_000L
    }
}
