package app.katty.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StateFileTest {
    private val directory: Path = Files.createTempDirectory("katty-state")
    private val path = directory.resolve("state.json")
    private val file = StateFile(path)

    @AfterTest
    fun cleanUp() {
        directory.toFile().deleteRecursively()
    }

    private fun built(): KattyState {
        var n = 0
        val store = KattyStore(newId = { "id${n++}" })
        val project = store.addProject("/dev/api-gateway")
        val first = store.state.value.selection.terminal!!
        val second = store.split(first, SplitDirection.ROW)!!
        store.split(second, SplitDirection.COLUMN)
        store.renameTerminal(first, "claude")
        store.setColorScheme(second, "Nord")
        store.addWorkspace(project, "release-notes")
        store.setProjectOpen(project, false)
        store.updateSettings { it.copy(theme = ThemeMode.DARK, layout = LayoutMode.VERTICAL, fontSize = 14f, ligatures = false, view = MainView.BOARD, usageHidden = setOf(usageKey(Agent.CLAUDE, "Week (Sonnet only)"))) }
        return store.state.value
    }

    @Test
    fun aRunningProgramSurvivesBeingWrittenAndReadAndAnOldFileHasNone() {
        var n = 0
        val store = KattyStore(newId = { "id${n++}" })
        store.addProject("/dev/api")
        val terminal = store.state.value.selection.terminal!!
        store.setRunning(terminal, RunningCommand(listOf("claude", "--resume", "abc"), "/dev/api/sub", "claude", "abc"))
        val state = store.state.value

        file.save(state)

        assertEquals(state, file.load())
        assertEquals("abc", file.load().locate(terminal)!!.terminal.running!!.agentSession)
        store.setRunning(terminal, null)
        file.save(store.state.value)
        assertFalse("argv" in path.readText(), "A terminal at its shell has nothing of the command in the file")
        assertEquals(null, file.load().locate(terminal)!!.terminal.running)
    }

    @Test
    fun theTerminalThatHadTheFocusInAWorkspaceSurvivesBeingWrittenAndReadAndAnOldFileHasNone() {
        var n = 0
        val store = KattyStore(newId = { "id${n++}" })
        store.addProject("/dev/api")
        val first = store.state.value.selection.terminal!!
        val second = store.split(first, SplitDirection.ROW)!!
        store.focus(first)
        store.focus(second)
        val state = store.state.value

        file.save(state)

        assertEquals(second, file.load().selectedWorkspace!!.focused)
        // A file that was written before the field existed has none, and the first terminal has the focus.
        path.writeText(path.readText().replace(Regex(""",\s*"focused":\s*("[^"]*"|null)"""), ""))
        assertEquals(null, file.load().selectedWorkspace!!.focused)
    }

    @Test
    fun theShortcutsThatWereChangedSurviveBeingWrittenAndReadAndAnOldFileHasNone() {
        var n = 0
        val store = KattyStore(newId = { "id${n++}" })
        store.addProject("/dev/api")
        store.updateSettings { it.copy(shortcuts = mapOf("SPLIT_MAIN" to "meta+shift:69", "FIND" to "none")) }
        val state = store.state.value

        file.save(state)

        assertEquals(mapOf("SPLIT_MAIN" to "meta+shift:69", "FIND" to "none"), file.load().settings.shortcuts)
        // A file that was written before the setting existed has none: every shortcut is its default.
        path.writeText(path.readText().replace(Regex(",\\s*\"shortcuts\":\\s*\\{[^}]*\\}"), ""))
        assertEquals(emptyMap(), file.load().settings.shortcuts)
    }

    @Test
    fun noFileIsAnEmptyState() {
        assertEquals(KattyState(), file.load())
    }

    @Test
    fun aStateSurvivesBeingWrittenAndRead() {
        val state = built()

        file.save(state)

        assertEquals(state, file.load())
    }

    @Test
    fun theTerminalThatIsMaximizedIsNotKept() {
        val state = built()
        val maximized = state.copy(selection = state.selection.copy(maximized = state.selection.terminal))

        file.save(maximized)

        assertEquals(state, file.load())
    }

    @Test
    fun writingLeavesNoTemporaryFile() {
        file.save(built())

        assertEquals(listOf("state.json"), directory.listDirectoryEntries().map { it.fileName.toString() })
    }

    @Test
    fun aDamagedFileIsKeptAsideAndTheStateIsEmpty() {
        path.writeText("{ not json")

        assertEquals(KattyState(), file.load())

        assertFalse(path.exists())
        assertEquals("{ not json", directory.resolve("state.json.damaged").readText())
    }

    @Test
    fun aFileThatDoesNotMatchTheFormatIsKeptAsideToo() {
        path.writeText("""{"version":1,"projects":[{"id":"p"}]}""")

        assertEquals(KattyState(), file.load())

        assertTrue(directory.resolve("state.json.damaged").exists())
    }

    @Test
    fun aFileOfALaterVersionIsKeptAsideAndNotWrittenOver() {
        path.writeText("""{"version":99,"projects":[]}""")

        assertEquals(KattyState(), file.load())

        assertEquals("""{"version":99,"projects":[]}""", directory.resolve("state.json.version-99").readText())
    }

    @Test
    fun aSecondDamagedFileDoesNotReplaceTheFirstOneThatWasKept() {
        path.writeText("one")
        file.load()
        path.writeText("two")
        file.load()

        assertEquals("one", directory.resolve("state.json.damaged").readText())
        assertEquals("two", directory.resolve("state.json.damaged.1").readText())
    }

    @Test
    fun aFileFromBeforeTheLimitsCouldBeHiddenShowsAllOfThem() {
        val state = built().let { it.copy(settings = it.settings.copy(usageHidden = emptySet())) }
        file.save(state)
        path.writeText(path.readText().replace(Regex(""","?\s*"usageHidden"\s*:\s*\[\s*\]"""), ""))
        check("usageHidden" !in path.readText())

        assertEquals(state, file.load())
    }

    @Test
    fun whatALaterVersionAddedIsIgnoredAsLongAsTheVersionIsOurs() {
        val state = built()
        file.save(state)
        path.writeText(path.readText().replaceFirst("{", """{"somethingNew": 1,"""))

        assertEquals(state, file.load())
    }

    @Test
    fun theSelectionOfAFileIsMadeToPointAtSomething() {
        val state = built()
        file.save(state.copy(selection = Selection(ProjectId("gone"), WorkspaceId("gone"), TerminalId("gone"))))

        val loaded = file.load()

        assertEquals(state.projects.first().id, loaded.selection.project)
        assertEquals(state.projects.first().workspaces.first().id, loaded.selection.workspace)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun theStateIsWrittenOnceAfterAnActivityThatChangesItManyTimes() {
        val scope = TestScope(StandardTestDispatcher())
        var n = 0
        val store = KattyStore(newId = { "id${n++}" })
        val persistence = StatePersistence(store, file, scope.backgroundScope, delay = kotlin.time.Duration.parse("500ms"))
        scope.runCurrent()

        val project = store.addProject("/a")
        scope.advanceTimeBy(100)
        store.addWorkspace(project)
        scope.advanceTimeBy(100)
        store.addWorkspace(project)
        scope.advanceTimeBy(400)
        assertFalse(path.exists(), "Not yet: the state is still changing")
        scope.advanceTimeBy(200)
        scope.runCurrent()

        assertEquals(store.state.value, file.load())
        persistence.stop()
    }

    @Test
    fun flushingWritesAtOnce() {
        val scope = TestScope(StandardTestDispatcher())
        val store = KattyStore()
        val persistence = StatePersistence(store, file, scope.backgroundScope)
        store.addProject("/a")

        persistence.flush()

        assertEquals(store.state.value, file.load())
        persistence.stop()
    }
}
