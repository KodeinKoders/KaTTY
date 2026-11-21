package app.katty.terminal.platform.windows

import app.katty.terminal.TerminalSession
import app.katty.terminal.defaultShell
import app.katty.terminal.testsOnWindows
import org.junit.Assume.assumeTrue
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WindowsProcessesTest {
    private val sessions = mutableListOf<TerminalSession>()
    private val started = mutableListOf<Long>()

    @AfterTest
    fun close() {
        sessions.forEach { it.close() }
        started.forEach { pid -> ProcessHandle.of(pid).ifPresent { it.destroyForcibly() } }
    }

    // A PE file of [subsystem]: what `subsystem` reads of it, and nothing else.
    private fun executable(subsystem: Int): Path = Files.createTempFile("katty", ".exe").also { file ->
        val bytes = ByteBuffer.allocate(0x40 + 24 + 70).order(ByteOrder.LITTLE_ENDIAN)
        bytes.putShort(0, 0x5A4D)
        bytes.putInt(0x3C, 0x40)
        bytes.putInt(0x40, 0x00004550)
        bytes.putShort(0x40 + 24 + 68, subsystem.toShort())
        Files.write(file, bytes.array())
        file.toFile().deleteOnExit()
    }

    @Test
    fun theSubsystemOfAnExecutableSaysWhetherItIsAGraphicalProgram() {
        assertEquals(2, subsystem(executable(2)))
        assertEquals(3, subsystem(executable(3)))
        assertTrue(isGraphicalProgram(executable(2).toString()))
        assertTrue(!isGraphicalProgram(executable(3).toString()))
        val text = Files.createTempFile("katty", ".txt").also { Files.writeString(it, "not a program"); it.toFile().deleteOnExit() }
        assertNull(subsystem(text))
        assertNull(subsystem(text.resolveSibling("nothing-here.exe")))
    }

    private fun await(what: String, session: TerminalSession, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TIMEOUT_MS * 1_000_000
        while (!condition()) {
            check(System.nanoTime() < deadline) {
                "Still waiting for $what. The screen:\n" + session.frames.value.rows.joinToString("\n") { row -> row.cells.joinToString("") { it.text.ifEmpty { " " } } }
            }
            Thread.sleep(50)
        }
    }

    private fun powerShell(directory: Path): TerminalSession =
        TerminalSession.start(100, 20, command = listOf("powershell.exe", "-NoLogo"), directory = directory.toString()).also { sessions += it }

    private fun directory(): Path = Files.createTempDirectory("katty-processes").also { it.toFile().deleteOnExit() }.toRealPath()

    @Test
    fun theProgramThatPowerShellRunsIsTheOneThatHasTheTerminal() {
        assumeTrue("Windows", testsOnWindows)
        val directory = directory()
        val session = powerShell(directory)
        await("the prompt", session) { session.atPrompt() }
        assertNull(session.foregroundCommand(), "At its prompt, the shell has the terminal")
        assertEquals("powershell", session.foregroundProcessName())

        session.sendText("ping -n 30 127.0.0.1\r")
        await("ping to run", session) { session.foregroundCommand() != null }

        val command = session.foregroundCommand()!!
        started += command.pid.toLong()
        assertTrue(command.argv.first().endsWith("PING.EXE", ignoreCase = true), "${command.argv}")
        assertEquals(listOf("-n", "30", "127.0.0.1"), command.argv.drop(1))
        assertEquals(directory.toString(), command.directory)
        assertTrue(command.executable!!.endsWith("PING.EXE", ignoreCase = true))
        assertTrue(command.startedAtMillis > 0)
        assertEquals(false, command.pipeline)
        assertEquals(listOf(command.pid), command.groupPids)
        assertEquals("PING", session.foregroundProcessName()?.uppercase())
    }

    @Test
    fun twoProgramsOfAPipelineAreAPipeline() {
        // pwsh: Windows PowerShell 5.1 starts the second program of a pipeline of programs only once the first has written (measured), so it
        // is seen late.
        val pwsh = defaultShell().first()
        assumeTrue("pwsh", testsOnWindows && pwsh.endsWith("pwsh.exe", ignoreCase = true))
        val session = TerminalSession.start(100, 20, command = listOf(pwsh, "-NoLogo"), directory = directory().toString()).also { sessions += it }
        await("the prompt", session) { session.atPrompt() }

        session.sendText("ping -n 30 127.0.0.1 | findstr nothing\r")
        await("the pipeline to run", session) { session.foregroundCommand()?.pipeline == true }
    }

    @Test
    fun aGraphicalProgramThatTheShellDoesNotWaitForIsNotTheOneThatHasTheTerminal() {
        assumeTrue("Windows", testsOnWindows)
        val session = powerShell(directory())
        await("the prompt", session) { session.atPrompt() }

        session.sendText("charmap\r")
        val charmap = run {
            val deadline = System.nanoTime() + TIMEOUT_MS * 1_000_000
            var found: ProcessHandle? = null
            while (found == null && System.nanoTime() < deadline) {
                found = ProcessHandle.of(session.programPid().toLong()).orElseThrow().children()
                    .filter { it.info().command().orElse("").endsWith("charmap.exe", ignoreCase = true) }.findFirst().orElse(null)
                if (found == null) Thread.sleep(50)
            }
            found
        }
        assumeTrue("charmap is on this Windows", charmap != null)
        started += charmap!!.pid()
        await("the prompt after charmap", session) { session.atPrompt() }

        assertNull(session.foregroundCommand(), "The shell is at its prompt, charmap is its child still")
        assertEquals("powershell", session.foregroundProcessName())
    }

    @Test
    fun theSessionIsWhatTheShellStartedAndNotWhatADeadProgramLeftBehind() {
        assumeTrue("Windows", testsOnWindows)
        // An inner cmd starts ping in the background and ends: that ping's parent is gone, and nothing that runs leads to it any more, as a
        // daemon whose client ended (Gradle's): it is not the session's. The outer cmd waits for a ping of its own, which is. (When the program
        // of the terminal ends, ConPTY closes its console, which ends what is attached to it.)
        val script = "cmd /c start /b ping -n 30 127.0.0.1 > nul & ping -n 30 127.0.0.1 > nul"
        val session = TerminalSession.start(100, 20, command = listOf("cmd.exe", "/c", script), shellIntegration = false).also { sessions += it }
        val leader = session.programPid()
        val since = ProcessHandle.of(leader.toLong()).flatMap { it.info().startInstant() }.map { it.toEpochMilli() }.orElse(0L)
        fun isPing(pid: Long) = ProcessHandle.of(pid).flatMap { it.info().command() }.orElse("").endsWith("PING.EXE", ignoreCase = true)
        val allPings = { ProcessHandle.allProcesses().filter { isPing(it.pid()) && it.info().startInstant().map { s -> s.toEpochMilli() >= since }.orElse(false) }.map { it.pid() }.toList() }

        await("both pings to run", session) { allPings().size >= 2 }
        started += allPings()
        val session1 = WindowsProcessInspector.sessionPids(leader, since)
        assertEquals(1, session1.count { isPing(it.toLong()) }, "Only the ping that its own cmd waits for: $session1")
        assertTrue(leader in session1, "The leader, while it runs")
        assertEquals(emptyList(), WindowsProcessInspector.sessionPids(leader, System.currentTimeMillis() + 60_000), "Nothing that started before is")

        assertTrue(session.closeAndWait(), "Closing the terminal ends what its console runs")
        assertEquals(emptyList(), WindowsProcessInspector.sessionPids(leader, since))
    }

    private companion object {
        const val TIMEOUT_MS = 30_000L
    }
}
