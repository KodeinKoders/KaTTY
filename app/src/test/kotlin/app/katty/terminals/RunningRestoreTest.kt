package app.katty.terminals

import app.katty.core.KattyStore
import app.katty.core.RunningCommand
import app.katty.core.TerminalId
import app.katty.core.toTyped
import app.katty.terminal.TerminalSession
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

    // A real zsh, as the application starts one, with the program of the terminal typed in it as `startShell` does.
    private val registry = SessionRegistry(
        store, scope, starter = kotlinx.coroutines.Dispatchers.Unconfined,
        loadTheme = { null },
        start = { spec, _ ->
            TerminalSession.start(
                80, 10,
                command = listOf("/bin/zsh", "-l"),
                environment = mapOf("HOME" to home.toString()),
                directory = spec.running?.directory ?: spec.directory,
                typeAtPrompt = spec.running?.toTyped(),
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

    @Test
    fun keepsTheProgramThatRunsOverTheShellAndForgetsItWhenItEnds() {
        store.addProject(project.toString())
        registry.sync(store.state.value)
        awaitUntil("the shell") { processName() == "zsh" }

        session().sendText("tail -f /dev/null\r")
        awaitUntil("the program to be kept") { running()?.argv == listOf("tail", "-f", "/dev/null") }
        assertEquals(project.toRealPath().toString(), running()!!.directory)
        assertNull(running()!!.agent)

        // Control + C ends it: the shell has the terminal again.
        session().sendText("\u0003")
        awaitUntil("the program to be forgotten") { running() == null }
    }

    @Test
    fun startsTheProgramAgainOverTheShellWhenTheTerminalIsRestored() {
        store.addProject(project.toString())
        store.setRunning(terminalId(), RunningCommand(listOf("tail", "-f", "/dev/null"), project.toString()))

        registry.sync(store.state.value)

        awaitUntil("the program to run") { processName() == "tail" }
        assertNotNull(running(), "It is still what the terminal runs")
        // Over the shell: ending the program is not the end of the terminal.
        session().sendText("\u0003")
        awaitUntil("the shell again") { processName() == "zsh" }
        assertNull(session().exited.value)
        awaitUntil("nothing to restore any more") { running() == null }
    }

    @Test
    fun onlyTypesTheProgramThatTheUserShouldRunThemselves() {
        store.addProject(project.toString())
        store.setRunning(terminalId(), RunningCommand(listOf("sleep", "44"), project.toString()))

        registry.sync(store.state.value)

        awaitUntil("the line to be typed") { screen().lines().any { it.endsWith("% sleep 44") } }
        assertEquals("zsh", processName(), "It did not run")
        assertNotNull(running(), "The shell's prompt, before the program that was to be typed, does not forget it")
        session().sendText("\r")
        awaitUntil("it to run when the user says so") { processName() == "sleep" }
    }

    @Test
    fun aShellThatIsReopenedIsAPlainOne() {
        store.addProject(project.toString())
        store.setRunning(terminalId(), RunningCommand(listOf("tail", "-f", "/dev/null"), project.toString()))
        registry.sync(store.state.value)
        awaitUntil("the program to run") { processName() == "tail" }

        registry.restart(terminalId())

        awaitUntil("a plain shell") { processName() == "zsh" }
        assertNull(running())
        Thread.sleep(1500)
        assertEquals("zsh", processName(), "The program was not typed in the new shell")
    }
}
