package app.katty.terminals

import app.katty.core.EnvironmentChange
import app.katty.core.Environments
import app.katty.core.KattyStore
import app.katty.core.RunningCommand
import app.katty.core.SplitDirection
import app.katty.core.TerminalId
import app.katty.core.TerminalSnapshotStore
import app.katty.core.TerminalSpec
import app.katty.terminal.TerminalSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// The shells of a workspace are started with its environment, read from real files, and print what they were given.
class SessionRegistryEnvironmentTest {
    private var counter = 0
    private val store = KattyStore(newId = { "id${counter++}" })
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val root: Path = Files.createTempDirectory("katty-registry-environments")
    private val environments = root.resolve("environments").also { it.createDirectories() }
    private val project = root.resolve("project").also { it.createDirectories() }.toString()

    private class Start(val spec: TerminalSpec, val environment: EnvironmentChange, val restored: Boolean)

    private val starts = CopyOnWriteArrayList<Start>()
    private val runs = CopyOnWriteArrayList<EnvironmentChange>()
    private val sessions = CopyOnWriteArrayList<TerminalSession>()

    // The second line says the name of the environment that the shell was told, which no test of the first line looks at.
    private val script = "echo \"FOO=\${FOO-none} end\"; echo \"ENV=\${KATTY_ENVIRONMENT-none}\"; sleep 30"

    private fun shell(spec: TerminalSpec, environment: EnvironmentChange, restored: Boolean, snapshot: app.katty.terminal.TerminalSnapshot? = null): TerminalSession {
        starts += Start(spec, environment, restored)
        return TerminalSession.start(
            80, 5, command = listOf("/bin/sh", "-c", script), directory = spec.directory, shellIntegration = false,
            environment = environment.variables, unsetEnvironment = environment.unset, snapshot = snapshot,
        ).also { sessions += it }
    }

    private val registry = SessionRegistry(
        store, scope, starter = Dispatchers.Unconfined, loadTheme = { null },
        start = { spec, _, environment -> shell(spec, environment, restored = false) },
        snapshots = TerminalSnapshotStore(root.resolve("snapshots")),
        startRestored = { spec, _, snapshot, environment -> shell(spec, environment, restored = true, snapshot) },
        startRun = { command, directory, _, environment ->
            runs += environment
            TerminalSession.start(80, 5, command = listOf("/bin/sh", "-c", command), directory = directory, shellIntegration = false).also { sessions += it }
        },
        resolveEnvironment = { Environments.resolve(it, listOf(environments.toString())) },
    )

    @AfterTest
    fun close() {
        registry.close()
        sessions.forEach { it.close() }
        scope.cancel()
        root.toFile().deleteRecursively()
    }

    private fun sync() = registry.sync(store.state.value)

    private fun await(what: String, screen: () -> String? = { null }, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 15_000_000_000L
        while (!condition()) {
            check(System.nanoTime() < deadline) { "Still waiting for $what" + (screen()?.let { ", the screen:\n$it" } ?: "") }
            Thread.sleep(10)
        }
    }

    private fun session(id: TerminalId): TerminalSession {
        await("the shell of ${id.value}") { registry.handles.value[id]?.session != null }
        return registry.handles.value.getValue(id).session!!
    }

    private fun TerminalSession.awaitText(text: String) = await(text, ::readText) { text in readText() }

    private val terminal get() = store.state.value.selection.terminal!!

    // What a shell with no environment of its own is given: only its name, taken out.
    private val standardShell = EnvironmentChange(emptyMap(), setOf("KATTY_ENVIRONMENT"))

    @Test
    fun aShellIsGivenTheEnvironmentOfItsWorkspace() {
        environments.resolve("demo.env").writeText("FOO=bar\nunset DOTENV_PROBE")
        store.updateSettings { it.copy(defaultEnvironment = "demo") }
        store.addProject(project)

        sync()

        session(terminal).awaitText("FOO=bar end")
        session(terminal).awaitText("ENV=demo")
        assertEquals(EnvironmentChange(mapOf("FOO" to "bar", "KATTY_ENVIRONMENT" to "demo"), setOf("DOTENV_PROBE")), starts.single().environment)
    }

    @Test
    fun aClaudeStartedAgainUnderAnotherAccountStartsANewConversation() {
        environments.resolve("kodein.env").writeText("CLAUDE_CONFIG_DIR=/claude-kodein")
        environments.resolve("kodein-too.env").writeText("CLAUDE_CONFIG_DIR=/claude-kodein\nFOO=too")
        environments.resolve("perso.env").writeText("CLAUDE_CONFIG_DIR=/claude-perso")
        store.updateSettings { it.copy(defaultEnvironment = "kodein") }
        store.addProject(project)
        val id = terminal
        val claude = RunningCommand(listOf("claude"), directory = project, agent = "claude", agentSession = "f65d7eb8-beec-40e0-9c07-40c1b1a56ba2")
        store.setRunning(id, claude)
        sync()
        session(id).awaitText("FOO=none end")
        val workspace = store.state.value.selection.workspace!!

        fun restartWith(environment: String, foo: String) {
            val before = registry.handles.value.getValue(id).session
            store.setWorkspacesEnvironment(setOf(workspace), environment)
            registry.restartWithEnvironment(listOf(id))
            await("the new shell") { registry.handles.value[id]?.session.let { it != null && it !== before } }
            session(id).awaitText("FOO=$foo end")
        }

        restartWith("kodein-too", "too")
        assertEquals(claude, starts.last().spec.running, "The same account: its conversation")
        restartWith("perso", "none")
        assertEquals(claude.copy(agentSession = null), starts.last().spec.running, "Another account: not its conversation")
        assertEquals(null, store.state.value.locate(id)!!.terminal.running!!.agentSession, "Nor at the next launch")
    }

    @Test
    fun claudeIsLookedForInTheConfigDirThatTheShellWasGiven() {
        assertEquals("/x", claudeConfigDir(EnvironmentChange(mapOf("CLAUDE_CONFIG_DIR" to "/x"), emptySet())))
        assertEquals(null, claudeConfigDir(EnvironmentChange(emptyMap(), setOf("CLAUDE_CONFIG_DIR"))), "Taken out: ~/.claude")
        assertEquals(System.getenv("CLAUDE_CONFIG_DIR")?.ifEmpty { null }, claudeConfigDir(EnvironmentChange.NONE), "The application's")
    }

    @Test
    fun aShellOfAWorkspaceWithNoEnvironmentIsGivenNothing() {
        environments.resolve("demo.env").writeText("FOO=bar")
        store.addProject(project)

        sync()

        session(terminal).awaitText("FOO=none end")
        session(terminal).awaitText("ENV=none")
        assertEquals(standardShell, starts.single().environment, "Nothing but the name taken out, in case KaTTY was started from one of its own terminals")
    }

    @Test
    fun aShellOfAnEnvironmentThatHasNoFileStartsWithout() {
        store.updateSettings { it.copy(defaultEnvironment = "gone") }
        store.addProject(project)

        sync()

        session(terminal).awaitText("FOO=none end")
        session(terminal).awaitText("ENV=none")
        assertEquals(standardShell, starts.single().environment, "It does not have the environment, so it is not told its name")
    }

    @Test
    fun aSplitIsGivenTheEnvironmentOfItsWorkspaceNotOfItsProject() {
        environments.resolve("demo.env").writeText("FOO=bar")
        environments.resolve("other.env").writeText("FOO=baz")
        store.updateSettings { it.copy(defaultEnvironment = "demo") }
        val id = store.addProject(project)
        sync()
        val first = terminal
        session(first).awaitText("FOO=bar end")
        store.setProjectEnvironment(id, "other")

        val second = store.split(first, SplitDirection.ROW)!!
        sync()

        session(second).awaitText("FOO=bar end")
    }

    @Test
    fun aRunIsGivenTheEnvironmentOfItsWorkspace() {
        environments.resolve("demo.env").writeText("FOO=bar")
        store.updateSettings { it.copy(defaultEnvironment = "demo") }
        store.addProject(project)
        sync()
        val workspace = store.state.value.selection.workspace!!

        registry.runs.run(workspace, "echo run", project)

        await("the run") { runs.isNotEmpty() }
        assertEquals(EnvironmentChange(mapOf("FOO" to "bar", "KATTY_ENVIRONMENT" to "demo"), emptySet()), runs.single())
    }

    @Test
    fun theShellsThatAreStartedAgainWithAnotherEnvironmentGoOnWhereTheyWere() {
        environments.resolve("demo.env").writeText("FOO=bar")
        environments.resolve("other.env").writeText("FOO=baz")
        store.updateSettings { it.copy(defaultEnvironment = "demo") }
        store.addProject(project)
        val id = terminal
        // A terminal restored with a program over its shell, which is kept while its new shell has not typed it again.
        val running = RunningCommand(listOf("vim", "notes.txt"), directory = project)
        store.setRunning(id, running)
        sync()
        val first = session(id)
        first.awaitText("FOO=bar end")
        val workspace = store.state.value.selection.workspace!!

        store.setWorkspacesEnvironment(setOf(workspace), "other")
        registry.restartWithEnvironment(listOf(id))

        await("the new shell") { registry.handles.value[id]?.session.let { it != null && it !== first } }
        val second = session(id)
        second.awaitText("FOO=baz end")
        val restart = starts.last()
        assertTrue(restart.restored, "It starts with what the old shell showed")
        assertTrue("FOO=bar end" in second.readText(), second.readText())
        assertEquals(running, restart.spec.running, "What ran over the shell is typed again")
        assertEquals(project, restart.spec.directory)
    }
}
