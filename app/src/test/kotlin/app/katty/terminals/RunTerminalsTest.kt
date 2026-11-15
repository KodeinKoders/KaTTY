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
    private val runs = RunTerminals(scope, Dispatchers.Unconfined) { command, directory ->
        started += command to directory
        TerminalSession.start(80, 5, command = listOf("/bin/sh", "-c", command), directory = directory, shellIntegration = false)
    }
    private val workspace = WorkspaceId("w")

    @AfterTest
    fun close() {
        runs.close().forEach { it.close() }
        scope.cancel()
    }

    private fun screen(session: TerminalSession) = session.frames.value.rows.joinToString("\n") { row -> row.cells.joinToString("") { it.text } }

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
        assertNull(second.session!!.exited.value)
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
        val failing = RunTerminals(scope, Dispatchers.Unconfined) { _, _ -> error("no such shell") }

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
            start = { spec, _ -> TerminalSession.start(80, 5, command = listOf("/bin/sh", "-c", "sleep 30"), directory = spec.directory) },
            startRun = { command, directory, _ ->
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
