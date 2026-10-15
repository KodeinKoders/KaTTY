package app.katty.terminals

import app.katty.core.KattyStore
import app.katty.core.SplitDirection
import app.katty.terminal.TerminalSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionRegistryTest {
    private var counter = 0
    private val store = KattyStore(newId = { "id${counter++}" })
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val started = mutableListOf<TerminalSession>()
    private val themes = mutableListOf<String>()
    private var script = "sleep 30"

    private val registry = SessionRegistry(
        store, scope,
        loadTheme = { name -> themes += name; null },
        start = { spec, _ ->
            TerminalSession.start(80, 5, command = listOf("/bin/sh", "-c", script), directory = spec.directory).also { started += it }
        },
    )

    @AfterTest
    fun close() {
        registry.close()
        scope.cancel()
    }

    private fun sync() = registry.sync(store.state.value)

    @Test
    fun startsOneSessionForEachTerminalOfEveryWorkspace() {
        val project = store.addProject("/tmp")
        store.addWorkspace(project)
        store.split(store.state.value.selection.terminal!!, SplitDirection.ROW)

        sync()

        assertEquals(3, registry.handles.value.size)
        assertEquals(store.state.value.allTerminals().map { it.terminal.id }.toSet(), registry.handles.value.keys)
        assertTrue(registry.handles.value.values.all { it.session != null })
    }

    @Test
    fun syncingAgainKeepsTheSessionsThatAreThere() {
        store.addProject("/tmp")
        sync()
        val before = registry.handles.value.values.single()

        sync()

        assertEquals(1, started.size)
        assertTrue(before === registry.handles.value.values.single())
    }

    @Test
    fun closesTheSessionOfATerminalThatIsGone() {
        store.addProject("/tmp")
        val first = store.state.value.selection.terminal!!
        val second = store.split(first, SplitDirection.ROW)!!
        sync()
        val session = registry.handles.value.getValue(first).session!!

        store.closeTerminal(first)
        sync()

        assertEquals(setOf(second), registry.handles.value.keys)
        // Its shell was ended: the session reports that it exited.
        runBlocking { withTimeout(10_000) { session.exited.filterNotNull().first() } }
    }

    @Test
    fun aShellThatCannotBeStartedLeavesATerminalWithItsFailure() {
        val failing = SessionRegistry(store, scope, loadTheme = { null }, start = { _, _ -> throw IllegalStateException("no such shell") })
        store.addProject("/tmp")

        failing.sync(store.state.value)

        val handle = failing.handles.value.values.single()
        assertNull(handle.session)
        assertEquals("no such shell", handle.failure)
    }

    @Test
    fun theColorSchemeOfATerminalIsItsOwnOrTheDefaultOfTheSettings() {
        store.addProject("/tmp")
        val id = store.state.value.selection.terminal!!
        sync()
        store.setColorScheme(id, "Nord")
        sync()
        store.setColorScheme(id, null)
        sync()

        assertEquals(listOf("Alabaster", "Nord", "Alabaster"), themes)
    }

    @Test
    fun aChangeOfTheDefaultSchemeReachesTheTerminalsThatDoNotHaveTheirOwn() {
        store.addProject("/tmp")
        sync()

        store.updateSettings { it.copy(colorScheme = "Nord") }
        sync()

        assertEquals(listOf("Alabaster", "Nord"), themes)
    }

    @Test
    fun whereTheShellGoesIsWhereTheTerminalStartsNextTime() {
        script = "printf '\\033]7;file://host/tmp\\007'; sleep 30"
        store.addProject("/")
        val id = store.state.value.selection.terminal!!

        sync()

        val deadline = System.nanoTime() + 10_000_000_000
        while (store.state.value.locate(id)!!.terminal.directory != "/tmp" && System.nanoTime() < deadline) Thread.sleep(20)
        assertEquals("/tmp", store.state.value.locate(id)!!.terminal.directory)
    }

    private fun git(directory: java.io.File, vararg arguments: String) {
        val process = ProcessBuilder(listOf("git", "-c", "user.name=t", "-c", "user.email=t@t", "-c", "init.defaultBranch=main") + arguments).directory(directory).redirectErrorStream(true).start()
        process.inputStream.readBytes()
        check(process.waitFor() == 0)
    }

    private fun await(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(20)
        assertTrue(condition(), what)
    }

    @Test
    fun aTerminalKnowsTheBranchItsShellIsIn() {
        val repo = kotlin.io.path.createTempDirectory("katty-registry").toRealPath().toFile()
        try {
            git(repo, "init")
            store.addProject(repo.path)
            val id = store.state.value.selection.terminal!!

            sync()

            await("the branch is read") { registry.handles.value.getValue(id).git.value == app.katty.core.GitInfo("main", app.katty.core.GitKind.BRANCH) }
        } finally {
            repo.deleteRecursively()
        }
    }

    @Test
    fun aTerminalOutsideARepositoryHasNoBranchAndOneThatGoesThereLosesIt() {
        val repo = kotlin.io.path.createTempDirectory("katty-registry").toRealPath().toFile()
        val plain = kotlin.io.path.createTempDirectory("katty-registry-plain").toRealPath().toFile()
        try {
            git(repo, "init")
            // The shell goes to a folder that is not in a repository.
            script = "printf '\\033]7;file://host${plain.path}\\007'; sleep 30"
            store.addProject(repo.path)
            val id = store.state.value.selection.terminal!!

            sync()

            await("the folder that the shell went to is the terminal's") { store.state.value.locate(id)!!.terminal.directory == plain.path }
            await("it has no branch there") { registry.handles.value.getValue(id).git.value == null }
        } finally {
            repo.deleteRecursively()
            plain.deleteRecursively()
        }
    }

    @Test
    fun aTerminalStartsWhereItLastWasIfThatFolderIsStillThere() {
        val project = kotlin.io.path.createTempDirectory("katty-project").toRealPath().toFile()
        val inside = java.io.File(project, "src").also { it.mkdirs() }
        try {
            store.addProject(project.path)
            store.setDirectory(store.state.value.selection.terminal!!, inside.path)
            val directories = mutableListOf<String>()
            val recording = SessionRegistry(store, scope, loadTheme = { null }, start = { spec, _ -> directories += spec.directory; TerminalSession.start(80, 5, command = listOf("/bin/sleep", "30"), directory = spec.directory) })

            recording.sync(store.state.value)

            assertEquals(listOf(inside.path), directories)
            recording.close()
        } finally {
            project.deleteRecursively()
        }
    }

    @Test
    fun aTerminalWhoseFolderIsGoneStartsInTheFolderOfItsProject() {
        val project = kotlin.io.path.createTempDirectory("katty-project").toRealPath().toFile()
        try {
            store.addProject(project.path)
            store.setDirectory(store.state.value.selection.terminal!!, "/nonexistent/folder")
            val directories = mutableListOf<String>()
            val recording = SessionRegistry(store, scope, loadTheme = { null }, start = { spec, _ -> directories += spec.directory; TerminalSession.start(80, 5, command = listOf("/bin/sleep", "30"), directory = spec.directory) })

            recording.sync(store.state.value)

            assertEquals(listOf(project.path), directories)
            recording.close()
        } finally {
            project.deleteRecursively()
        }
    }

    @Test
    fun aShellThatEndsLeavesItsOutputAndSaysThatItIsOver() {
        script = "printf hello"
        store.addProject("/tmp")
        val id = store.state.value.selection.terminal!!

        sync()

        val session = registry.handles.value.getValue(id).session!!
        await("the end is on the screen") { session.screenText().let { "hello" in it && "[Process completed]" in it } }
        assertEquals(0, session.exited.value)
    }

    @Test
    fun restartingStartsAnotherShellInThePlaceOfTheFirst() {
        store.addProject("/tmp")
        val id = store.state.value.selection.terminal!!
        sync()
        val before = registry.handles.value.getValue(id)

        registry.restart(id)

        val after = registry.handles.value.getValue(id)
        assertNotSame(before, after)
        assertNotNull(after.session)
        assertNotSame(before.session, after.session)
        assertEquals(1, after.generation)
        assertEquals(2, started.size)
    }

    @Test
    fun hidesTheSessionsThatAreNotShown() {
        store.addProject("/tmp")
        val first = store.state.value.selection.terminal!!
        val second = store.split(first, SplitDirection.ROW)!!
        sync()

        registry.setVisible(setOf(second))

        assertEquals(false, registry.handles.value.getValue(first).session!!.visible.value)
        assertEquals(true, registry.handles.value.getValue(second).session!!.visible.value)
    }
}
