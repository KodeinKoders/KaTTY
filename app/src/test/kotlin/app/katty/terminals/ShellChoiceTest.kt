package app.katty.terminals

import app.katty.core.InstalledShell
import app.katty.core.KattyStore
import app.katty.core.Settings
import app.katty.core.ShellLaunch
import app.katty.core.isWindows
import app.katty.core.resolveShell
import app.katty.terminal.TerminalSession
import app.katty.tmpDirectory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assume.assumeTrue
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** The terminals start the shell that the settings choose (`Settings.shell`), with real shells: bash, where the default is the user's. */
class ShellChoiceTest {
    private var counter = 0
    private val store = KattyStore(newId = { "id${counter++}" })
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val sessions = mutableListOf<TerminalSession>()
    private val bash = InstalledShell("/bin/bash", "bash", "bash", listOf("/bin/bash", "-l"))

    private val registry = SessionRegistry(
        store, scope, starter = Dispatchers.Unconfined, loadTheme = { null },
        shellLaunch = { settings -> resolveShell(settings.shell, settings.shellCommand, listOf(bash), ShellLaunch.default(), windows = false) },
    )

    @BeforeTest
    fun aUnixSystemWithBash() {
        assumeTrue("bash of a Unix system", !isWindows && File("/bin/bash").canExecute())
    }

    @AfterTest
    fun close() {
        registry.close()
        sessions.forEach { it.close() }
        scope.cancel()
    }

    private fun await(what: String, screen: () -> String = { "" }, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 15_000_000_000L
        while (!condition()) {
            check(System.nanoTime() < deadline) { "Still waiting for $what. The screen:\n${screen()}" }
            Thread.sleep(20)
        }
    }

    private fun TerminalSession.text() = frames.value.rows.joinToString("\n") { row -> row.cells.joinToString("") { it.text.ifEmpty { " " } }.trimEnd() }

    @Test
    fun theTerminalsStartTheShellThatIsChosen() {
        store.updateSettings { it.copy(shell = "/bin/bash") }
        store.addProject(tmpDirectory)

        registry.sync(store.state.value, false)

        val handle = registry.handles.value.values.single()
        assertEquals("bash", handle.shellName, "What says that the shell has the terminal again")
        await("bash to have the terminal", { handle.session!!.text() }) { handle.processName.value == "bash" }
    }

    @Test
    fun aShellThatIsNotInstalledAnyMoreIsTheDefaultOne() {
        store.updateSettings { it.copy(shell = "/no/such/fish") }
        store.addProject(tmpDirectory)

        registry.sync(store.state.value, false)

        assertEquals(ShellLaunch.default().name, registry.handles.value.values.single().shellName)
    }

    @Test
    fun aRunTerminalRunsItsCommandWithTheChosenShell() {
        val launch = resolveShell("/bin/bash", "", listOf(bash), ShellLaunch.default(), windows = false)

        // Only bash has a BASH_VERSION: the default shell (zsh) would print nothing after the sign.
        val run = startCommand("echo \"shell=\$BASH_VERSION\"", tmpDirectory, null, Settings(), launch = launch).also { sessions += it }

        await("bash to run the command", { run.text() }) { Regex("shell=\\d").containsMatchIn(run.text()) }
    }
}
