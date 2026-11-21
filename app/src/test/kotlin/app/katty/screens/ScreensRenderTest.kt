package app.katty.screens

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import app.katty.core.KattyStore
import app.katty.ui.render
import kotlin.test.Test
import kotlin.test.assertTrue

class ScreensRenderTest {
    private fun store(): KattyStore {
        var n = 0
        val store = KattyStore(newId = { "id${n++}" })
        store.addProject("/Users/me/dev/web-client")
        store.addProject("/Users/me/dev/docs-site")
        val gateway = store.addProject("/Users/me/dev/api-gateway")
        store.renameProject(gateway, "api-gateway")
        store.renameWorkspace(store.state.value.selection.workspace!!, "refactor-auth")
        store.addWorkspace(gateway, "rate-limiter")
        store.addWorkspace(gateway, "release-notes")
        store.selectWorkspace(store.state.value.projects.last().workspaces.first().id)
        return store
    }

    @Composable
    private fun SidebarOf(store: KattyStore) {
        val state by store.state.collectAsState()
        Sidebar(state, store, onAddProject = {}, onRemoveProject = {}, onCloseWorkspace = {}, onReveal = {}, renamingProject = null, onRenamingProject = {}, renamingWorkspace = null, onRenamingWorkspace = {}, modifier = Modifier)
    }

    // Projects with environments: a client's, the default one, none, and one whose file is gone; a workspace that kept the default after its project
    // changed, and one with none in a project that has one.
    private fun storeWithEnvironments(): KattyStore {
        var n = 0
        val store = KattyStore(newId = { "id${n++}" })
        store.updateSettings { it.copy(defaultEnvironment = "claude-kodein") }
        store.addProject("/Users/me/dev/web-client")
        val sunday = store.addProject("/Users/me/dev/sunday-app")
        store.setProjectEnvironment(sunday, "sunday")
        store.addWorkspace(sunday, "payments")
        val gone = store.addProject("/Users/me/dev/old-client")
        store.setProjectEnvironment(gone, "acme")
        store.updateSettings { it.copy(defaultEnvironment = null) }
        store.addProject("/Users/me/dev/scratch")
        val standard = store.state.value.projects.first { it.id == sunday }.workspaces.last().id
        store.setWorkspacesEnvironment(setOf(standard), null)
        store.selectWorkspace(store.state.value.projects.first { it.id == sunday }.workspaces.first().id)
        return store
    }

    @Composable
    private fun SidebarWithEnvironments(store: KattyStore) {
        val state by store.state.collectAsState()
        Sidebar(
            state, store, onAddProject = {}, onRemoveProject = {}, onCloseWorkspace = {}, onReveal = {}, renamingProject = null, onRenamingProject = {},
            renamingWorkspace = null, onRenamingWorkspace = {}, modifier = Modifier,
            environments = EnvironmentControls(available = setOf("claude-kodein", "claude-perso", "sunday")),
        )
    }

    @Test
    fun drawsTheEnvironmentsOfTheProjectsAndWorkspacesInBothThemes() {
        render("sidebar-environments-light", 560, 700) { SidebarWithEnvironments(storeWithEnvironments()) }
        render("sidebar-environments-dark", 560, 700, dark = true) { SidebarWithEnvironments(storeWithEnvironments()) }
    }

    @Test
    fun drawsTheEnvironmentsPageInBothThemes() {
        val root = java.nio.file.Files.createTempDirectory("katty-environments-render")
        try {
            val shared = root.resolve("environments").also { java.nio.file.Files.createDirectories(it) }
            val secrets = root.resolve("secrets").also { java.nio.file.Files.createDirectories(it) }
            java.nio.file.Files.writeString(shared.resolve("claude-kodein.env"), "CLAUDE_CONFIG_DIR=~/.claude-kodein")
            java.nio.file.Files.writeString(shared.resolve("claude-perso.env"), "CLAUDE_CONFIG_DIR=~/.claude-perso\nunset ANTHROPIC_API_KEY")
            java.nio.file.Files.writeString(secrets.resolve("claude-perso.env"), "GH_TOKEN=x\nnot a line\nB=\$(whoami)")
            val settings = app.katty.core.Settings(environmentDirectories = listOf(shared.toString(), secrets.toString()), defaultEnvironment = "claude-kodein")
            val listing = app.katty.core.Environments.list(settings.environmentDirectories)
            for (dark in listOf(false, true)) {
                render("settings-environments-${if (dark) "dark" else "light"}", 900, 1400, dark = dark) {
                    SettingsContent(
                        SettingsPage.ENVIRONMENTS, {}, settings, {}, emptyList(), emptyList(), app.katty.ShortcutMap.DEFAULT, {}, emptySet(), {},
                        environments = listing, environmentActions = EnvironmentActions(projectsWithoutEnvironment = 2),
                    )
                }
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun drawsTheSidebarInBothThemes() {
        val light = render("sidebar-light", 560, 900) { SidebarOf(store()) }
        val dark = render("sidebar-dark", 560, 900, dark = true) { SidebarOf(store()) }

        // The selected workspace is drawn in the color of a selection.
        assertTrue(light.count(0xD4E2FF) > 1000)
        assertTrue(dark.count(0x2E436E) > 1000)
    }

    @Test
    fun drawsTheWelcomeInBothThemes() {
        val light = render("welcome-light", 1440, 900) { Welcome(onAddProject = {}, modifier = Modifier.fillMaxSize()) }
        val dark = render("welcome-dark", 1440, 900, dark = true) { Welcome(onAddProject = {}, modifier = Modifier.fillMaxSize()) }

        assertTrue(light.count(0xF2F3F5) > 100_000)
        assertTrue(dark.count(0x1E1F22) > 100_000)
    }

    @Test
    fun drawsTheQuestionOfTheIdeOfAFileInBothThemes() {
        val file = app.katty.terminal.TerminalFilePosition("/Users/me/dev/api-gateway/src/main/kotlin/RateLimiter.kt", 42, 5)
        val ides = listOf(app.katty.core.Ide.VS_CODE, app.katty.core.Ide.INTELLIJ_IDEA, app.katty.core.Ide.XCODE, app.katty.core.Ide.FINDER)
        val light = render("open-file-light", 1000, 700) { OpenFileDialog(file, "refactor-auth", ides, onOpen = { _, _ -> }, onCancel = {}) }
        val dark = render("open-file-dark", 1000, 700, dark = true) { OpenFileDialog(file, "refactor-auth", ides, onOpen = { _, _ -> }, onCancel = {}) }

        // The row that the keys are on.
        assertTrue(light.count(0xD4E2FF) > 1000)
        assertTrue(dark.count(0x2E436E) > 1000)
    }
}
