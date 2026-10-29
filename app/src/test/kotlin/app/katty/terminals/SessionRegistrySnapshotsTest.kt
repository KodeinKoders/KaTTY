package app.katty.terminals

import app.katty.core.KattyStore
import app.katty.core.SplitDirection
import app.katty.core.TerminalSnapshotStore
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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// What a terminal showed comes back when the registry is made again, with real shells and real files.
class SessionRegistrySnapshotsTest {
    private var counter = 0
    private val store = KattyStore(newId = { "id${counter++}" })
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val directory: Path = Files.createTempDirectory("katty-registry-snapshots").resolve("snapshots")
    private val snapshots = TerminalSnapshotStore(directory)
    private val registries = mutableListOf<SessionRegistry>()
    private val sessions = mutableListOf<TerminalSession>()

    @AfterTest
    fun close() {
        registries.forEach { it.close() }
        sessions.forEach { it.close() }
        scope.cancel()
        directory.parent.toFile().deleteRecursively()
    }

    private fun sh(script: String) = listOf("/bin/sh", "-c", script)

    // A registry whose first shell says `first` and whose restored one says `second`, and that saves every [autosaveMillis].
    private fun registry(autosaveMillis: Long = 3_600_000): SessionRegistry = SessionRegistry(
        store, scope, starter = Dispatchers.Unconfined, loadTheme = { null },
        start = { spec, _ ->
            TerminalSession.start(80, 5, command = sh("echo first; sleep 30"), directory = spec.directory, shellIntegration = false).also { sessions += it }
        },
        snapshots = snapshots,
        startRestored = { spec, _, snapshot ->
            TerminalSession.start(
                80, 5, command = sh("echo second; sleep 30"), directory = spec.directory, shellIntegration = false,
                snapshot = snapshot, restoredNote = "Restored",
            ).also { sessions += it }
        },
        autosaveMillis = autosaveMillis,
    ).also { registries += it }

    private fun await(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 15_000_000_000L
        while (!condition()) {
            check(System.nanoTime() < deadline) { "Still waiting for $what" }
            Thread.sleep(10)
        }
    }

    private fun SessionRegistry.session() = handles.value.values.single().session!!

    private fun TerminalSession.awaitText(text: String) = await(text) { text in readText() }

    // The snapshots, not the file that one is being written to.
    private fun files() = if (Files.isDirectory(directory)) Files.list(directory).use { list -> list.filter { it.fileName.toString().endsWith(".snapshot") }.toList() } else emptyList()

    @Test
    fun whatATerminalShowedComesBackWhenTheRegistryIsMadeAgain() {
        store.addProject("/tmp")
        val first = registry().also { it.sync(store.state.value) }
        first.session().awaitText("first")

        first.close()

        assertEquals(1, files().size, "Saved when the registry was closed")
        val second = registry().also { it.sync(store.state.value) }
        val text = second.session().also { it.awaitText("second") }.readText().lines()
        assertTrue(text.indexOf("first") < text.indexOf("Restored"), "What it showed is there, then the note: $text")
        assertTrue(text.indexOf("Restored") < text.indexOf("second"), "And its new shell below: $text")
    }

    @Test
    fun aTerminalThatNothingHasSavedStartsPlain() {
        store.addProject("/tmp")

        val registry = registry().also { it.sync(store.state.value) }

        val text = registry.session().also { it.awaitText("first") }.readText()
        assertFalse("Restored" in text)
    }

    @Test
    fun reopeningTheShellStartsAPlainOneAndForgetsWhatWasShown() {
        store.addProject("/tmp")
        registry().also { it.sync(store.state.value); it.session().awaitText("first") }.close()
        val second = registry().also { it.sync(store.state.value) }
        second.session().awaitText("second")
        val id = store.state.value.selection.terminal!!

        second.restart(id)

        // The shell that was started again says `first` (a plain one), and has none of what the old one showed.
        await("the new shell") { second.handles.value[id]?.session?.let { "first" in it.readText() } == true }
        val text = second.session().readText()
        assertFalse("Restored" in text, "A new shell is a plain one: $text")
        assertFalse("second" in text)
    }

    @Test
    fun aTerminalThatIsClosedLeavesNoSnapshotBehind() {
        val project = store.addProject("/tmp")
        store.split(store.state.value.selection.terminal!!, SplitDirection.ROW)
        val registry = registry().also { it.sync(store.state.value) }
        await("both shells") { registry.handles.value.size == 2 && registry.handles.value.values.all { it.session != null } }
        registry.close()
        assertEquals(2, files().size)

        val again = registry().also { it.sync(store.state.value) }
        val gone = store.state.value.selection.terminal!!
        store.closeTerminal(gone)
        again.sync(store.state.value)

        await("the snapshot of the closed terminal to go") { files().size == 1 }
        assertNotNull(project)
    }

    @Test
    fun withTheSettingOffNothingIsSavedOrRestoredAndWhatWasKeptIsDeleted() {
        store.addProject("/tmp")
        registry().also { it.sync(store.state.value); it.session().awaitText("first") }.close()
        assertEquals(1, files().size)

        store.updateSettings { it.copy(restoreContents = false) }
        val off = registry().also { it.sync(store.state.value) }

        val text = off.session().also { it.awaitText("first") }.readText()
        assertFalse("Restored" in text, "Not restored: $text")
        await("the files to be deleted") { files().isEmpty() }
        off.close()
        assertTrue(files().isEmpty(), "And not saved when it is closed")
    }

    @Test
    fun theTerminalsAreSavedWhileTheyRunAndOnlyWhenTheyChanged() {
        store.addProject("/tmp")
        val registry = registry(autosaveMillis = 100).also { it.sync(store.state.value) }
        registry.session().awaitText("first")

        await("the first save") { files().isNotEmpty() }
        // The shell's output may still be coming in, and is saved as it does: it is settled when no save has been made for a while.
        var saved = Files.getLastModifiedTime(files().single())
        var settled = 0
        while (settled < 5) {
            Thread.sleep(100)
            val now = Files.getLastModifiedTime(files().single())
            if (now == saved) settled++ else { saved = now; settled = 0 }
        }
        Thread.sleep(600)

        assertEquals(saved, Files.getLastModifiedTime(files().single()), "A terminal that has not changed is not saved again")
        registry.session().paste("x")
        await("a save of what changed") { Files.getLastModifiedTime(files().single()) > saved }
    }
}
