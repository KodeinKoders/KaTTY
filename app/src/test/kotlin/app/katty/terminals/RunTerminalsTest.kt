package app.katty.terminals

import app.katty.core.CursorShape
import app.katty.core.KattyStore
import app.katty.core.WorkspaceId
import app.katty.terminal.TerminalSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RunTerminalsTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val started = mutableListOf<Pair<String, String>>()
    private val startedAt = mutableMapOf<String, Long>()
    private val runs = RunTerminals(scope, Dispatchers.Unconfined) { _, command, directory ->
        started += command to directory
        startedAt[command] = System.nanoTime()
        TerminalSession.start(80, 5, command = listOf("/bin/sh", "-c", command), directory = directory, shellIntegration = false)
    }
    private val workspace = WorkspaceId("w")

    @AfterTest
    fun close() {
        runs.close().forEach { it.close() }
        scope.cancel()
    }

    private fun screen(session: TerminalSession) = session.frames.value.rows.joinToString("\n") { row -> row.cells.joinToString("") { it.text } }

    // The session of the run of [workspace], once it has one: a run that replaces another waits for it to end first.
    private fun sessionOf(workspace: WorkspaceId): TerminalSession =
        runBlocking { withTimeout(15_000) { runs.terminals.first { it[workspace]?.session != null } } }.getValue(workspace).session!!

    private fun exitOf(session: TerminalSession): Int = runBlocking { withTimeout(15_000) { session.exited.first { it != null }!! } }

    @Test
    fun aCommandThatEndsByItselfStaysWithItsExitCode() {
        runs.setVisible(workspace)
        runs.run(workspace, "echo built; exit 3", "/tmp")

        val run = runs.terminals.value.getValue(workspace)
        val session = assertNotNull(run.session)
        assertEquals(3, exitOf(session))
        assertEquals("/tmp", started.single().second, "It runs in the directory it is given")
        assertTrue(runs.terminals.value.getValue(workspace).ended)
        assertFalse(runs.active(workspace), "A new run would not ask")
        val shown = runBlocking { withTimeout(15_000) { session.frames.first { frame -> frame.rows.any { row -> "built" in row.cells.joinToString("") { it.text } } } } }
        assertNotNull(shown, "What it wrote is still there to be read")
    }

    @Test
    fun onlyTheRunOfTheWorkspaceOnTheScreenWhileItIsShownDrawsFrames() {
        runs.setVisible(workspace)
        runs.run(workspace, "echo built; sleep 30", "/tmp")
        val session = runs.terminals.value.getValue(workspace).session!!
        runBlocking { withTimeout(15_000) { session.frames.first { "built" in screen(session) } } }

        runs.setShown(workspace, false)

        // A hidden session lets go of its frame: a blank one.
        runBlocking { withTimeout(15_000) { session.frames.first { "built" !in screen(session) } } }
        runs.setShown(workspace, true)
        runBlocking { withTimeout(15_000) { session.frames.first { "built" in screen(session) } } }
    }

    @Test
    fun aRunThatIsStoppedIsGoneWithItsProgram() {
        runs.run(workspace, "sleep 30", "/tmp")
        val session = runs.terminals.value.getValue(workspace).session!!
        assertTrue(runs.active(workspace))

        runs.stop(workspace)

        assertNull(runs.terminals.value[workspace])
        exitOf(session)
    }

    @Test
    fun aWorkspaceHasOneRunAndANewOneStopsTheOld() {
        runs.run(workspace, "sleep 30", "/tmp")
        val first = runs.terminals.value.getValue(workspace)
        runs.setShown(workspace, false)

        runs.run(workspace, "sleep 31", "/tmp")

        val second = runs.terminals.value.getValue(workspace)
        assertEquals("sleep 31", second.command)
        assertTrue(second.shown, "A new run is shown")
        assertTrue(second.generation > first.generation)
        exitOf(first.session!!)
        assertNull(sessionOf(workspace).exited.value)
    }

    // A server that is run again must not find its port taken by the one it replaces: what the old run started, and that is still in its
    // terminal, ends first. Here a child that ignores the hang-up, in a group of its own, and ends a second and a half after.
    @Test
    fun aNewRunStartsOnceWhatTheOldOneStartedHasEnded() {
        runs.run(workspace, "set -m; trap '' HUP; sleep 1.5 & wait", "/tmp")
        sessionOf(workspace)
        // Time for the shell to start its sleep.
        Thread.sleep(300)
        val replaced = System.nanoTime()

        runs.run(workspace, "sleep 31", "/tmp")

        assertTrue(runs.terminals.value.getValue(workspace).waiting, "It waits for the old run")
        val second = sessionOf(workspace)
        assertFalse(runs.terminals.value.getValue(workspace).waiting)
        val waitedMs = (startedAt.getValue("sleep 31") - replaced) / 1_000_000
        assertTrue(waitedMs >= 900, "It started once the sleep of the old run had ended: after ${waitedMs}ms")
        assertNull(second.exited.value)
    }

    @Test
    fun aRunIsShownOrMinimized() {
        runs.run(workspace, "sleep 30", "/tmp")

        runs.toggle(workspace)
        assertFalse(runs.terminals.value.getValue(workspace).shown)
        runs.toggle(workspace)
        assertTrue(runs.terminals.value.getValue(workspace).shown)
    }

    @Test
    fun theRunOfAWorkspaceThatIsGoneIsStopped() {
        val other = WorkspaceId("other")
        runs.run(workspace, "sleep 30", "/tmp")
        runs.run(other, "sleep 30", "/tmp")
        val session = runs.terminals.value.getValue(workspace).session!!

        runs.keepOnly(setOf(other))

        assertEquals(setOf(other), runs.terminals.value.keys)
        exitOf(session)
    }

    @Test
    fun aCommandThatCannotStartSaysWhy() {
        val failing = RunTerminals(scope, Dispatchers.Unconfined) { _, _, _ -> error("no such shell") }

        failing.run(workspace, "make", "/tmp")

        val run = failing.terminals.value.getValue(workspace)
        assertEquals("no such shell", run.failure)
        assertTrue(run.ended)
    }

    @Test
    fun theRegistryRunsTheCommandWithTheUsersShellAndStopsTheRunsOfClosedWorkspaces() {
        val store = KattyStore(newId = { "id${counter++}" })
        val commands = mutableListOf<String>()
        val registry = SessionRegistry(
            store, scope, starter = Dispatchers.Unconfined, loadTheme = { null },
            start = { spec, _, _ -> TerminalSession.start(80, 5, command = listOf("/bin/sh", "-c", "sleep 30"), directory = spec.directory) },
            startRun = { command, directory, _, _ ->
                commands += command
                TerminalSession.start(80, 5, command = listOf("/bin/sh", "-c", command), directory = directory, shellIntegration = false)
            },
        )
        try {
            store.addProject("/tmp")
            store.updateSettings { it.copy(cursorShape = CursorShape.BAR) }
            registry.sync(store.state.value)
            val id = store.state.value.selection.workspace!!
            registry.runs.run(id, "sleep 30", "/tmp")
            val session = registry.runs.terminals.value.getValue(id).session!!
            assertEquals(listOf("sleep 30"), commands)

            store.closeWorkspace(id)
            registry.sync(store.state.value)

            assertTrue(registry.runs.terminals.value.isEmpty())
            exitOf(session)
        } finally {
            registry.close()
        }
    }

    private var counter = 0
}
