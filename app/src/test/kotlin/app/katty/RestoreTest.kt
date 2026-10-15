package app.katty

import app.katty.core.KattyStore
import app.katty.core.PaneNode
import app.katty.core.SplitDirection
import app.katty.core.StateFile
import app.katty.core.terminals
import app.katty.terminal.TerminalSession
import app.katty.terminals.SessionRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** What the user sees when the application is started again: the tree comes back, and every terminal has a new shell where it was. */
class RestoreTest {
    @Test
    fun aSavedStateComesBackWithItsTreesAndNewShellsInTheirDirectories() {
        val support = Files.createTempDirectory("katty-restore")
        val projectA = Files.createTempDirectory("katty-a").toRealPath()
        val projectB = Files.createTempDirectory("katty-b").toRealPath()
        val inner = Files.createDirectories(projectA.resolve("src"))
        val file = StateFile(support.resolve("state.json"))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            // The first run: two projects, a tree of splits, a terminal that went into a folder, a name, a scheme and a setting.
            var n = 0
            val first = KattyStore(newId = { "id${n++}" })
            val a = first.addProject(projectA.toString())
            first.renameWorkspace(first.state.value.selection.workspace!!, "refactor-auth")
            val left = first.state.value.selection.terminal!!
            val right = first.split(left, SplitDirection.ROW)!!
            val bottom = first.split(right, SplitDirection.COLUMN)!!
            first.setDirectory(bottom, inner.toString())
            first.renameTerminal(left, "claude")
            first.setColorScheme(right, "Nord")
            first.addProject(projectB.toString())
            first.selectProject(a)
            first.updateSettings { it.copy(ligatures = false, fontSize = 14f) }
            file.save(first.state.value)

            // The second run: the file is read, and a shell is started for each terminal.
            val second = KattyStore(file.load())
            val started = mutableMapOf<String, String>()
            val registry = SessionRegistry(
                second, scope, loadTheme = { null },
                start = { spec, _ ->
                    started[spec.id.value] = spec.directory
                    TerminalSession.start(80, 5, command = listOf("/bin/sleep", "30"), directory = spec.directory)
                },
            )
            registry.sync(second.state.value)

            assertEquals(first.state.value.projects, second.state.value.projects)
            assertEquals(first.state.value.settings, second.state.value.settings)
            assertEquals(first.state.value.selection, second.state.value.selection)
            val tree = second.state.value.selectedWorkspace!!.layout
            assertTrue(tree is PaneNode.Split)
            assertEquals(listOf("claude", null, null), tree.terminals().map { it.name })
            assertEquals(4, started.size, "A shell for each terminal, in every project")
            assertEquals(inner.toString(), started.getValue(bottom.value), "Where the terminal had gone")
            assertEquals(projectA.toString(), started.getValue(left.value))
            assertEquals(4, registry.handles.value.size)
            registry.handles.value.values.forEach { assertNotNull(it.session) }
            registry.close()
        } finally {
            scope.cancel()
            support.toFile().deleteRecursively()
            projectA.toFile().deleteRecursively()
            projectB.toFile().deleteRecursively()
        }
    }
}
