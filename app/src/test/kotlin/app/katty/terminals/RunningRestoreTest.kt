package app.katty.terminals

import app.katty.core.KattyStore
import app.katty.core.RestorePolicy
import app.katty.core.RunningCommand
import app.katty.core.TerminalId
import app.katty.core.isWindows
import app.katty.core.toTyped
import app.katty.interactiveShellName
import app.katty.terminal.TerminalSession
import app.katty.terminal.defaultShell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RunningRestoreTest {
    private var counter = 0
    private val store = KattyStore(newId = { "id${counter++}" })
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val home: Path = Files.createTempDirectory("katty-home").also {
        Files.createFile(it.resolve(".zshrc"))
        it.toFile().deleteOnExit()
    }
    private val project = Files.createTempDirectory("katty-project").also { it.toFile().deleteOnExit() }

    // A program that runs until Control + C, and its name: on Windows ping, which no list of RestorePolicy has, so that the ones that are restored
    // and run are given it.
    private val program = if (isWindows) listOf("ping", "-n", "60", "127.0.0.1") else listOf("tail", "-f", "/dev/null")
    private val programName = if (isWindows) "PING" else "tail"
    private val runsProgram = if (isWindows) RestorePolicy(setOf("ping")) else RestorePolicy()
    private var policy = runsProgram

    // Whether [argv] is [program]: PowerShell gives a program the whole path of its file.
    private fun isProgram(argv: List<String>?) =
        argv != null && argv.drop(1) == program.drop(1) && argv.first().substringAfterLast('\\').substringAfterLast('/').substringBeforeLast(".").equals(program.first(), ignoreCase = true)

    // A real zsh, as the application starts one, with the program of the terminal typed in it as `startShell` does; on Windows PowerShell with
    // its integration.
    private val registry = SessionRegistry(
        store, scope, starter = kotlinx.coroutines.Dispatchers.Unconfined,
        loadTheme = { null },
        start = { spec, _, _ ->
            // Wide on Windows, whose prompt has the path of the temporary folder: a typed line that wraps is not on one row.
            TerminalSession.start(
                if (isWindows) 200 else 80, 10,
                command = if (isWindows) listOf(defaultShell().first(), "-NoLogo") else listOf("/bin/zsh", "-l"),
                environment = if (isWindows) emptyMap() else mapOf("HOME" to home.toString()),
                directory = spec.running?.directory ?: spec.directory,
                typeAtPrompt = spec.running?.toTyped(policy),
            )
        },
    )

    @AfterTest
    fun close() {
        registry.close()
        scope.cancel()
    }

    private fun terminalId(): TerminalId = store.state.value.selection.terminal!!

    private fun running() = store.state.value.locate(terminalId())!!.terminal.running

    private fun session(): TerminalSession = registry.handles.value.getValue(terminalId()).session!!

    private fun awaitUntil(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 20_000_000_000
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(20)
        assertTrue(condition(), "$what. The screen:\n${screen()}")
    }

    private fun screen(): String =
        registry.handles.value.values.firstOrNull()?.session?.frames?.value?.rows
            ?.joinToString("\n") { row -> row.cells.joinToString("") { it.text.ifEmpty { " " } }.trimEnd() }.orEmpty()

    private fun processName() = registry.processNames.value[terminalId()]

    private val shell = if (isWindows) interactiveShellName else "zsh"

    @Test
    fun keepsTheProgramThatRunsOverTheShellAndForgetsItWhenItEnds() {
        store.addProject(project.toString())
        registry.sync(store.state.value)
        awaitUntil("the shell") { processName() == shell }

        session().sendText(program.joinToString(" ") + "\r")
        awaitUntil("the program to be kept") { isProgram(running()?.argv) }
        assertEquals(project.toRealPath().toString(), running()!!.directory)
        assertNull(running()!!.agent)

        // Control + C ends it: the shell has the terminal again.
        session().sendText("\u0003")
        awaitUntil("the program to be forgotten") { running() == null }
    }

    @Test
    fun startsTheProgramAgainOverTheShellWhenTheTerminalIsRestored() {
        store.addProject(project.toString())
        store.setRunning(terminalId(), RunningCommand(program, project.toString()))

        registry.sync(store.state.value)

        awaitUntil("the program to run") { processName() == programName }
        assertNotNull(running(), "It is still what the terminal runs")
        // Over the shell: ending the program is not the end of the terminal.
        session().sendText("\u0003")
        awaitUntil("the shell again") { processName() == shell }
        assertNull(session().exited.value)
        awaitUntil("nothing to restore any more") { running() == null }
    }

    @Test
    fun onlyTypesTheProgramThatTheUserShouldRunThemselves() {
        store.addProject(project.toString())
        // A program that is not one that runs again by itself.
        policy = RestorePolicy()
        val waits = if (isWindows) listOf("ping", "-n", "44", "127.0.0.1") else listOf("sleep", "44")
        store.setRunning(terminalId(), RunningCommand(waits, project.toString()))

        registry.sync(store.state.value)

        awaitUntil("the line to be typed") { screen().lines().any { it.endsWith((if (isWindows) "> " else "% ") + waits.joinToString(" ")) } }
        assertEquals(shell, processName(), "It did not run")
        assertNotNull(running(), "The shell's prompt, before the program that was to be typed, does not forget it")
        session().sendText("\r")
        awaitUntil("it to run when the user says so") { processName() == (if (isWindows) "PING" else "sleep") }
    }

    @Test
    fun aShellThatIsReopenedIsAPlainOne() {
        store.addProject(project.toString())
        store.setRunning(terminalId(), RunningCommand(program, project.toString()))
        registry.sync(store.state.value)
        awaitUntil("the program to run") { processName() == programName }

        registry.restart(terminalId())

        awaitUntil("a plain shell") { processName() == shell }
        assertNull(running())
        Thread.sleep(1500)
        assertEquals(shell, processName(), "The program was not typed in the new shell")
    }
}
