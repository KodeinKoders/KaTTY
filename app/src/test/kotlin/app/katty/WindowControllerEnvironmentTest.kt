package app.katty

import app.katty.core.KattyStore
import app.katty.core.SplitDirection
import app.katty.core.TerminalId
import app.katty.core.WorkspaceId
import app.katty.core.terminals
import app.katty.screens.paletteCommands
import app.katty.terminals.TerminalHandle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import java.nio.file.Files
import kotlin.io.path.isDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WindowControllerEnvironmentTest {
    private var counter = 0
    private val store = KattyStore(newId = { "id${counter++}" })
    private val restarted = mutableListOf<Collection<TerminalId>>()
    private val revealed = mutableListOf<String>()
    private var folderAnswer: String? = null
    private val platform = object : WindowPlatform {
        override fun chooseFolder(title: String, initial: String?): String? = folderAnswer
        override fun chooseSaveFile(title: String, directory: String?, name: String): String? = null
        override fun copy(text: String) = Unit
        override fun readClipboard(): String? = null
        override fun reveal(directory: String) {
            revealed += directory
        }
        override fun openIn(bundleId: String, directory: String) = Unit
        override fun openFile(ide: app.katty.core.Ide, bundleId: String, file: app.katty.terminal.TerminalFilePosition) = Unit
        override fun openBoardWindow() = Unit
        override fun openAbout() = Unit
    }
    private val controller = WindowController(
        store, CoroutineScope(Dispatchers.Unconfined), platform, {}, {}, restartWithEnvironment = { restarted += it },
    )

    private val state get() = store.state.value

    init {
        store.updateSettings { it.copy(defaultEnvironment = "kodein") }
        store.addProject("/tmp/project")
        store.split(state.selection.terminal!!, SplitDirection.ROW)
    }

    private val project get() = state.projects.single()
    private val workspace get() = project.workspaces.first()

    private fun workspace(id: WorkspaceId) = project.workspaces.first { it.id == id }

    // A terminal whose shell runs a program: changing the environment of its workspace asks first.
    private fun runProgram(id: TerminalId) {
        val handle = TerminalHandle(id, session = null)
        handle.processState.value = "vim"
        controller.handles = mapOf(id to handle)
    }

    @Test
    fun aWorkspaceWithNothingRunningIsStartedAgainAtOnceWithItsNewEnvironment() {
        controller.setWorkspaceEnvironment(workspace.id, "perso")

        assertNull(controller.environmentRestart)
        assertEquals("perso", workspace.environment)
        assertEquals<List<Collection<TerminalId>>>(listOf(workspace.layout.terminals().map { it.id }), restarted)
    }

    @Test
    fun aWorkspaceWhereAProgramRunsIsAskedFirstAndCancellingChangesNothing() {
        runProgram(workspace.layout.terminals().first().id)

        controller.setWorkspaceEnvironment(workspace.id, "perso")

        assertEquals(EnvironmentRestart(setOf(workspace.id), "perso", later = false), controller.environmentRestart)
        assertTrue(controller.blockingDialog)
        controller.answerEnvironmentRestart(now = false)
        assertEquals("kodein", workspace.environment)
        assertEquals(emptyList<Collection<TerminalId>>(), restarted)

        controller.setWorkspaceEnvironment(workspace.id, "perso")
        controller.answerEnvironmentRestart(now = true)
        assertEquals("perso", workspace.environment)
        assertEquals(1, restarted.size)
    }

    @Test
    fun aWorkspaceIsGivenTheEnvironmentOfItsProject() {
        controller.setWorkspaceEnvironment(workspace.id, null)
        restarted.clear()

        controller.useProjectEnvironment(workspace.id)

        assertEquals("kodein", workspace.environment)
        assertEquals(1, restarted.size)
    }

    @Test
    fun changingTheEnvironmentOfAProjectOffersItsWorkspacesToStartAgainNowOrLater() {
        val own = store.addWorkspace(project.id, "own")!!
        controller.setWorkspaceEnvironment(own, "perso")
        restarted.clear()
        val first = workspace.id

        controller.setProjectEnvironment(project.id, "sunday")

        assertEquals("sunday", project.environment)
        assertEquals(EnvironmentRestart(setOf(first), "sunday", later = true), controller.environmentRestart, "Not the one with its own")
        controller.answerEnvironmentRestart(now = false)
        assertEquals("kodein", workspace(first).environment, "Later: it keeps its own")
        assertEquals(emptyList<Collection<TerminalId>>(), restarted)

        controller.setProjectEnvironment(project.id, "kodein")
        controller.setProjectEnvironment(project.id, "sunday")
        controller.answerEnvironmentRestart(now = true)
        assertEquals("sunday", workspace(first).environment)
        assertEquals("perso", workspace(own).environment)
        assertEquals<List<Collection<TerminalId>>>(listOf(workspace(first).layout.terminals().map { it.id }), restarted)
    }

    @Test
    fun theDefaultIsAppliedFromTheSettingsWhichAskThemselves() {
        store.setProjectEnvironment(project.id, null)
        store.setWorkspacesEnvironment(setOf(workspace.id), null)

        controller.applyDefaultEnvironment()

        assertEquals("kodein", project.environment)
        assertEquals(EnvironmentRestart(setOf(workspace.id), "kodein", later = true, fromSettings = true), controller.environmentRestart)
        assertEquals(false, controller.blockingDialog, "The settings window shows it, not the main one")
        controller.answerEnvironmentRestart(now = true)
        assertEquals("kodein", workspace.environment)
    }

    @Test
    fun aFolderOfEnvironmentsIsAddedLastWithTheHomeWrittenAsATilde() {
        folderAnswer = System.getProperty("user.home") + "/secrets/katty"

        controller.addEnvironmentFolder()
        controller.addEnvironmentFolder()

        assertEquals(listOf("~/.config/KaTTY/environments", "~/secrets/katty"), state.settings.environmentDirectories)
    }

    @Test
    fun theFolderOfEnvironmentsIsMadeBeforeItIsShown() {
        val folder = Files.createTempDirectory("katty-environments").resolve("not/yet")
        try {
            controller.openEnvironmentsFolder(folder.toString())

            assertTrue(folder.isDirectory())
            assertEquals(listOf(folder.toString()), revealed)
        } finally {
            folder.parent.parent.toFile().deleteRecursively()
        }
    }

    @Test
    fun thePaletteGivesTheProjectAndTheWorkspaceTheEnvironmentsTheyDoNotHave() {
        controller.environmentNames = setOf("kodein", "perso")
        val commands = paletteCommands(state, emptyMap(), titleOf = { "t" }, handlers = controller.paletteHandlers())
        val labels = commands.entries.map { it.label }

        assertTrue("Project environment: None" in labels && "Project environment: perso" in labels, labels.toString())
        assertTrue("Project environment: kodein" !in labels, "The one it has")
        assertTrue("Workspace environment: perso" in labels)

        commands.run(commands.entries.first { it.id == "command:project-environment:perso" })
        assertEquals("perso", project.environment)
    }
}
