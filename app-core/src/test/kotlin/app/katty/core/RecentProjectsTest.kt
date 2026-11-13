package app.katty.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecentProjectsTest {
    private val directory: Path = Files.createTempDirectory("katty-recent")
    private val path = directory.resolve("recent-projects.json")
    private val file = RecentProjectsFile(path)
    private var ids = 0
    private val store = KattyStore(newId = { "id${ids++}" })
    private var time = 0L
    private val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
    private val recent = RecentProjects(store, file, scope, clock = { ++time })

    @AfterTest
    fun cleanUp() {
        recent.stop()
        directory.toFile().deleteRecursively()
    }

    private fun folder(name: String) = directory.resolve(name).also { it.createDirectories() }.toString()

    private fun directories() = recent.entries.value.map { it.directory }

    @Test
    fun aProjectThatIsAddedIsRecorded() {
        val a = folder("a")
        store.addProject(a)

        assertEquals(listOf(a), directories())
        assertEquals(listOf(RecentProject(a, "a", 1)), file.load())
    }

    @Test
    fun aProjectThatIsRemovedIsRecordedAgainFirstWithTheNameItHad() {
        val a = folder("a")
        val b = folder("b")
        val id = store.addProject(a)
        store.addProject(b)
        store.renameProject(id, "Alpha")

        store.removeProject(id)

        assertEquals(listOf(a, b), directories())
        assertEquals("Alpha", recent.entries.value.first().name)
    }

    @Test
    fun aFolderIsKeptOnceAndComesFirstWhenItIsOpenedAgain() {
        val a = folder("a")
        val b = folder("b")
        store.addProject(a)
        store.addProject(b)

        store.removeProject(store.state.value.projects.first { it.directory == a }.id)
        store.addProject(a)

        assertEquals(listOf(a, b), directories())
    }

    @Test
    fun onlyTheFiftyLastAreKept() {
        repeat(RecentProjects.MAX + 5) { recent.record("/p$it", "p$it") }

        assertEquals(RecentProjects.MAX, recent.entries.value.size)
        assertEquals("/p${RecentProjects.MAX + 4}", recent.entries.value.first().directory)
        assertEquals(RecentProjects.MAX, file.load().size)
    }

    @Test
    fun closedLeavesOutTheOpenProjectsAndTheFoldersThatAreGone() {
        val open = folder("open")
        val closed = folder("closed")
        store.addProject(open)
        recent.record(closed, "closed")
        recent.record(directory.resolve("gone").toString(), "gone")

        assertEquals(listOf(closed), recent.closed(store.state.value).map { it.directory })
    }

    @Test
    fun theListIsReadBackFromTheFile() {
        recent.record("/x", "x")

        val again = RecentProjects(KattyStore(), file, scope)

        assertEquals(listOf("/x"), again.entries.value.map { it.directory })
        again.stop()
    }

    @Test
    fun aFileThatCannotBeReadOrIsOfALaterVersionIsAnEmptyListAndIsWrittenOverNext() {
        path.writeText("not json")
        assertTrue(file.load().isEmpty())

        path.writeText("""{"version": 99, "projects": [{"directory": "/x", "name": "x", "openedAt": 1}]}""")
        assertTrue(file.load().isEmpty())

        file.save(listOf(RecentProject("/y", "y", 2)))
        assertEquals("/y", file.load().single().directory)
    }

    @Test
    fun aRemovedProjectIsRecordedWithItsStateBeforeRemoveProjectReturns() {
        val a = folder("a")
        val id = store.addProject(a)
        val project = store.state.value.projects.single()
        val terminal = project.workspaces.single().layout.terminals().single().id
        // No collector: only what the store tells as it removes.
        val apart = RecentProjects(store, RecentProjectsFile(directory.resolve("apart.json")), CoroutineScope(Job()))

        store.removeProject(id)

        assertEquals(project, apart.saved(a))
        assertEquals(setOf(terminal), apart.savedTerminals.value)
        assertEquals(project, RecentProjectsFile(directory.resolve("apart.json")).load().single().saved, "It is in the file")
        apart.stop()
    }

    @Test
    fun aProjectWithNoWorkspaceKeepsNoState() {
        val a = folder("a")
        val id = store.addProject(a)
        store.closeWorkspace(store.state.value.projects.single().workspaces.single().id)

        store.removeProject(id)

        assertNull(recent.saved(a))
        assertEquals(emptySet(), recent.savedTerminals.value)
    }

    @Test
    fun addingTheFolderAgainDropsItsState() {
        val a = folder("a")
        store.removeProject(store.addProject(a))
        assertNotNull(recent.saved(a))

        store.addProject(a)

        assertNull(recent.saved(a))
        assertEquals(emptySet(), recent.savedTerminals.value)
        assertEquals(listOf(a), directories())
    }

    @Test
    fun aDroppedStateIsGoneFromTheListAndTheFile() {
        val a = folder("a")
        val b = folder("b")
        store.removeProject(store.addProject(a))
        store.removeProject(store.addProject(b))

        recent.dropSaved(a)

        assertNull(recent.saved(a))
        assertNotNull(recent.saved(b))
        assertEquals(recent.saved(b)!!.workspaces.single().layout.terminals().map { it.id }.toSet(), recent.savedTerminals.value)
        assertEquals(listOf(b, a), directories(), "The folder stays in the list")
        assertEquals(listOf(null), file.load().filter { it.directory == a }.map { it.saved })
    }

    @Test
    fun theStateOfAnEntryThatFallsOffTheListIsForgotten() {
        val a = folder("a")
        store.removeProject(store.addProject(a))

        repeat(RecentProjects.MAX) { recent.record("/p$it", "p$it") }

        assertEquals(emptySet(), recent.savedTerminals.value)
    }

    @Test
    fun theStateIsReadBackFromTheFileAndAFileWithoutItIsRead() {
        val a = folder("a")
        store.removeProject(store.addProject(a))

        val again = RecentProjects(KattyStore(), file, scope)

        assertEquals(recent.saved(a), again.saved(a))
        assertEquals(recent.savedTerminals.value, again.savedTerminals.value)
        again.stop()

        path.writeText("""{"version": 1, "projects": [{"directory": "/x", "name": "x", "openedAt": 1}]}""")
        assertEquals(listOf(RecentProject("/x", "x", 1)), file.load())
    }
}
