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
}
