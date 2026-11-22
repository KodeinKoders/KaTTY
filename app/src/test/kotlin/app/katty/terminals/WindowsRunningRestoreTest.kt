package app.katty.terminals

import app.katty.core.KattyStore
import app.katty.core.RunningCommand
import app.katty.core.TerminalId
import app.katty.core.isWindows
import app.katty.core.programName
import app.katty.core.toTyped
import app.katty.terminal.TerminalSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assume.assumeTrue
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** What runs over a shell is kept and comes back, on Windows: [RunningRestoreTest] does it with zsh. */
class WindowsRunningRestoreTest {
    private var counter = 0
    private val store = KattyStore(newId = { "id${counter++}" })
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val project = Files.createTempDirectory("katty-project").also { it.toFile().deleteOnExit() }

    // A real Windows PowerShell, with its integration, and the program of the terminal typed in it as `startShell` does.
    private val registry = SessionRegistry(
        store, scope, starter = Dispatchers.Unconfined,
        loadTheme = { null },
        start = { spec, _, _ ->
            TerminalSession.start(
                100, 10,
                command = listOf("powershell.exe", "-NoLogo"),
                directory = spec.running?.directory ?: spec.directory,
                typeAtPrompt = spec.running?.toTyped(),
            )
        },
    )

    @BeforeTest
    fun onWindows() {
        assumeTrue("Windows", isWindows)
    }

    @AfterTest
    fun close() {
        registry.close()
        scope.cancel()
    }

    private fun terminalId(): TerminalId = store.state.value.selection.terminal!!

    private fun running() = store.state.value.locate(terminalId())!!.terminal.running

    private fun session(): TerminalSession = registry.handles.value.getValue(terminalId()).session!!

    private fun awaitUntil(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 30_000_000_000
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(20)
        assertTrue(condition(), "$what. The screen:\n${screen()}")
    }

    private fun screen(): String =
        registry.handles.value.values.firstOrNull()?.session?.frames?.value?.rows
            ?.joinToString("\n") { row -> row.cells.joinToString("") { it.text.ifEmpty { " " } }.trimEnd() }.orEmpty()

    private fun processName() = registry.processNames.value[terminalId()]

    @Test
    fun keepsTheProgramThatRunsOverPowerShellAndForgetsItWhenItEnds() {
        store.addProject(project.toString())
        registry.sync(store.state.value)
        awaitUntil("the shell") { processName() == "powershell" }

        session().sendText("ping -n 60 127.0.0.1\r")
        awaitUntil("the program to be kept") { running()?.argv?.drop(1) == listOf("-n", "60", "127.0.0.1") }
        assertEquals("ping", programName(running()!!.argv.first()).lowercase())
        assertEquals(project.toRealPath().toString(), running()!!.directory)

        // Control + C ends it: the shell has the terminal again.
        session().sendText("\u0003")
        awaitUntil("the program to be forgotten") { running() == null }
    }

    @Test
    fun typesTheProgramAgainInPowerShellWhenTheTerminalIsRestored() {
        store.addProject(project.toString())
        store.setRunning(terminalId(), RunningCommand(listOf("ping", "-n", "60", "127.0.0.1"), project.toString()))

        registry.sync(store.state.value)

        // ping is not one of the programs that run by themselves: it is typed, and runs when the user says so.
        awaitUntil("the line to be typed") { screen().lines().any { it.endsWith("> ping -n 60 127.0.0.1") } }
        assertEquals("powershell", processName(), "It did not run")
        assertNotNull(running())
        session().sendText("\r")
        awaitUntil("it to run when the user says so") { processName()?.lowercase() == "ping" }
    }
}
