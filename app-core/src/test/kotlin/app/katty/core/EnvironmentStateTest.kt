package app.katty.core

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EnvironmentStateTest {
    private var counter = 0
    private val store = KattyStore(newId = { "id${counter++}" })
    private val directory: Path = Files.createTempDirectory("katty-environment-state")

    @AfterTest
    fun cleanUp() {
        directory.toFile().deleteRecursively()
    }

    private val state get() = store.state.value

    private fun project(id: ProjectId) = state.projects.first { it.id == id }

    private fun workspace(id: WorkspaceId) = state.projects.flatMap { it.workspaces }.first { it.id == id }

    @Test
    fun aStateWrittenBeforeEnvironmentsHasNone() {
        val path = directory.resolve("state.json")
        path.writeText(
            """
            {"version": ${KattyState.VERSION}, "settings": {"fontSize": 13},
             "projects": [{"id": "p", "name": "p", "directory": "/p", "workspaces": [{"id": "w", "name": "w", "layout": {"type": "leaf", "terminal": {"id": "t", "directory": "/p"}}}]}]}
            """.trimIndent(),
        )

        val loaded = StateFile(path).load()

        assertNull(loaded.projects.single().environment)
        assertNull(loaded.projects.single().workspaces.single().environment)
        assertEquals(listOf("~/.config/KaTTY/environments"), loaded.settings.environmentDirectories)
        assertNull(loaded.settings.defaultEnvironment)
        assertEquals(emptyMap(), loaded.settings.environmentColors)
        assertEquals(emptyList(), loaded.settings.environmentFolders)
    }

    @Test
    fun theEnvironmentsAreKeptInTheFile() {
        store.updateSettings {
            it.copy(
                defaultEnvironment = "kodein", environmentDirectories = listOf("~/a", "/b"),
                environmentFolders = listOf(EnvironmentFolder("~/dev/kosi-libs", "kodein-koders"), EnvironmentFolder("/dev/private", null)),
            )
        }
        val id = store.addProject("/dev/api")
        store.setProjectEnvironment(id, "sunday")
        val path = directory.resolve("state.json")

        StateFile(path).save(state)
        val loaded = StateFile(path).load()

        assertEquals("sunday", loaded.projects.single().environment)
        assertEquals("kodein", loaded.projects.single().workspaces.single().environment)
        assertEquals(listOf("~/a", "/b"), loaded.settings.environmentDirectories)
        assertEquals("kodein", loaded.settings.defaultEnvironment)
        assertEquals(listOf(EnvironmentFolder("~/dev/kosi-libs", "kodein-koders"), EnvironmentFolder("/dev/private", null)), loaded.settings.environmentFolders, "A folder that says none is kept")
    }

    @Test
    fun aProjectAndItsWorkspacesAreGivenTheDefaultAsItIsWhenTheyAreMade() {
        val standard = store.addProject("/dev/standard")
        store.updateSettings { it.copy(defaultEnvironment = "kodein") }
        val kodein = store.addProject("/dev/kodein")
        store.updateSettings { it.copy(defaultEnvironment = "perso") }
        val second = store.addWorkspace(kodein, "second")!!

        assertNull(project(standard).environment, "Added before there was a default")
        assertNull(project(standard).workspaces.single().environment)
        assertEquals("kodein", project(kodein).environment, "The default does not follow the settings")
        assertEquals("kodein", project(kodein).workspaces.first().environment)
        assertEquals("kodein", workspace(second).environment, "A new workspace has its project's")
    }

    @Test
    fun aProjectUnderAFolderIsGivenItsEnvironmentAndNotTheDefaultOne() {
        store.updateSettings {
            it.copy(
                defaultEnvironment = "default",
                environmentFolders = listOf(EnvironmentFolder("/dev/kosi-libs", "kodein"), EnvironmentFolder("/dev/kosi-libs/vie", "perso"), EnvironmentFolder("/dev/private", null)),
            )
        }

        val api = store.addProject("/dev/kosi-libs/api")
        val vie = store.addProject("/dev/kosi-libs/vie/app")
        val hidden = store.addProject("/dev/private/notes")
        val other = store.addProject("/dev/other")
        val second = store.addWorkspace(api, "second")!!

        assertEquals("kodein", project(api).environment)
        assertEquals("kodein", project(api).workspaces.first().environment, "Its first workspace has it too")
        assertEquals("kodein", workspace(second).environment)
        assertEquals("perso", project(vie).environment, "The deepest folder")
        assertNull(project(hidden).environment, "A folder that says none is not the default one")
        assertNull(project(hidden).workspaces.single().environment)
        assertEquals("default", project(other).environment, "No folder: the default one")
    }

    @Test
    fun aFolderIsAppliedToTheProjectsThatAreUnderItAndHaveAnotherEnvironment() {
        val api = store.addProject("/dev/kosi-libs/api")
        val vie = store.addProject("/dev/kosi-libs/vie/app")
        val same = store.addProject("/dev/kosi-libs/same")
        val other = store.addProject("/dev/other")
        store.setProjectEnvironment(same, "kodein")
        store.setProjectEnvironment(other, "sunday")
        val followed = project(api).workspaces.single().id
        val own = store.addWorkspace(api, "own")!!
        store.setWorkspacesEnvironment(setOf(own), "perso")
        store.updateSettings { it.copy(environmentFolders = listOf(EnvironmentFolder("/dev/kosi-libs", "kodein"), EnvironmentFolder("/dev/kosi-libs/vie", "perso"))) }

        assertEquals(listOf(api), state.projectsOffFolder("/dev/kosi-libs").map { it.id }, "Not the one that has it, nor the one of a folder below, nor the others")
        val following = store.applyFolderEnvironment("/dev/kosi-libs")

        assertEquals(setOf(followed), following, "The workspace with an environment of its own did not follow")
        assertEquals("kodein", project(api).environment)
        assertNull(workspace(followed).environment, "It is only changed when its shells are started again")
        assertEquals("perso", workspace(own).environment)
        assertNull(project(vie).environment, "It is under another folder")
        assertEquals("kodein", project(same).environment)
        assertEquals("sunday", project(other).environment)
        assertEquals(emptyList(), state.projectsOffFolder("/dev/kosi-libs"))
        assertEquals(emptySet(), store.applyFolderEnvironment("/dev/kosi-libs"), "Nothing is left to change")
        assertEquals(setOf(project(vie).workspaces.single().id), store.applyFolderEnvironment("/dev/kosi-libs/vie"))
        assertEquals("perso", project(vie).environment)
    }

    @Test
    fun aFolderThatSaysNoneTakesTheEnvironmentOffItsProjects() {
        val api = store.addProject("/dev/private/api")
        store.setProjectEnvironment(api, "kodein")
        val followed = project(api).workspaces.single().id
        store.setWorkspacesEnvironment(setOf(followed), "kodein")
        store.updateSettings { it.copy(environmentFolders = listOf(EnvironmentFolder("/dev/private", null))) }

        assertEquals(setOf(followed), store.applyFolderEnvironment("/dev/private"))

        assertNull(project(api).environment)
    }

    @Test
    fun aFolderThatIsNotInTheSettingsChangesNothing() {
        val api = store.addProject("/dev/kosi-libs/api")
        store.updateSettings { it.copy(environmentFolders = listOf(EnvironmentFolder("/dev/kosi-libs", "kodein"))) }

        assertEquals(emptySet(), store.applyFolderEnvironment("/dev/elsewhere"))
        assertEquals(emptyList(), state.projectsOffFolder("/dev/elsewhere"))

        assertNull(project(api).environment)
    }

    @Test
    fun changingTheEnvironmentOfAProjectLeavesItsWorkspacesAndTellsWhichFollowedIt() {
        store.updateSettings { it.copy(defaultEnvironment = "kodein") }
        val id = store.addProject("/dev/api")
        val first = project(id).workspaces.single().id
        val own = store.addWorkspace(id, "own")!!
        store.setWorkspacesEnvironment(setOf(own), "perso")

        val following = store.setProjectEnvironment(id, "sunday")

        assertEquals(setOf(first), following, "The workspace with an environment of its own did not follow")
        assertEquals("sunday", project(id).environment)
        assertEquals("kodein", workspace(first).environment, "Its shells were started with it")
        assertEquals("perso", workspace(own).environment)
        val added = store.addWorkspace(id, "added")!!
        assertEquals("sunday", workspace(added).environment)
        assertEquals(emptySet(), store.setProjectEnvironment(id, "sunday"), "No workspace is offered for the environment it already had")
    }

    @Test
    fun theDefaultIsAppliedToTheProjectsThatHaveNone() {
        val a = store.addProject("/dev/a")
        val b = store.addProject("/dev/b")
        store.setProjectEnvironment(b, "sunday")
        val aWorkspace = project(a).workspaces.single().id
        val own = store.addWorkspace(a, "own")!!
        store.setWorkspacesEnvironment(setOf(own), "perso")

        assertEquals(listOf(a), state.projectsWithoutEnvironment().map { it.id })
        val following = store.applyEnvironmentToProjectsWithout("kodein")

        assertEquals(setOf(aWorkspace), following)
        assertEquals("kodein", project(a).environment)
        assertEquals("sunday", project(b).environment)
        assertNull(workspace(aWorkspace).environment, "It is only changed when its shells are started again")
        assertEquals(emptyList(), state.projectsWithoutEnvironment())
    }

    @Test
    fun aProjectThatIsOpenedAgainKeepsItsEnvironments() {
        val id = store.addProject("/dev/api")
        store.setProjectEnvironment(id, "sunday")
        store.setWorkspacesEnvironment(project(id).workspaces.map { it.id }.toSet(), "sunday")
        val saved = project(id)
        store.removeProject(id)
        store.updateSettings { it.copy(defaultEnvironment = "kodein") }

        store.restoreProject(saved)

        assertEquals("sunday", project(id).environment)
        assertEquals("sunday", project(id).workspaces.single().environment)
        assertEquals("sunday", state.environmentOf(project(id).workspaces.single().id))
    }

    @Test
    fun theBadgesSayWhatDiffersFromTheProject() {
        val available = setOf("kodein", "perso")
        val workspace = Workspace(WorkspaceId("w"), "w", PaneNode.Leaf(TerminalSpec(TerminalId("t"), directory = "/")))
        val kodein = Project(ProjectId("p"), "p", "/", environment = "kodein")
        val standard = kodein.copy(environment = null)

        assertEquals(EnvironmentBadge("kodein"), kodein.environmentBadge(available))
        assertNull(standard.environmentBadge(available), "No badge for a project with no environment")
        assertEquals(EnvironmentBadge("sunday", missing = true), kodein.copy(environment = "sunday").environmentBadge(available))

        assertNull(workspace.copy(environment = "kodein").environmentBadge(kodein, available), "The project's")
        assertEquals(EnvironmentBadge("perso"), workspace.copy(environment = "perso").environmentBadge(kodein, available))
        assertEquals(EnvironmentBadge("standard"), workspace.environmentBadge(kodein, available))
        assertNull(workspace.environmentBadge(standard, available))
        assertEquals(EnvironmentBadge("gone", missing = true), workspace.copy(environment = "gone").environmentBadge(standard, available))

        assertEquals(EnvironmentBadge("kodein"), workspace.copy(environment = "kodein").terminalEnvironmentBadge(kodein, available))
        assertEquals(EnvironmentBadge("standard"), workspace.terminalEnvironmentBadge(kodein, available))
        assertNull(workspace.terminalEnvironmentBadge(standard, available))
    }
}
